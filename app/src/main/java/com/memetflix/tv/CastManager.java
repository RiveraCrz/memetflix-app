package com.memetflix.tv;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Transmite el stream HLS a la TV sin librerías externas:
 * - Chromecast / Google TV: protocolo Cast v2 (mDNS + TLS al puerto 8009) con el
 *   receptor por defecto de Google.
 * - Smart TV (Samsung, LG, Sony...): UPnP/DLNA AVTransport (SSDP + SOAP).
 * La TV no puede pedir el video directamente (CORS / https), así que lo sirve un
 * pequeño proxy HTTP en este dispositivo.
 */
final class CastManager {

    interface Listener {
        void onDevices(String devicesJson);

        void onStatus(String statusJson);
    }

    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DEFAULT_MEDIA_RECEIVER = "CC1AD845";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final int DISCOVERY_MS = 4000;

    private static final class Device {
        String id;
        String name;
        String type; // "chromecast" | "dlna"
        String host;
        int port;
        String controlUrl;
    }

    private interface Session {
        void load(JSONObject media) throws Exception;

        void control(String action, double value);

        void close();
    }

    private final Context appContext;
    private final Listener listener;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Device> devices = Collections.synchronizedMap(new LinkedHashMap<String, Device>());
    private final MediaProxy proxy = new MediaProxy();

    private volatile boolean discovering = false;
    private Session session;
    private String sessionDeviceId;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    CastManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    // ---------------------------------------------------------------------
    // API usada por el puente JavaScript
    // ---------------------------------------------------------------------

    void discover() {
        if (discovering) {
            emitDevices();
            return;
        }
        discovering = true;
        synchronized (devices) {
            devices.keySet().retainAll(Collections.singleton(sessionDeviceId));
        }
        executor.execute(() -> {
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    lock = wifi.createMulticastLock("memetflix-cast");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
            } catch (Throwable ignored) {
            }
            final WifiManager.MulticastLock heldLock = lock;
            Runnable mdns = () -> safeRun(this::discoverChromecasts);
            Runnable ssdp = () -> safeRun(this::discoverDlna);
            Thread a = new Thread(mdns, "cast-mdns");
            Thread b = new Thread(ssdp, "cast-ssdp");
            a.start();
            b.start();
            try {
                a.join(DISCOVERY_MS + 3000);
                b.join(DISCOVERY_MS + 6000);
            } catch (InterruptedException ignored) {
            }
            if (heldLock != null) {
                try {
                    heldLock.release();
                } catch (Throwable ignored) {
                }
            }
            discovering = false;
            emitDevices();
        });
    }

    void start(String deviceId, String mediaJson) {
        executor.execute(() -> {
            try {
                Device device = devices.get(deviceId);
                if (device == null) throw new IOException("TV no encontrada");
                JSONObject media = new JSONObject(mediaJson);
                synchronized (this) {
                    if (session == null || !deviceId.equals(sessionDeviceId)) {
                        closeSessionLocked();
                        session = "chromecast".equals(device.type)
                                ? new ChromecastSession(device)
                                : new DlnaSession(device);
                        sessionDeviceId = deviceId;
                        acquireLocks();
                    }
                }
                Session target;
                synchronized (this) {
                    target = session;
                }
                emitStatus(deviceId, "connecting", -1, -1, null);
                target.load(media);
            } catch (Throwable e) {
                fail(deviceId, "No se pudo transmitir a la TV: " + e.getMessage());
            }
        });
    }

    void control(String action, double value) {
        executor.execute(() -> {
            Session s;
            synchronized (this) {
                s = session;
                if ("stop".equals(action)) {
                    session = null;
                    sessionDeviceId = null;
                    releaseLocks();
                }
            }
            if (s == null) return;
            if ("stop".equals(action)) {
                s.close();
            } else {
                s.control(action, value);
            }
        });
    }

    void release() {
        synchronized (this) {
            closeSessionLocked();
        }
        proxy.stop();
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------------
    // Utilidades comunes
    // ---------------------------------------------------------------------

    private void closeSessionLocked() {
        if (session != null) {
            final Session old = session;
            session = null;
            sessionDeviceId = null;
            new Thread(old::close, "cast-close").start();
        }
        releaseLocks();
    }

    // Mientras se transmite, el proxy sigue enviando video aunque se apague la pantalla
    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memetflix:cast");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(4 * 60 * 60 * 1000L);
            if (wifiLock == null) {
                WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "memetflix:cast");
                wifiLock.setReferenceCounted(false);
            }
            wifiLock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
    }

    private void fail(String deviceId, String message) {
        synchronized (this) {
            if (deviceId != null && deviceId.equals(sessionDeviceId)) {
                closeSessionLocked();
            }
        }
        emitStatus(deviceId, "error", -1, -1, message);
    }

    private void emitDevices() {
        JSONArray arr = new JSONArray();
        synchronized (devices) {
            for (Device d : devices.values()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", d.id);
                    o.put("name", d.name);
                    o.put("type", d.type);
                    arr.put(o);
                } catch (Throwable ignored) {
                }
            }
        }
        listener.onDevices(arr.toString());
    }

    private void emitStatus(String deviceId, String state, double position, double duration, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("deviceId", deviceId);
            o.put("state", state);
            if (position >= 0) o.put("position", position);
            if (duration > 0) o.put("duration", duration);
            if (message != null) o.put("message", message);
            listener.onStatus(o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void safeRun(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable ignored) {
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** IP de este dispositivo en la red por la que se llega a la TV. */
    private static String localAddressFor(String host) throws IOException {
        DatagramSocket s = new DatagramSocket();
        try {
            s.connect(InetAddress.getByName(host), 9);
            InetAddress local = s.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) throw new IOException("Sin red local");
            return local.getHostAddress();
        } finally {
            s.close();
        }
    }

    private static String httpText(String urlStr, String method, String body, Map<String, String> headers, int timeoutMs)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", UA_DESKTOP);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String text = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
            if (code >= 400) throw new IOException("HTTP " + code + " " + text);
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String xmlTag(String xml, String tag) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + tag + "(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?" + tag + ">").matcher(xml);
        return m.find() ? unescapeXml(m.group(1).trim()) : null;
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    // ---------------------------------------------------------------------
    // Búsqueda de Chromecast (mDNS _googlecast._tcp.local)
    // ---------------------------------------------------------------------

    private void discoverChromecasts() throws IOException {
        final byte[] query = Mdns.buildPtrQuery("_googlecast._tcp.local");
        final InetAddress group = InetAddress.getByName("224.0.0.251");
        final long end = System.currentTimeMillis() + DISCOVERY_MS;

        // Además de la respuesta directa, se escucha el grupo mDNS por si la TV responde ahí
        Thread groupListener = new Thread(() -> safeRun(() -> {
            java.net.MulticastSocket ms = new java.net.MulticastSocket(null);
            try {
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(5353));
                ms.joinGroup(group);
                receiveMdns(ms, end, null, group);
            } finally {
                ms.close();
            }
        }), "cast-mdns-group");
        groupListener.setDaemon(true);
        groupListener.start();

        DatagramSocket socket = new DatagramSocket();
        try {
            receiveMdns(socket, end, query, group);
        } finally {
            socket.close();
        }
    }

    private void receiveMdns(DatagramSocket socket, long end, byte[] query, InetAddress group) throws IOException {
        socket.setSoTimeout(400);
        long nextSend = 0;
        byte[] buf = new byte[9000];
        while (System.currentTimeMillis() < end) {
            if (query != null && System.currentTimeMillis() >= nextSend) {
                socket.send(new DatagramPacket(query, query.length, group, 5353));
                nextSend = System.currentTimeMillis() + 1200;
            }
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            List<Mdns.Service> services = Mdns.parse(packet.getData(), packet.getLength(), "_googlecast._tcp.local");
            for (Mdns.Service svc : services) {
                Device d = new Device();
                d.type = "chromecast";
                d.host = svc.address != null ? svc.address : packet.getAddress().getHostAddress();
                d.port = svc.port > 0 ? svc.port : 8009;
                d.name = svc.friendlyName != null ? svc.friendlyName : svc.instance;
                d.id = "cc:" + d.host + ":" + d.port;
                if (devices.put(d.id, d) == null) emitDevices();
            }
        }
    }

    /** Lector mínimo de paquetes DNS para mDNS. */
    static final class Mdns {
        static final class Service {
            String instance;
            String friendlyName;
            String address;
            int port;
        }

        static byte[] buildPtrQuery(String name) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] header = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};
            out.write(header, 0, header.length);
            for (String label : name.split("\\.")) {
                byte[] b = label.getBytes(StandardCharsets.UTF_8);
                out.write(b.length);
                out.write(b, 0, b.length);
            }
            out.write(0);
            out.write(0);
            out.write(12); // PTR
            out.write(0x80); // QU: pedir respuesta directa
            out.write(1); // IN
            return out.toByteArray();
        }

        static List<Service> parse(byte[] data, int length, String serviceType) {
            List<Service> result = new ArrayList<>();
            try {
                int qd = u16(data, 4);
                int records = u16(data, 6) + u16(data, 8) + u16(data, 10);
                int[] pos = {12};
                for (int i = 0; i < qd; i++) {
                    readName(data, length, pos);
                    pos[0] += 4;
                }
                List<String> instances = new ArrayList<>();
                Map<String, int[]> srvPort = new HashMap<>();
                Map<String, String> srvTarget = new HashMap<>();
                Map<String, String> txtName = new HashMap<>();
                Map<String, String> aRecords = new HashMap<>();
                String suffix = serviceType.toLowerCase(Locale.ROOT);
                for (int i = 0; i < records && pos[0] < length; i++) {
                    String name = readName(data, length, pos);
                    int type = u16(data, pos[0]);
                    int rdLen = u16(data, pos[0] + 8);
                    int rdStart = pos[0] + 10;
                    if (rdStart + rdLen > length) break;
                    String key = name.toLowerCase(Locale.ROOT);
                    if (type == 12 && key.equals(suffix)) {
                        int[] p = {rdStart};
                        instances.add(readName(data, length, p));
                    } else if (type == 33) {
                        srvPort.put(key, new int[]{u16(data, rdStart + 4)});
                        int[] p = {rdStart + 6};
                        srvTarget.put(key, readName(data, length, p).toLowerCase(Locale.ROOT));
                    } else if (type == 16) {
                        int p = rdStart;
                        while (p < rdStart + rdLen) {
                            int l = data[p] & 0xff;
                            String entry = new String(data, p + 1, Math.min(l, rdStart + rdLen - p - 1), StandardCharsets.UTF_8);
                            if (entry.startsWith("fn=")) txtName.put(key, entry.substring(3));
                            p += l + 1;
                        }
                    } else if (type == 1 && rdLen == 4) {
                        aRecords.put(key, (data[rdStart] & 0xff) + "." + (data[rdStart + 1] & 0xff) + "."
                                + (data[rdStart + 2] & 0xff) + "." + (data[rdStart + 3] & 0xff));
                    }
                    pos[0] = rdStart + rdLen;
                }
                // Algunos equipos mandan solo SRV/TXT sin el PTR
                for (String key : srvPort.keySet()) {
                    if (key.endsWith("." + suffix) && !containsIgnoreCase(instances, key)) instances.add(key);
                }
                for (String inst : instances) {
                    String key = inst.toLowerCase(Locale.ROOT);
                    Service s = new Service();
                    String label = inst.endsWith("." + serviceType) || key.endsWith("." + suffix)
                            ? inst.substring(0, inst.length() - suffix.length() - 1) : inst;
                    s.instance = label;
                    s.friendlyName = txtName.get(key);
                    int[] port = srvPort.get(key);
                    s.port = port != null ? port[0] : 0;
                    String target = srvTarget.get(key);
                    s.address = target != null ? aRecords.get(target) : null;
                    result.add(s);
                }
            } catch (Throwable ignored) {
            }
            return result;
        }

        private static boolean containsIgnoreCase(List<String> list, String value) {
            for (String s : list) if (s.equalsIgnoreCase(value)) return true;
            return false;
        }

        private static int u16(byte[] d, int i) {
            return ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff);
        }

        private static String readName(byte[] d, int length, int[] pos) {
            StringBuilder sb = new StringBuilder();
            int p = pos[0];
            boolean jumped = false;
            int guard = 0;
            while (p < length && guard++ < 128) {
                int len = d[p] & 0xff;
                if (len == 0) {
                    p++;
                    break;
                }
                if ((len & 0xc0) == 0xc0) {
                    int ptr = ((len & 0x3f) << 8) | (d[p + 1] & 0xff);
                    if (!jumped) pos[0] = p + 2;
                    jumped = true;
                    p = ptr;
                    continue;
                }
                if (sb.length() > 0) sb.append('.');
                sb.append(new String(d, p + 1, len, StandardCharsets.UTF_8));
                p += len + 1;
            }
            if (!jumped) pos[0] = p;
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------------
    // Búsqueda de Smart TV (SSDP / UPnP AVTransport)
    // ---------------------------------------------------------------------

    private void discoverDlna() throws IOException {
        String msearch = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + AVTRANSPORT + "\r\n\r\n";
        byte[] payload = msearch.getBytes(StandardCharsets.US_ASCII);
        byte[] payloadRenderer = msearch.replace(AVTRANSPORT, "urn:schemas-upnp-org:device:MediaRenderer:1")
                .getBytes(StandardCharsets.US_ASCII);
        Set<String> locations = new HashSet<>();
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(400);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long end = System.currentTimeMillis() + DISCOVERY_MS;
            long nextSend = 0;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    socket.send(new DatagramPacket(payload, payload.length, group, 1900));
                    socket.send(new DatagramPacket(payloadRenderer, payloadRenderer.length, group, 1900));
                    nextSend = System.currentTimeMillis() + 1500;
                }
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?im)^LOCATION:\\s*(\\S+)").matcher(text);
                if (m.find() && locations.add(m.group(1))) {
                    final String location = m.group(1);
                    executor.execute(() -> safeRun(() -> describeDlna(location)));
                }
            }
        } finally {
            socket.close();
        }
    }

    private void describeDlna(String location) throws IOException {
        String xml = httpText(location, "GET", null, null, 3000);
        String controlPath = null;
        Matcher services = Pattern.compile("<(?:\\w+:)?service>([\\s\\S]*?)</(?:\\w+:)?service>").matcher(xml);
        while (services.find()) {
            String block = services.group(1);
            String type = xmlTag(block, "serviceType");
            if (type != null && type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) {
                controlPath = xmlTag(block, "controlURL");
                break;
            }
        }
        if (controlPath == null) return;
        String base = xmlTag(xml, "URLBase");
        URL baseUrl = new URL(base != null && !base.isEmpty() ? base : location);
        Device d = new Device();
        d.type = "dlna";
        d.controlUrl = new URL(baseUrl, controlPath).toString();
        d.host = new URL(location).getHost();
        String name = xmlTag(xml, "friendlyName");
        d.name = name != null && !name.isEmpty() ? name : ("TV " + d.host);
        d.id = "dlna:" + location;
        if (devices.put(d.id, d) == null) emitDevices();
    }

    // ---------------------------------------------------------------------
    // Sesión Chromecast (Cast v2)
    // ---------------------------------------------------------------------

    private final class ChromecastSession implements Session {
        private final Device device;
        private final String senderId = "sender-" + Integer.toHexString((int) (Math.random() * 0xffffff));
        private SSLSocket socket;
        private OutputStream out;
        private volatile boolean closed = false;
        private int requestId = 1;
        private String transportId;
        private String appSessionId;
        private int mediaSessionId = -1;
        private int ignoredMediaSessionId = -1;
        private JSONObject pendingLoad;
        private boolean endedSent = false;
        private double lastDuration = -1;
        private ScheduledFuture<?> heartbeat;

        ChromecastSession(Device device) {
            this.device = device;
        }

        @Override
        public void load(JSONObject media) throws Exception {
            String localIp;
            synchronized (this) {
                if (socket == null) connect();
                localIp = socket.getLocalAddress().getHostAddress();
                // Los avisos del video anterior (ej. "terminó") ya no cuentan
                if (mediaSessionId >= 0) ignoredMediaSessionId = mediaSessionId;
                mediaSessionId = -1;
                pendingLoad = null;
                endedSent = false;
                lastDuration = -1;
                if (transportId == null) {
                    JSONObject launch = new JSONObject();
                    launch.put("type", "LAUNCH");
                    launch.put("appId", DEFAULT_MEDIA_RECEIVER);
                    launch.put("requestId", requestId++);
                    send(NS_RECEIVER, "receiver-0", launch);
                }
            }
            // Fuera del candado: revisa el stream por red mientras la TV abre el reproductor
            JSONObject load = buildLoad(media, localIp);
            synchronized (this) {
                pendingLoad = load;
                sendLoad();
            }
        }

        private JSONObject buildLoad(JSONObject media, String localIp) throws Exception {
            String url = media.getString("url");
            String proxied = proxy.register(url, localIp);
            boolean fmp4 = proxy.looksLikeFmp4(url);

            JSONObject metadata = new JSONObject();
            metadata.put("metadataType", 0);
            metadata.put("title", media.optString("title", "Memetflix"));
            String poster = media.optString("poster", "");
            if (!poster.isEmpty()) {
                JSONArray images = new JSONArray();
                images.put(new JSONObject().put("url", poster));
                metadata.put("images", images);
            }

            JSONObject info = new JSONObject();
            info.put("contentId", proxied);
            info.put("contentUrl", proxied);
            info.put("contentType", "application/x-mpegurl");
            info.put("streamType", "BUFFERED");
            info.put("metadata", metadata);
            if (fmp4) {
                info.put("hlsSegmentFormat", "fmp4");
                info.put("hlsVideoSegmentFormat", "fmp4");
            }

            JSONArray tracks = new JSONArray();
            JSONArray subs = media.optJSONArray("subtitles");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject sub = subs.optJSONObject(i);
                    if (sub == null || sub.optString("file").isEmpty()) continue;
                    JSONObject t = new JSONObject();
                    t.put("trackId", tracks.length() + 1);
                    t.put("type", "TEXT");
                    t.put("subtype", "SUBTITLES");
                    t.put("trackContentId", proxy.register(sub.getString("file"), localIp));
                    t.put("trackContentType", "text/vtt");
                    t.put("name", sub.optString("label", "Español"));
                    t.put("language", "es");
                    tracks.put(t);
                }
            }
            if (tracks.length() > 0) info.put("tracks", tracks);

            JSONObject load = new JSONObject();
            load.put("type", "LOAD");
            load.put("media", info);
            load.put("autoplay", true);
            load.put("currentTime", media.optDouble("startTime", 0));
            if (tracks.length() > 0) load.put("activeTrackIds", new JSONArray().put(1));
            return load;
        }

        private void sendLoad() throws Exception {
            if (pendingLoad == null || transportId == null) return;
            JSONObject load = pendingLoad;
            pendingLoad = null;
            load.put("requestId", requestId++);
            load.put("sessionId", appSessionId);
            send(NS_MEDIA, transportId, load);
        }

        private void connect() throws Exception {
            SSLContext ctx = SSLContext.getInstance("TLS");
            // El Chromecast usa un certificado propio: solo se habla con la IP que respondió en la red local
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(device.host, device.port), 6000);
            s.startHandshake();
            socket = s;
            out = s.getOutputStream();
            send(NS_CONNECTION, "receiver-0", new JSONObject().put("type", "CONNECT"));
            Thread reader = new Thread(this::readLoop, "cast-reader");
            reader.setDaemon(true);
            reader.start();
            heartbeat = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    send(NS_HEARTBEAT, "receiver-0", new JSONObject().put("type", "PING"));
                    synchronized (ChromecastSession.this) {
                        if (transportId != null && mediaSessionId >= 0) {
                            send(NS_MEDIA, transportId, new JSONObject()
                                    .put("type", "GET_STATUS")
                                    .put("requestId", requestId++)
                                    .put("mediaSessionId", mediaSessionId));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        private void send(String namespace, String destination, JSONObject payload) throws IOException {
            byte[] msg = CastProtocol.encode(senderId, destination, namespace, payload.toString());
            synchronized (this) {
                if (out == null) throw new IOException("Sin conexión");
                byte[] len = {(byte) (msg.length >>> 24), (byte) (msg.length >>> 16), (byte) (msg.length >>> 8), (byte) msg.length};
                out.write(len);
                out.write(msg);
                out.flush();
            }
        }

        private void readLoop() {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (!closed) {
                    int len = in.readInt();
                    if (len <= 0 || len > 1 << 20) throw new IOException("Mensaje inválido");
                    byte[] data = new byte[len];
                    in.readFully(data);
                    CastProtocol.Message m = CastProtocol.decode(data);
                    if (m != null && m.payload != null) handle(m);
                }
            } catch (Throwable e) {
                if (!closed) {
                    closed = true;
                    cleanup();
                    fail(device.id, "Se perdió la conexión con la TV");
                }
            }
        }

        private synchronized void handle(CastProtocol.Message m) throws Exception {
            JSONObject p = new JSONObject(m.payload);
            String type = p.optString("type");
            if (NS_HEARTBEAT.equals(m.namespace)) {
                if ("PING".equals(type)) send(NS_HEARTBEAT, m.source, new JSONObject().put("type", "PONG"));
            } else if (NS_CONNECTION.equals(m.namespace)) {
                if ("CLOSE".equals(type) && m.source.equals(transportId)) remoteStopped();
            } else if (NS_RECEIVER.equals(m.namespace)) {
                if ("RECEIVER_STATUS".equals(type)) onReceiverStatus(p);
                else if ("LAUNCH_ERROR".equals(type)) failAndClose("La TV no pudo abrir el reproductor");
            } else if (NS_MEDIA.equals(m.namespace)) {
                if ("MEDIA_STATUS".equals(type)) onMediaStatus(p);
                else if ("LOAD_FAILED".equals(type)) failAndClose("La TV no pudo reproducir este video");
            }
        }

        private void onReceiverStatus(JSONObject p) throws Exception {
            JSONObject status = p.optJSONObject("status");
            JSONArray apps = status != null ? status.optJSONArray("applications") : null;
            JSONObject ours = null;
            if (apps != null) {
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.optJSONObject(i);
                    if (app != null && DEFAULT_MEDIA_RECEIVER.equals(app.optString("appId"))) ours = app;
                }
            }
            if (ours == null) {
                // Otra app tomó la TV o la apagaron desde el control
                if (transportId != null) remoteStopped();
                return;
            }
            String newTransport = ours.optString("transportId");
            if (!newTransport.equals(transportId)) {
                transportId = newTransport;
                appSessionId = ours.optString("sessionId");
                send(NS_CONNECTION, transportId, new JSONObject().put("type", "CONNECT"));
            }
            sendLoad();
        }

        private void onMediaStatus(JSONObject p) {
            JSONArray list = p.optJSONArray("status");
            if (list == null || list.length() == 0) return;
            JSONObject st = list.optJSONObject(0);
            if (st == null) return;
            int id = st.optInt("mediaSessionId", -1);
            if (id >= 0 && id == ignoredMediaSessionId) return;
            if (id >= 0) mediaSessionId = id;
            JSONObject media = st.optJSONObject("media");
            if (media != null && media.optDouble("duration", -1) > 0) lastDuration = media.optDouble("duration");
            double position = st.optDouble("currentTime", -1);
            String player = st.optString("playerState");
            String state;
            if ("PLAYING".equals(player)) state = "playing";
            else if ("PAUSED".equals(player)) state = "paused";
            else if ("BUFFERING".equals(player) || "LOADING".equals(player)) state = "buffering";
            else if ("IDLE".equals(player)) {
                String reason = st.optString("idleReason");
                if ("FINISHED".equals(reason) && !endedSent) {
                    endedSent = true;
                    state = "ended";
                } else if ("ERROR".equals(reason)) {
                    failAndClose("La TV no pudo reproducir este video");
                    return;
                } else {
                    return;
                }
            } else {
                return;
            }
            emitStatus(device.id, state, position, lastDuration, null);
        }

        private void failAndClose(String message) {
            closed = true;
            cleanup();
            fail(device.id, message);
        }

        private void remoteStopped() {
            closed = true;
            cleanup();
            synchronized (CastManager.this) {
                if (session == this) {
                    session = null;
                    sessionDeviceId = null;
                    releaseLocks();
                }
            }
            emitStatus(device.id, "stopped", -1, -1, null);
        }

        @Override
        public void control(String action, double value) {
            try {
                synchronized (this) {
                    if (transportId == null || mediaSessionId < 0) return;
                    JSONObject msg = new JSONObject();
                    msg.put("mediaSessionId", mediaSessionId);
                    msg.put("requestId", requestId++);
                    if ("play".equals(action)) msg.put("type", "PLAY");
                    else if ("pause".equals(action)) msg.put("type", "PAUSE");
                    else if ("seek".equals(action)) {
                        msg.put("type", "SEEK");
                        msg.put("currentTime", value);
                    } else return;
                    send(NS_MEDIA, transportId, msg);
                }
            } catch (Throwable e) {
                failAndClose("Se perdió la conexión con la TV");
            }
        }

        @Override
        public void close() {
            try {
                synchronized (this) {
                    if (appSessionId != null && out != null) {
                        send(NS_RECEIVER, "receiver-0", new JSONObject()
                                .put("type", "STOP")
                                .put("sessionId", appSessionId)
                                .put("requestId", requestId++));
                    }
                }
            } catch (Throwable ignored) {
            }
            closed = true;
            cleanup();
        }

        private void cleanup() {
            if (heartbeat != null) heartbeat.cancel(false);
            try {
                if (socket != null) socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Mensajes CastMessage (protobuf) codificados a mano: solo se usan campos de texto. */
    static final class CastProtocol {
        static final class Message {
            String source;
            String destination;
            String namespace;
            String payload;
        }

        static byte[] encode(String source, String destination, String namespace, String payload) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x08); // protocol_version = CASTV2_1_0
            out.write(0x00);
            writeString(out, 0x12, source);
            writeString(out, 0x1a, destination);
            writeString(out, 0x22, namespace);
            out.write(0x28); // payload_type = STRING
            out.write(0x00);
            writeString(out, 0x32, payload);
            return out.toByteArray();
        }

        static Message decode(byte[] data) {
            Message m = new Message();
            int[] pos = {0};
            while (pos[0] < data.length) {
                long tag = readVarint(data, pos);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    readVarint(data, pos);
                } else if (wire == 2) {
                    int len = (int) readVarint(data, pos);
                    String value = new String(data, pos[0], len, StandardCharsets.UTF_8);
                    pos[0] += len;
                    if (field == 2) m.source = value;
                    else if (field == 3) m.destination = value;
                    else if (field == 4) m.namespace = value;
                    else if (field == 6) m.payload = value;
                } else {
                    return null;
                }
            }
            return m;
        }

        private static void writeString(ByteArrayOutputStream out, int tag, String value) {
            byte[] b = value.getBytes(StandardCharsets.UTF_8);
            out.write(tag);
            int len = b.length;
            while ((len & ~0x7f) != 0) {
                out.write((len & 0x7f) | 0x80);
                len >>>= 7;
            }
            out.write(len);
            out.write(b, 0, b.length);
        }

        private static long readVarint(byte[] data, int[] pos) {
            long result = 0;
            int shift = 0;
            while (pos[0] < data.length) {
                int b = data[pos[0]++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }
    }

    // ---------------------------------------------------------------------
    // Sesión DLNA / UPnP AVTransport
    // ---------------------------------------------------------------------

    private final class DlnaSession implements Session {
        private final Device device;
        private ScheduledFuture<?> poller;
        private volatile boolean closed = false;
        private double pendingSeek = 0;
        private boolean wasPlaying = false;
        private boolean endedSent = false;
        private double lastPosition = 0;
        private double lastDuration = 0;
        private int failures = 0;
        private final java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean(false);

        DlnaSession(Device device) {
            this.device = device;
        }

        @Override
        public synchronized void load(JSONObject media) throws Exception {
            String localIp = localAddressFor(device.host);
            String url = proxy.register(media.getString("url"), localIp);
            String title = media.optString("title", "Memetflix");
            String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                    + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                    + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                    + "<dc:title>" + escapeXml(title) + "</dc:title>"
                    + "<upnp:class>object.item.videoItem</upnp:class>"
                    + "<res protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\">" + escapeXml(url) + "</res>"
                    + "</item></DIDL-Lite>";
            try {
                soap("Stop", "");
            } catch (Throwable ignored) {
            }
            soap("SetAVTransportURI", "<CurrentURI>" + escapeXml(url) + "</CurrentURI>"
                    + "<CurrentURIMetaData>" + escapeXml(didl) + "</CurrentURIMetaData>");
            soap("Play", "<Speed>1</Speed>");
            pendingSeek = media.optDouble("startTime", 0);
            wasPlaying = false;
            endedSent = false;
            lastPosition = pendingSeek;
            lastDuration = 0;
            if (poller == null) {
                poller = scheduler.scheduleWithFixedDelay(() -> {
                    if (polling.compareAndSet(false, true)) {
                        executor.execute(() -> {
                            try {
                                poll();
                            } finally {
                                polling.set(false);
                            }
                        });
                    }
                }, 1, 1, TimeUnit.SECONDS);
            }
        }

        private synchronized void poll() {
            if (closed) return;
            try {
                String info = soap("GetTransportInfo", "");
                String transport = xmlTag(info, "CurrentTransportState");
                String pos = soap("GetPositionInfo", "");
                double position = parseTime(xmlTag(pos, "RelTime"));
                double duration = parseTime(xmlTag(pos, "TrackDuration"));
                failures = 0;
                if (position >= 0) lastPosition = position;
                if (duration > 0) lastDuration = duration;
                String state;
                if ("PLAYING".equals(transport)) {
                    state = "playing";
                    wasPlaying = true;
                    if (pendingSeek > 5) {
                        double target = pendingSeek;
                        pendingSeek = 0;
                        soap("Seek", "<Unit>REL_TIME</Unit><Target>" + formatTime(target) + "</Target>");
                    }
                } else if ("PAUSED_PLAYBACK".equals(transport)) {
                    state = "paused";
                } else if ("TRANSITIONING".equals(transport)) {
                    state = "buffering";
                } else if (("STOPPED".equals(transport) || "NO_MEDIA_PRESENT".equals(transport)) && wasPlaying) {
                    wasPlaying = false;
                    if (lastDuration > 0 && lastDuration - lastPosition < 30 && !endedSent) {
                        endedSent = true;
                        emitStatus(device.id, "ended", lastPosition, lastDuration, null);
                    } else if (!endedSent) {
                        stopFromTv();
                    }
                    return;
                } else {
                    return;
                }
                emitStatus(device.id, state, lastPosition, lastDuration, null);
            } catch (Throwable e) {
                if (++failures >= 5) {
                    closed = true;
                    if (poller != null) poller.cancel(false);
                    fail(device.id, "Se perdió la conexión con la TV");
                }
            }
        }

        private void stopFromTv() {
            closed = true;
            if (poller != null) poller.cancel(false);
            synchronized (CastManager.this) {
                if (session == this) {
                    session = null;
                    sessionDeviceId = null;
                    releaseLocks();
                }
            }
            emitStatus(device.id, "stopped", -1, -1, null);
        }

        @Override
        public void control(String action, double value) {
            try {
                if ("play".equals(action)) soap("Play", "<Speed>1</Speed>");
                else if ("pause".equals(action)) soap("Pause", "");
                else if ("seek".equals(action)) {
                    soap("Seek", "<Unit>REL_TIME</Unit><Target>" + formatTime(value) + "</Target>");
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void close() {
            closed = true;
            if (poller != null) poller.cancel(false);
            try {
                soap("Stop", "");
            } catch (Throwable ignored) {
            }
        }

        private String soap(String action, String args) throws IOException {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                    + "<u:" + action + " xmlns:u=\"" + AVTRANSPORT + "\"><InstanceID>0</InstanceID>" + args
                    + "</u:" + action + "></s:Body></s:Envelope>";
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/xml; charset=\"utf-8\"");
            headers.put("SOAPAction", "\"" + AVTRANSPORT + "#" + action + "\"");
            return httpText(device.controlUrl, "POST", body, headers, 5000);
        }
    }

    private static double parseTime(String s) {
        if (s == null || s.isEmpty() || s.startsWith("NOT")) return -1;
        try {
            String[] parts = s.split(":");
            double total = 0;
            for (String part : parts) total = total * 60 + Double.parseDouble(part);
            return total;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String formatTime(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    // ---------------------------------------------------------------------
    // Proxy HTTP local: la TV pide aquí el .m3u8, los segmentos y los .vtt
    // ---------------------------------------------------------------------

    private final class MediaProxy {
        private ServerSocket server;
        private final Set<String> allowedHosts = Collections.synchronizedSet(new HashSet<String>());
        private final Pattern uriAttr = Pattern.compile("URI=\"([^\"]+)\"");

        synchronized String register(String url, String localIp) throws IOException {
            ensureStarted();
            allowedHosts.add(new URL(url).getHost().toLowerCase(Locale.ROOT));
            return "http://" + localIp + ":" + server.getLocalPort() + proxyPath(url);
        }

        /** Revisa la primera variante: si usa #EXT-X-MAP los segmentos son fMP4. */
        boolean looksLikeFmp4(String url) {
            try {
                String playlist = httpText(url, "GET", null, null, 6000);
                if (playlist.contains("#EXT-X-STREAM-INF")) {
                    for (String line : playlist.split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty() && !t.startsWith("#")) {
                            playlist = httpText(new URL(new URL(url), t).toString(), "GET", null, null, 6000);
                            break;
                        }
                    }
                }
                return playlist.contains("#EXT-X-MAP");
            } catch (Throwable e) {
                return false;
            }
        }

        private String proxyPath(String url) throws IOException {
            String path = new URL(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "");
            if (name.isEmpty()) name = "media";
            String token = Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return "/m/" + token + "/" + name;
        }

        private void ensureStarted() throws IOException {
            if (server != null && !server.isClosed()) return;
            server = new ServerSocket(0);
            final ServerSocket s = server;
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        final Socket client = s.accept();
                        executor.execute(() -> handle(client));
                    } catch (Throwable e) {
                        if (s.isClosed()) return;
                    }
                }
            }, "cast-proxy");
            t.setDaemon(true);
            t.start();
        }

        synchronized void stop() {
            try {
                if (server != null) server.close();
            } catch (Throwable ignored) {
            }
            server = null;
        }

        private void handle(Socket client) {
            HttpURLConnection conn = null;
            try {
                client.setSoTimeout(20000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                String requestLine = readLine(in);
                if (requestLine == null) return;
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int idx = line.indexOf(':');
                    if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT), line.substring(idx + 1).trim());
                }
                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";

                if ("OPTIONS".equals(method)) {
                    writeHead(out, 204, "No Content", null, -1, null);
                    return;
                }
                String upstream = decodePath(path);
                if (upstream == null || !allowedHosts.contains(new URL(upstream).getHost().toLowerCase(Locale.ROOT))) {
                    writeHead(out, 404, "Not Found", "text/plain", 0, null);
                    return;
                }

                boolean head = "HEAD".equals(method);
                conn = (HttpURLConnection) new URL(upstream).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(20000);
                conn.setRequestMethod(head ? "HEAD" : "GET");
                conn.setRequestProperty("User-Agent", UA_DESKTOP);
                if (headers.containsKey("range")) conn.setRequestProperty("Range", headers.get("range"));
                int code = conn.getResponseCode();
                String type = conn.getContentType();
                String lowerPath = new URL(upstream).getPath().toLowerCase(Locale.ROOT);
                boolean playlist = lowerPath.endsWith(".m3u8") || (type != null && type.toLowerCase(Locale.ROOT).contains("mpegurl"));

                if (code >= 400) {
                    writeHead(out, code, "Error", "text/plain", 0, null);
                    return;
                }
                if (playlist) {
                    byte[] body = head ? new byte[0] : rewritePlaylist(
                            new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8), conn.getURL().toString(),
                            client.getLocalAddress().getHostAddress()).getBytes(StandardCharsets.UTF_8);
                    writeHead(out, 200, "OK", "application/vnd.apple.mpegurl", head ? -1 : body.length, null);
                    out.write(body);
                    out.flush();
                    return;
                }

                if (lowerPath.endsWith(".vtt")) type = "text/vtt; charset=utf-8";
                Map<String, String> extra = new HashMap<>();
                String range = conn.getHeaderField("Content-Range");
                if (range != null) extra.put("Content-Range", range);
                extra.put("Accept-Ranges", "bytes");
                long length = -1;
                try {
                    String lengthHeader = conn.getHeaderField("Content-Length");
                    if (lengthHeader != null) length = Long.parseLong(lengthHeader.trim());
                } catch (NumberFormatException ignored) {
                }
                writeHead(out, code == 206 ? 206 : 200, code == 206 ? "Partial Content" : "OK",
                        type != null ? type : "application/octet-stream", length, extra);
                if (!head) {
                    InputStream body = conn.getInputStream();
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = body.read(buf)) != -1) out.write(buf, 0, n);
                    body.close();
                }
                out.flush();
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
                try {
                    client.close();
                } catch (Throwable ignored) {
                }
            }
        }

        private String rewritePlaylist(String text, String baseUrl, String localIp) throws IOException {
            URL base = new URL(baseUrl);
            StringBuilder sb = new StringBuilder();
            for (String rawLine : text.split("\r?\n")) {
                String t = rawLine.trim();
                if (t.isEmpty()) {
                    sb.append('\n');
                } else if (t.startsWith("#")) {
                    Matcher m = uriAttr.matcher(t);
                    StringBuffer out = new StringBuffer();
                    while (m.find()) {
                        String abs = new URL(base, m.group(1)).toString();
                        m.appendReplacement(out, Matcher.quoteReplacement("URI=\"" + register(abs, localIp) + "\""));
                    }
                    m.appendTail(out);
                    sb.append(out).append('\n');
                } else {
                    sb.append(register(new URL(base, t).toString(), localIp)).append('\n');
                }
            }
            return sb.toString();
        }

        private String decodePath(String path) {
            try {
                if (!path.startsWith("/m/")) return null;
                int end = path.indexOf('/', 3);
                String token = end > 0 ? path.substring(3, end) : path.substring(3);
                token = URLDecoder.decode(token, "UTF-8");
                return new String(Base64.decode(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                        StandardCharsets.UTF_8);
            } catch (Throwable e) {
                return null;
            }
        }

        private void writeHead(OutputStream out, int code, String reason, String type, long length,
                               Map<String, String> extra) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
            if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
            if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: *\r\n");
            sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n");
            sb.append("transferMode.dlna.org: Streaming\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        }

        private String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 8192) throw new IOException("Cabecera demasiado larga");
            }
            return c == -1 && sb.length() == 0 ? null : sb.toString();
        }
    }
}
