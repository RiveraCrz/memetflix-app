package com.memetflix.tv;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descargas para ver sin conexión. Baja el .m3u8 (una calidad de hasta 720p, con
 * todos sus idiomas de audio), los segmentos y los subtítulos a la carpeta de la app,
 * y los sirve a la interfaz como https://memetflix.offline/<id>/... desde
 * shouldInterceptRequest, así hls.js los reproduce igual que en línea.
 */
final class OfflineStore {

    interface Listener {
        void onUpdate(String itemJson);
    }

    /** Vuelve a pedir el .m3u8 cuando los enlaces del servidor ya caducaron. */
    interface StreamResolver {
        JSONObject resolve(String postId);
    }

    static final String OFFLINE_HOST = "memetflix.offline";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_HEIGHT = 720;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private static OfflineStore instance;

    static synchronized OfflineStore get(Context context) {
        if (instance == null) instance = new OfflineStore(context.getApplicationContext());
        return instance;
    }

    private static final class Task {
        final String url;
        final String file;

        Task(String url, String file) {
            this.url = url;
            this.file = file;
        }
    }

    private static final class ExpiredException extends IOException {
        ExpiredException(String message) {
            super(message);
        }
    }

    private final class Job {
        final String id;
        JSONObject meta;
        String state = "queued"; // queued | downloading | paused | done | error
        int done;
        int total;
        long bytes;
        long created;
        String error;
        JSONArray subtitles = new JSONArray();
        volatile boolean cancelled;
        Future<?> future;

        Job(String id) {
            this.id = id;
        }

        File dir() {
            return new File(root, safeId(id));
        }
    }

    private final File root;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile StreamResolver resolver;
    private long lastNotify;

    private OfflineStore(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        root = new File(base, "offline");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        loadJobs();
    }

    // ---------------------------------------------------------------------
    // API
    // ---------------------------------------------------------------------

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void setResolver(StreamResolver r) {
        resolver = r;
    }

    synchronized String listJson() {
        JSONArray arr = new JSONArray();
        for (Job job : jobs.values()) arr.put(toJson(job));
        return arr.toString();
    }

    synchronized boolean hasActiveDownloads() {
        for (Job job : jobs.values()) {
            if ("queued".equals(job.state) || "downloading".equals(job.state)) return true;
        }
        return false;
    }

    /** Texto para la notificación: "Título — 45%". */
    synchronized String activeSummary() {
        for (Job job : jobs.values()) {
            if ("downloading".equals(job.state)) {
                int pct = job.total > 0 ? (int) (job.done * 100L / job.total) : 0;
                return job.meta.optString("title", "Memetflix") + " — " + pct + "%";
            }
        }
        return "En espera...";
    }

    synchronized int activeProgress() {
        for (Job job : jobs.values()) {
            if ("downloading".equals(job.state) && job.total > 0) return (int) (job.done * 100L / job.total);
        }
        return 0;
    }

    synchronized void start(String metaJson) throws Exception {
        JSONObject meta = new JSONObject(metaJson);
        String id = meta.getString("id");
        Job job = jobs.get(id);
        if (job != null) {
            if (!"done".equals(job.state)) resume(id);
            return;
        }
        job = new Job(id);
        job.meta = meta;
        job.created = System.currentTimeMillis();
        jobs.put(id, job);
        //noinspection ResultOfMethodCallIgnored
        job.dir().mkdirs();
        saveJob(job);
        enqueue(job);
    }

    synchronized void pause(String id) {
        Job job = jobs.get(id);
        if (job == null || "done".equals(job.state)) return;
        job.cancelled = true;
        if (job.future != null) job.future.cancel(false);
        job.state = "paused";
        saveJob(job);
        notifyJob(job, true);
    }

    synchronized void resume(String id) {
        Job job = jobs.get(id);
        if (job == null || "done".equals(job.state)
                || "downloading".equals(job.state) || "queued".equals(job.state)) return;
        job.error = null;
        enqueue(job);
    }

    synchronized void delete(String id) {
        Job job = jobs.remove(id);
        if (job == null) return;
        job.cancelled = true;
        if (job.future != null) job.future.cancel(false);
        final File dir = job.dir();
        new Thread(() -> deleteRecursive(dir), "offline-delete").start();
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("state", "deleted");
            emit(o.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Respuesta para https://memetflix.offline/<id>/<archivo>, o null si no existe. */
    WebResourceResponse serve(Uri uri) {
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 2) return null;
        String id = parts.get(0);
        String name = parts.get(1);
        if (!SAFE_NAME.matcher(id).matches() || !SAFE_NAME.matcher(name).matches()) return null;
        File file = new File(new File(root, id), name);
        if (!file.isFile()) return null;
        try {
            String lower = name.toLowerCase(Locale.ROOT);
            String type = lower.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                    : lower.endsWith(".vtt") ? "text/vtt" : "application/octet-stream";
            WebResourceResponse response = new WebResourceResponse(type, "UTF-8", new FileInputStream(file));
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Content-Length", String.valueOf(file.length()));
            response.setResponseHeaders(headers);
            response.setStatusCodeAndReasonPhrase(200, "OK");
            return response;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Descarga
    // ---------------------------------------------------------------------

    private void enqueue(final Job job) {
        job.cancelled = false;
        job.state = "queued";
        saveJob(job);
        notifyJob(job, true);
        job.future = runner.submit(() -> run(job));
    }

    private void run(Job job) {
        synchronized (this) {
            if (job.cancelled || !jobs.containsKey(job.id)) return;
            job.state = "downloading";
        }
        notifyJob(job, true);
        try {
            String m3u8 = job.meta.getString("m3u8");
            JSONArray subs = job.meta.optJSONArray("subtitles");
            List<Task> tasks;
            boolean refreshed = false;
            try {
                tasks = prepare(job, m3u8, subs);
            } catch (ExpiredException e) {
                JSONObject fresh = refreshStream(job);
                tasks = prepare(job, fresh.getString("m3u8"), fresh.optJSONArray("subtitles"));
                refreshed = true;
            }
            try {
                downloadAll(job, tasks);
            } catch (ExpiredException e) {
                if (refreshed) throw e;
                JSONObject fresh = refreshStream(job);
                downloadAll(job, prepare(job, fresh.getString("m3u8"), fresh.optJSONArray("subtitles")));
            }
            synchronized (this) {
                if (job.cancelled || !jobs.containsKey(job.id)) return;
                job.state = "done";
                job.error = null;
                saveJob(job);
            }
            notifyJob(job, true);
        } catch (Throwable e) {
            synchronized (this) {
                if (job.cancelled || !jobs.containsKey(job.id)) return;
                job.state = "error";
                job.error = describe(e);
                saveJob(job);
            }
            notifyJob(job, true);
        }
    }

    private JSONObject refreshStream(Job job) throws Exception {
        StreamResolver r = resolver;
        JSONObject fresh = r != null ? r.resolve(job.id) : null;
        if (fresh == null || fresh.optString("m3u8").isEmpty()) {
            throw new IOException("El enlace del video caducó");
        }
        job.meta.put("m3u8", fresh.getString("m3u8"));
        if (fresh.optJSONArray("subtitles") != null) job.meta.put("subtitles", fresh.getJSONArray("subtitles"));
        saveJob(job);
        return fresh;
    }

    private String describe(Throwable e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("ENOSPC") || msg.toLowerCase(Locale.ROOT).contains("no space")) return "Sin espacio en el dispositivo";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) return "Sin conexión";
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    /** Escribe las listas locales y devuelve lo que hay que bajar. */
    private List<Task> prepare(Job job, String masterUrl, JSONArray subs) throws Exception {
        File dir = job.dir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        List<Task> tasks = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        String[] fetched = fetchText(masterUrl);
        String master = fetched[0];
        String masterBase = fetched[1];

        if (master.contains("#EXT-X-STREAM-INF")) {
            String[] lines = master.split("\r?\n");
            String bestAttrs = null;
            String bestUri = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
                String uri = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String t = lines[j].trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        uri = t;
                        break;
                    }
                }
                if (uri == null) continue;
                int height = parseHeight(line);
                long bandwidth = parseLong(attr(line, "BANDWIDTH"));
                // Hasta 720p la mejor; si todas son mayores, la más baja
                int score = height > 0
                        ? (height <= MAX_HEIGHT ? height : -height)
                        : (bandwidth <= 3_000_000 ? (int) (bandwidth / 1000) : (int) (-bandwidth / 1000));
                if (score > bestScore) {
                    bestScore = score;
                    bestAttrs = line;
                    bestUri = uri;
                }
            }
            if (bestUri == null) throw new IOException("Lista de video vacía");
            String group = attr(bestAttrs, "AUDIO");
            StringBuilder local = new StringBuilder("#EXTM3U\n");
            int audioIndex = 0;
            for (String raw : lines) {
                String line = raw.trim();
                if (!line.startsWith("#EXT-X-MEDIA:") || !line.contains("TYPE=AUDIO")) continue;
                if (group == null || !group.equals(attr(line, "GROUP-ID"))) continue;
                String uri = attr(line, "URI");
                if (uri != null) {
                    String name = "a" + audioIndex++;
                    mediaPlaylist(new URL(new URL(masterBase), uri).toString(), name, dir, tasks, names);
                    line = line.replace("URI=\"" + uri + "\"", "URI=\"" + name + ".m3u8\"");
                }
                local.append(line).append('\n');
            }
            String variant = bestAttrs.replaceAll(",?(SUBTITLES|CLOSED-CAPTIONS)=(\"[^\"]*\"|NONE)", "")
                    .replace("#EXT-X-STREAM-INF:,", "#EXT-X-STREAM-INF:");
            local.append(variant).append('\n').append("v.m3u8\n");
            mediaPlaylist(new URL(new URL(masterBase), bestUri).toString(), "v", dir, tasks, names);
            writeText(new File(dir, "master.m3u8"), local.toString());
        } else {
            mediaPlaylistFromText(master, masterBase, "v", dir, tasks, names);
            writeText(new File(dir, "master.m3u8"), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nv.m3u8\n");
        }

        JSONArray localSubs = new JSONArray();
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject sub = subs.optJSONObject(i);
                if (sub == null || sub.optString("file").isEmpty()) continue;
                String name = "sub" + i + ".vtt";
                tasks.add(new Task(sub.getString("file"), name));
                JSONObject o = new JSONObject();
                o.put("file", "https://" + OFFLINE_HOST + "/" + safeId(job.id) + "/" + name);
                o.put("label", sub.optString("label", "Español"));
                localSubs.put(o);
            }
        }
        synchronized (this) {
            job.subtitles = localSubs;
            job.total = tasks.size();
        }
        return tasks;
    }

    private void mediaPlaylist(String url, String prefix, File dir, List<Task> tasks, Map<String, String> names)
            throws Exception {
        String[] fetched = fetchText(url);
        mediaPlaylistFromText(fetched[0], fetched[1], prefix, dir, tasks, names);
    }

    private void mediaPlaylistFromText(String text, String baseUrl, String prefix, File dir, List<Task> tasks,
                                       Map<String, String> names) throws Exception {
        URL base = new URL(baseUrl);
        StringBuilder out = new StringBuilder();
        int[] counter = {0};
        for (String raw : text.split("\r?\n")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(t);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String local = localName(new URL(base, m.group(1)).toString(), prefix, counter, tasks, names);
                    m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + local + "\""));
                }
                m.appendTail(sb);
                out.append(sb).append('\n');
            } else {
                out.append(localName(new URL(base, t).toString(), prefix, counter, tasks, names)).append('\n');
            }
        }
        writeText(new File(dir, prefix + ".m3u8"), out.toString());
    }

    // El mismo archivo (ej. con EXT-X-BYTERANGE) se baja una sola vez
    private String localName(String url, String prefix, int[] counter, List<Task> tasks, Map<String, String> names) {
        String existing = names.get(url);
        if (existing != null) return existing;
        String path;
        try {
            path = new URL(url).getPath();
        } catch (Exception e) {
            path = "";
        }
        String ext = ".ts";
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot > path.lastIndexOf('/')) {
            String candidate = path.substring(dot).toLowerCase(Locale.ROOT);
            if (candidate.matches("\\.[a-z0-9]{1,5}")) ext = candidate;
        }
        String name = String.format(Locale.ROOT, "%s_%05d%s", prefix, counter[0]++, ext);
        names.put(url, name);
        tasks.add(new Task(url, name));
        return name;
    }

    private void downloadAll(final Job job, List<Task> tasks) throws Exception {
        final File dir = job.dir();
        final AtomicInteger done = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();
        List<Task> pending = new ArrayList<>();
        for (Task task : tasks) {
            File f = new File(dir, task.file);
            if (f.isFile()) {
                done.incrementAndGet();
                bytes.addAndGet(f.length());
            } else {
                pending.add(task);
            }
        }
        synchronized (this) {
            job.done = done.get();
            job.bytes = bytes.get();
        }
        notifyJob(job, true);

        List<Future<?>> futures = new ArrayList<>();
        final IOException[] failure = {null};
        for (final Task task : pending) {
            futures.add(workers.submit(() -> {
                if (job.cancelled || failure[0] != null) return;
                try {
                    long size = downloadFile(task.url, new File(dir, task.file), job);
                    synchronized (OfflineStore.this) {
                        job.done = done.incrementAndGet();
                        job.bytes = bytes.addAndGet(size);
                    }
                    notifyJob(job, false);
                } catch (IOException e) {
                    synchronized (failure) {
                        if (failure[0] == null) failure[0] = e;
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        if (job.cancelled) return;
        if (failure[0] != null) throw failure[0];
        saveJob(job);
    }

    private long downloadFile(String url, File target, Job job) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (job.cancelled) throw new IOException("Cancelado");
            File part = new File(target.getPath() + ".part");
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", UA_DESKTOP);
                int code = conn.getResponseCode();
                if (code == 403 || code == 404 || code == 410) throw new ExpiredException("HTTP " + code);
                if (code >= 400) throw new IOException("HTTP " + code);
                long size = 0;
                InputStream in = conn.getInputStream();
                OutputStream out = new FileOutputStream(part);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (job.cancelled) throw new IOException("Cancelado");
                        out.write(buf, 0, n);
                        size += n;
                    }
                } finally {
                    out.close();
                    in.close();
                }
                if (!part.renameTo(target)) throw new IOException("No se pudo guardar " + target.getName());
                return size;
            } catch (ExpiredException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    throw new IOException("Cancelado");
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last;
    }

    /** Devuelve {texto, url final tras redirecciones}. */
    private String[] fetchText(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA_DESKTOP);
            int code = conn.getResponseCode();
            if (code == 403 || code == 404 || code == 410) throw new ExpiredException("HTTP " + code);
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return new String[]{new String(bos.toByteArray(), StandardCharsets.UTF_8), conn.getURL().toString()};
        } finally {
            conn.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // Estado en disco
    // ---------------------------------------------------------------------

    private void loadJobs() {
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        List<Job> loaded = new ArrayList<>();
        for (File dir : dirs) {
            File f = new File(dir, "job.json");
            if (!f.isFile()) continue;
            try {
                JSONObject o = new JSONObject(readText(f));
                Job job = new Job(o.getString("id"));
                job.meta = o.getJSONObject("meta");
                job.state = o.optString("state", "paused");
                job.done = o.optInt("done");
                job.total = o.optInt("total");
                job.bytes = o.optLong("bytes");
                job.created = o.optLong("created");
                job.error = o.has("error") ? o.optString("error") : null;
                job.subtitles = o.optJSONArray("subtitles") != null ? o.getJSONArray("subtitles") : new JSONArray();
                // Lo que quedó a medias al cerrar la app espera a que el usuario lo reanude
                if ("downloading".equals(job.state) || "queued".equals(job.state)) job.state = "paused";
                loaded.add(job);
            } catch (Throwable ignored) {
            }
        }
        java.util.Collections.sort(loaded, (a, b) -> Long.compare(a.created, b.created));
        for (Job job : loaded) jobs.put(job.id, job);
    }

    private synchronized void saveJob(Job job) {
        if (!jobs.containsKey(job.id)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("id", job.id);
            o.put("meta", job.meta);
            o.put("state", job.state);
            o.put("done", job.done);
            o.put("total", job.total);
            o.put("bytes", job.bytes);
            o.put("created", job.created);
            if (job.error != null) o.put("error", job.error);
            o.put("subtitles", job.subtitles);
            //noinspection ResultOfMethodCallIgnored
            job.dir().mkdirs();
            writeText(new File(job.dir(), "job.json"), o.toString());
        } catch (Throwable ignored) {
        }
    }

    private synchronized JSONObject toJson(Job job) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", job.id);
            o.put("state", job.state);
            o.put("progress", "done".equals(job.state) ? 1.0 : (job.total > 0 ? (double) job.done / job.total : 0));
            o.put("bytes", job.bytes);
            o.put("created", job.created);
            o.put("meta", job.meta);
            if (job.error != null) o.put("error", job.error);
            if ("done".equals(job.state)) {
                o.put("playUrl", "https://" + OFFLINE_HOST + "/" + safeId(job.id) + "/master.m3u8");
                o.put("subtitles", job.subtitles);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private void notifyJob(Job job, boolean force) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (!force && now - lastNotify < 500) return;
            lastNotify = now;
        }
        emit(toJson(job).toString());
    }

    private void emit(String json) {
        for (Listener l : listeners) {
            try {
                l.onUpdate(json);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------

    static String safeId(String id) {
        String s = id.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isEmpty() ? "_" : s;
    }

    private static String attr(String line, String name) {
        if (line == null) return null;
        Matcher m = Pattern.compile("(?:^|[:,])" + name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    private static int parseHeight(String line) {
        String res = attr(line, "RESOLUTION");
        if (res == null || !res.contains("x")) return 0;
        try {
            return Integer.parseInt(res.substring(res.indexOf('x') + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) throw new IOException("No se pudo guardar " + file.getName());
    }

    private static String readText(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
