// Transmitir a la TV desde Windows, igual que CastManager.java en Android:
// - Chromecast / Google TV: protocolo Cast v2 (mDNS + TLS al puerto 8009).
// - Smart TV (Samsung, LG, Sony...): UPnP/DLNA AVTransport (SSDP + SOAP).
// La TV pide el video a un pequeño proxy HTTP en esta PC (evita CORS / https).
const dgram = require("dgram");
const http = require("http");
const os = require("os");
const tls = require("tls");
const { Readable } = require("stream");
const { net } = require("electron");

const UA_DESKTOP =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const DEFAULT_MEDIA_RECEIVER = "CC1AD845";
const NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
const NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
const NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
const NS_MEDIA = "urn:x-cast:com.google.cast.media";
const AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
const DISCOVERY_MS = 4000;

// ---------------------------------------------------------------------------
// Utilidades
// ---------------------------------------------------------------------------

function xmlTag(xml, tag) {
  const m = new RegExp(`<(?:\\w+:)?${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?${tag}>`).exec(xml);
  return m ? unescapeXml(m[1].trim()) : null;
}

function escapeXml(s) {
  return String(s || "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

function unescapeXml(s) {
  return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&amp;/g, "&");
}

function parseTime(s) {
  if (!s || s.startsWith("NOT")) return -1;
  const total = s.split(":").reduce((acc, part) => acc * 60 + parseFloat(part), 0);
  return Number.isFinite(total) ? total : -1;
}

function formatTime(seconds) {
  const s = Math.max(0, Math.round(seconds));
  const pad = (n) => String(n).padStart(2, "0");
  return `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

async function httpText(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), options.timeout || 6000);
  try {
    const res = await net.fetch(url, {
      method: options.method || "GET",
      headers: Object.assign({ "User-Agent": UA_DESKTOP }, options.headers || {}),
      body: options.body,
      signal: controller.signal
    });
    const text = await res.text();
    if (res.status >= 400) throw new Error(`HTTP ${res.status} ${text}`);
    return text;
  } finally {
    clearTimeout(timer);
  }
}

// IP de esta PC en la red por la que se llega a la TV
function localAddressFor(host) {
  return new Promise((resolve, reject) => {
    const s = dgram.createSocket("udp4");
    s.connect(9, host, () => {
      try {
        const addr = s.address().address;
        s.close();
        resolve(addr);
      } catch (e) {
        s.close();
        reject(e);
      }
    });
    s.on("error", (e) => {
      s.close();
      reject(e);
    });
  });
}

function ipv4Interfaces() {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === "IPv4" && !i.internal)
    .map((i) => i.address);
}

// ---------------------------------------------------------------------------
// mDNS mínimo para _googlecast._tcp.local
// ---------------------------------------------------------------------------

function buildPtrQuery(name) {
  const parts = [Buffer.from([0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0])];
  for (const label of name.split(".")) {
    const b = Buffer.from(label, "utf8");
    parts.push(Buffer.from([b.length]), b);
  }
  parts.push(Buffer.from([0, 0, 12, 0x80, 1])); // PTR, IN + QU
  return Buffer.concat(parts);
}

function readName(buf, pos) {
  const labels = [];
  let p = pos;
  let end = -1;
  for (let guard = 0; p < buf.length && guard < 128; guard++) {
    const len = buf[p];
    if (len === 0) {
      p++;
      break;
    }
    if ((len & 0xc0) === 0xc0) {
      if (end < 0) end = p + 2;
      p = ((len & 0x3f) << 8) | buf[p + 1];
      continue;
    }
    labels.push(buf.toString("utf8", p + 1, p + 1 + len));
    p += len + 1;
  }
  return { name: labels.join("."), next: end >= 0 ? end : p };
}

function parseMdns(buf, serviceType) {
  const suffix = serviceType.toLowerCase();
  const services = [];
  try {
    const qd = buf.readUInt16BE(4);
    const records = buf.readUInt16BE(6) + buf.readUInt16BE(8) + buf.readUInt16BE(10);
    let pos = 12;
    for (let i = 0; i < qd; i++) pos = readName(buf, pos).next + 4;
    const instances = new Set();
    const srv = {};
    const txt = {};
    const a = {};
    for (let i = 0; i < records && pos < buf.length; i++) {
      const r = readName(buf, pos);
      const key = r.name.toLowerCase();
      const type = buf.readUInt16BE(r.next);
      const rdLen = buf.readUInt16BE(r.next + 8);
      const rd = r.next + 10;
      if (rd + rdLen > buf.length) break;
      if (type === 12 && key === suffix) {
        instances.add(readName(buf, rd).name.toLowerCase());
      } else if (type === 33) {
        srv[key] = { port: buf.readUInt16BE(rd + 4), target: readName(buf, rd + 6).name.toLowerCase(), name: r.name };
      } else if (type === 16) {
        let p = rd;
        while (p < rd + rdLen) {
          const l = buf[p];
          const entry = buf.toString("utf8", p + 1, Math.min(p + 1 + l, rd + rdLen));
          if (entry.startsWith("fn=")) txt[key] = entry.slice(3);
          p += l + 1;
        }
      } else if (type === 1 && rdLen === 4) {
        a[key] = `${buf[rd]}.${buf[rd + 1]}.${buf[rd + 2]}.${buf[rd + 3]}`;
      }
      pos = rd + rdLen;
    }
    for (const key of Object.keys(srv)) if (key.endsWith("." + suffix)) instances.add(key);
    for (const key of instances) {
      const s = srv[key];
      const original = s ? s.name : key;
      services.push({
        instance: original.slice(0, original.length - suffix.length - 1),
        friendlyName: txt[key] || null,
        port: s ? s.port : 0,
        address: s ? a[s.target] || null : null
      });
    }
  } catch (e) {
    // paquete incompleto: se ignora
  }
  return services;
}

// ---------------------------------------------------------------------------
// CastMessage (protobuf) codificado a mano
// ---------------------------------------------------------------------------

function varint(n) {
  const out = [];
  while (n > 0x7f) {
    out.push((n & 0x7f) | 0x80);
    n >>>= 7;
  }
  out.push(n);
  return Buffer.from(out);
}

function encodeCastMessage(source, destination, namespace, payload) {
  const field = (tag, value) => {
    const b = Buffer.from(value, "utf8");
    return Buffer.concat([Buffer.from([tag]), varint(b.length), b]);
  };
  return Buffer.concat([
    Buffer.from([0x08, 0x00]),
    field(0x12, source),
    field(0x1a, destination),
    field(0x22, namespace),
    Buffer.from([0x28, 0x00]),
    field(0x32, payload)
  ]);
}

function decodeCastMessage(buf) {
  const m = {};
  let pos = 0;
  const readVarint = () => {
    let result = 0;
    let shift = 0;
    while (pos < buf.length) {
      const b = buf[pos++];
      result += (b & 0x7f) * 2 ** shift;
      if ((b & 0x80) === 0) break;
      shift += 7;
    }
    return result;
  };
  while (pos < buf.length) {
    const tag = readVarint();
    const fieldNo = Math.floor(tag / 8);
    const wire = tag & 7;
    if (wire === 0) {
      readVarint();
    } else if (wire === 2) {
      const len = readVarint();
      const value = buf.toString("utf8", pos, pos + len);
      pos += len;
      if (fieldNo === 2) m.source = value;
      else if (fieldNo === 3) m.destination = value;
      else if (fieldNo === 4) m.namespace = value;
      else if (fieldNo === 6) m.payload = value;
    } else {
      return null;
    }
  }
  return m;
}

// ---------------------------------------------------------------------------
// Proxy HTTP local
// ---------------------------------------------------------------------------

class MediaProxy {
  constructor() {
    this.server = null;
    this.port = 0;
    this.allowedHosts = new Set();
  }

  async ensureStarted() {
    if (this.server) return;
    this.server = http.createServer((req, res) => {
      this.handle(req, res).catch(() => {
        if (!res.headersSent) res.writeHead(502, this.corsHeaders());
        res.end();
      });
    });
    await new Promise((resolve, reject) => {
      this.server.once("error", reject);
      this.server.listen(0, "0.0.0.0", resolve);
    });
    this.port = this.server.address().port;
  }

  stop() {
    if (this.server) this.server.close();
    this.server = null;
  }

  async register(url, localIp) {
    await this.ensureStarted();
    return this.registerSync(url, localIp);
  }

  registerSync(url, localIp) {
    const u = new URL(url);
    this.allowedHosts.add(u.hostname.toLowerCase());
    const name = (u.pathname.split("/").pop() || "").replace(/[^A-Za-z0-9._-]/g, "") || "media";
    const token = Buffer.from(url, "utf8").toString("base64url");
    return `http://${localIp}:${this.port}/m/${token}/${name}`;
  }

  // Si la primera variante usa #EXT-X-MAP los segmentos son fMP4
  async looksLikeFmp4(url) {
    try {
      let playlist = await httpText(url);
      if (playlist.includes("#EXT-X-STREAM-INF")) {
        const first = playlist.split("\n").map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
        if (first) playlist = await httpText(new URL(first, url).toString());
      }
      return playlist.includes("#EXT-X-MAP");
    } catch (e) {
      return false;
    }
  }

  corsHeaders() {
    return {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
      "Access-Control-Allow-Headers": "*",
      "Access-Control-Expose-Headers": "Content-Length, Content-Range",
      "transferMode.dlna.org": "Streaming"
    };
  }

  async handle(req, res) {
    if (req.method === "OPTIONS") {
      res.writeHead(204, this.corsHeaders());
      res.end();
      return;
    }
    const m = /^\/m\/([^/]+)/.exec(req.url || "");
    let upstream = null;
    try {
      upstream = m ? Buffer.from(decodeURIComponent(m[1]), "base64url").toString("utf8") : null;
      if (upstream && !this.allowedHosts.has(new URL(upstream).hostname.toLowerCase())) upstream = null;
    } catch (e) {
      upstream = null;
    }
    if (!upstream) {
      res.writeHead(404, this.corsHeaders());
      res.end();
      return;
    }

    const head = req.method === "HEAD";
    const headers = { "User-Agent": UA_DESKTOP };
    if (req.headers.range) headers.Range = req.headers.range;
    const upstreamRes = await net.fetch(upstream, { method: head ? "HEAD" : "GET", headers });
    const type = upstreamRes.headers.get("content-type") || "";
    const pathname = new URL(upstream).pathname.toLowerCase();
    const playlist = pathname.endsWith(".m3u8") || type.toLowerCase().includes("mpegurl");

    if (upstreamRes.status >= 400) {
      res.writeHead(upstreamRes.status, this.corsHeaders());
      res.end();
      return;
    }
    if (playlist) {
      const localIp = req.socket.localAddress.replace(/^::ffff:/, "");
      const body = head ? "" : this.rewritePlaylist(await upstreamRes.text(), upstreamRes.url || upstream, localIp);
      res.writeHead(200, Object.assign(this.corsHeaders(), {
        "Content-Type": "application/vnd.apple.mpegurl",
        "Content-Length": Buffer.byteLength(body)
      }));
      res.end(body);
      return;
    }

    const out = Object.assign(this.corsHeaders(), {
      "Content-Type": pathname.endsWith(".vtt") ? "text/vtt; charset=utf-8" : (type || "application/octet-stream"),
      "Accept-Ranges": "bytes"
    });
    const length = upstreamRes.headers.get("content-length");
    const range = upstreamRes.headers.get("content-range");
    if (length) out["Content-Length"] = length;
    if (range) out["Content-Range"] = range;
    res.writeHead(upstreamRes.status === 206 ? 206 : 200, out);
    if (head || !upstreamRes.body) {
      res.end();
      return;
    }
    const stream = Readable.fromWeb(upstreamRes.body);
    stream.on("error", () => res.destroy());
    res.on("close", () => stream.destroy());
    stream.pipe(res);
  }

  rewritePlaylist(text, baseUrl, localIp) {
    return text.split(/\r?\n/).map((line) => {
      const t = line.trim();
      if (!t) return "";
      if (t.startsWith("#")) {
        return t.replace(/URI="([^"]+)"/g, (_m, uri) => `URI="${this.registerSync(new URL(uri, baseUrl).toString(), localIp)}"`);
      }
      return this.registerSync(new URL(t, baseUrl).toString(), localIp);
    }).join("\n");
  }
}

// ---------------------------------------------------------------------------
// Sesión Chromecast
// ---------------------------------------------------------------------------

class ChromecastSession {
  constructor(manager, device) {
    this.manager = manager;
    this.device = device;
    this.senderId = "sender-" + Math.floor(Math.random() * 0xffffff).toString(16);
    this.socket = null;
    this.closed = false;
    this.requestId = 1;
    this.transportId = null;
    this.appSessionId = null;
    this.mediaSessionId = -1;
    this.ignoredMediaSessionId = -1;
    this.pendingLoad = null;
    this.endedSent = false;
    this.lastDuration = -1;
    this.buffer = Buffer.alloc(0);
    this.timer = null;
  }

  connect() {
    return new Promise((resolve, reject) => {
      // El Chromecast usa un certificado propio: solo se habla con la IP que respondió en la red local
      const socket = tls.connect({ host: this.device.host, port: this.device.port, rejectUnauthorized: false, timeout: 6000 });
      socket.once("secureConnect", () => {
        socket.setTimeout(0);
        this.socket = socket;
        this.send(NS_CONNECTION, "receiver-0", { type: "CONNECT" });
        this.timer = setInterval(() => {
          this.send(NS_HEARTBEAT, "receiver-0", { type: "PING" });
          if (this.transportId && this.mediaSessionId >= 0) {
            this.send(NS_MEDIA, this.transportId, {
              type: "GET_STATUS",
              requestId: this.requestId++,
              mediaSessionId: this.mediaSessionId
            });
          }
        }, 1000);
        resolve();
      });
      socket.on("data", (chunk) => this.onData(chunk));
      socket.on("timeout", () => socket.destroy(new Error("timeout")));
      socket.on("error", (e) => {
        if (!this.socket) reject(e);
      });
      socket.on("close", () => {
        if (this.socket && !this.closed) this.failAndClose("Se perdió la conexión con la TV");
      });
    });
  }

  send(namespace, destination, payload) {
    if (!this.socket || this.closed) return;
    const msg = encodeCastMessage(this.senderId, destination, namespace, JSON.stringify(payload));
    const len = Buffer.alloc(4);
    len.writeUInt32BE(msg.length);
    this.socket.write(Buffer.concat([len, msg]));
  }

  onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 4) {
      const len = this.buffer.readUInt32BE(0);
      if (this.buffer.length < 4 + len) return;
      const m = decodeCastMessage(this.buffer.subarray(4, 4 + len));
      this.buffer = this.buffer.subarray(4 + len);
      if (m && m.payload) {
        try {
          this.handle(m, JSON.parse(m.payload));
        } catch (e) {
          // mensaje que no es JSON: se ignora
        }
      }
    }
  }

  handle(m, p) {
    const type = p.type;
    if (m.namespace === NS_HEARTBEAT) {
      if (type === "PING") this.send(NS_HEARTBEAT, m.source, { type: "PONG" });
    } else if (m.namespace === NS_CONNECTION) {
      if (type === "CLOSE" && m.source === this.transportId) this.remoteStopped();
    } else if (m.namespace === NS_RECEIVER) {
      if (type === "RECEIVER_STATUS") this.onReceiverStatus(p);
      else if (type === "LAUNCH_ERROR") this.failAndClose("La TV no pudo abrir el reproductor");
    } else if (m.namespace === NS_MEDIA) {
      if (type === "MEDIA_STATUS") this.onMediaStatus(p);
      else if (type === "LOAD_FAILED") this.failAndClose("La TV no pudo reproducir este video");
    }
  }

  onReceiverStatus(p) {
    const apps = (p.status && p.status.applications) || [];
    const ours = apps.find((a) => a.appId === DEFAULT_MEDIA_RECEIVER);
    if (!ours) {
      // Otra app tomó la TV o la apagaron desde el control
      if (this.transportId) this.remoteStopped();
      return;
    }
    if (ours.transportId !== this.transportId) {
      this.transportId = ours.transportId;
      this.appSessionId = ours.sessionId;
      this.send(NS_CONNECTION, this.transportId, { type: "CONNECT" });
    }
    this.sendLoad();
  }

  onMediaStatus(p) {
    const st = (p.status || [])[0];
    if (!st) return;
    const id = typeof st.mediaSessionId === "number" ? st.mediaSessionId : -1;
    if (id >= 0 && id === this.ignoredMediaSessionId) return;
    if (id >= 0) this.mediaSessionId = id;
    if (st.media && st.media.duration > 0) this.lastDuration = st.media.duration;
    let state;
    if (st.playerState === "PLAYING") state = "playing";
    else if (st.playerState === "PAUSED") state = "paused";
    else if (st.playerState === "BUFFERING" || st.playerState === "LOADING") state = "buffering";
    else if (st.playerState === "IDLE") {
      if (st.idleReason === "FINISHED" && !this.endedSent) {
        this.endedSent = true;
        state = "ended";
      } else if (st.idleReason === "ERROR") {
        this.failAndClose("La TV no pudo reproducir este video");
        return;
      } else {
        return;
      }
    } else {
      return;
    }
    this.manager.emitStatus(this.device.id, state, st.currentTime, this.lastDuration);
  }

  async load(media) {
    if (!this.socket) await this.connect();
    const localIp = this.socket.localAddress.replace(/^::ffff:/, "");
    // Los avisos del video anterior (ej. "terminó") ya no cuentan
    if (this.mediaSessionId >= 0) this.ignoredMediaSessionId = this.mediaSessionId;
    this.mediaSessionId = -1;
    this.pendingLoad = null;
    this.endedSent = false;
    this.lastDuration = -1;
    if (!this.transportId) {
      this.send(NS_RECEIVER, "receiver-0", { type: "LAUNCH", appId: DEFAULT_MEDIA_RECEIVER, requestId: this.requestId++ });
    }
    this.pendingLoad = await this.buildLoad(media, localIp);
    this.sendLoad();
  }

  async buildLoad(media, localIp) {
    const proxy = this.manager.proxy;
    const proxied = await proxy.register(media.url, localIp);
    const info = {
      contentId: proxied,
      contentUrl: proxied,
      contentType: "application/x-mpegurl",
      streamType: "BUFFERED",
      metadata: {
        metadataType: 0,
        title: media.title || "Memetflix",
        images: media.poster ? [{ url: media.poster }] : []
      }
    };
    if (await proxy.looksLikeFmp4(media.url)) {
      info.hlsSegmentFormat = "fmp4";
      info.hlsVideoSegmentFormat = "fmp4";
    }
    const tracks = [];
    for (const sub of media.subtitles || []) {
      if (!sub || !sub.file) continue;
      tracks.push({
        trackId: tracks.length + 1,
        type: "TEXT",
        subtype: "SUBTITLES",
        trackContentId: await proxy.register(sub.file, localIp),
        trackContentType: "text/vtt",
        name: sub.label || "Español",
        language: "es"
      });
    }
    if (tracks.length) info.tracks = tracks;
    const load = { type: "LOAD", media: info, autoplay: true, currentTime: Number(media.startTime) || 0 };
    if (tracks.length) load.activeTrackIds = [1];
    return load;
  }

  sendLoad() {
    if (!this.pendingLoad || !this.transportId) return;
    const load = this.pendingLoad;
    this.pendingLoad = null;
    load.requestId = this.requestId++;
    load.sessionId = this.appSessionId;
    this.send(NS_MEDIA, this.transportId, load);
  }

  control(action, value) {
    if (!this.transportId || this.mediaSessionId < 0) return;
    const msg = { mediaSessionId: this.mediaSessionId, requestId: this.requestId++ };
    if (action === "play") msg.type = "PLAY";
    else if (action === "pause") msg.type = "PAUSE";
    else if (action === "seek") Object.assign(msg, { type: "SEEK", currentTime: value });
    else return;
    this.send(NS_MEDIA, this.transportId, msg);
  }

  failAndClose(message) {
    this.cleanup();
    this.manager.fail(this, message);
  }

  remoteStopped() {
    this.cleanup();
    this.manager.sessionEnded(this);
  }

  close() {
    if (this.appSessionId) {
      this.send(NS_RECEIVER, "receiver-0", { type: "STOP", sessionId: this.appSessionId, requestId: this.requestId++ });
    }
    setTimeout(() => this.cleanup(), 300);
  }

  cleanup() {
    this.closed = true;
    clearInterval(this.timer);
    if (this.socket) this.socket.destroy();
  }
}

// ---------------------------------------------------------------------------
// Sesión DLNA / UPnP AVTransport
// ---------------------------------------------------------------------------

class DlnaSession {
  constructor(manager, device) {
    this.manager = manager;
    this.device = device;
    this.closed = false;
    this.timer = null;
    this.polling = false;
    this.pendingSeek = 0;
    this.wasPlaying = false;
    this.endedSent = false;
    this.lastPosition = 0;
    this.lastDuration = 0;
    this.failures = 0;
  }

  soap(action, args) {
    const body = '<?xml version="1.0" encoding="utf-8"?>'
      + '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
      + 's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
      + `<u:${action} xmlns:u="${AVTRANSPORT}"><InstanceID>0</InstanceID>${args}</u:${action}>`
      + "</s:Body></s:Envelope>";
    return httpText(this.device.controlUrl, {
      method: "POST",
      body,
      timeout: 5000,
      headers: { "Content-Type": 'text/xml; charset="utf-8"', SOAPAction: `"${AVTRANSPORT}#${action}"` }
    });
  }

  async load(media) {
    const localIp = await localAddressFor(this.device.host);
    const url = await this.manager.proxy.register(media.url, localIp);
    const didl = '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
      + 'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
      + '<item id="0" parentID="-1" restricted="1">'
      + `<dc:title>${escapeXml(media.title || "Memetflix")}</dc:title>`
      + "<upnp:class>object.item.videoItem</upnp:class>"
      + `<res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">${escapeXml(url)}</res>`
      + "</item></DIDL-Lite>";
    await this.soap("Stop", "").catch(() => {});
    await this.soap("SetAVTransportURI",
      `<CurrentURI>${escapeXml(url)}</CurrentURI><CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>`);
    await this.soap("Play", "<Speed>1</Speed>");
    this.pendingSeek = Number(media.startTime) || 0;
    this.wasPlaying = false;
    this.endedSent = false;
    this.lastPosition = this.pendingSeek;
    this.lastDuration = 0;
    if (!this.timer) {
      this.timer = setInterval(() => {
        if (this.polling || this.closed) return;
        this.polling = true;
        this.poll().finally(() => {
          this.polling = false;
        });
      }, 1000);
    }
  }

  async poll() {
    try {
      const transport = xmlTag(await this.soap("GetTransportInfo", ""), "CurrentTransportState");
      const pos = await this.soap("GetPositionInfo", "");
      if (this.closed) return;
      const position = parseTime(xmlTag(pos, "RelTime"));
      const duration = parseTime(xmlTag(pos, "TrackDuration"));
      this.failures = 0;
      if (position >= 0) this.lastPosition = position;
      if (duration > 0) this.lastDuration = duration;
      let state;
      if (transport === "PLAYING") {
        state = "playing";
        this.wasPlaying = true;
        if (this.pendingSeek > 5) {
          const target = this.pendingSeek;
          this.pendingSeek = 0;
          await this.soap("Seek", `<Unit>REL_TIME</Unit><Target>${formatTime(target)}</Target>`);
        }
      } else if (transport === "PAUSED_PLAYBACK") {
        state = "paused";
      } else if (transport === "TRANSITIONING") {
        state = "buffering";
      } else if ((transport === "STOPPED" || transport === "NO_MEDIA_PRESENT") && this.wasPlaying) {
        this.wasPlaying = false;
        if (this.lastDuration > 0 && this.lastDuration - this.lastPosition < 30 && !this.endedSent) {
          this.endedSent = true;
          this.manager.emitStatus(this.device.id, "ended", this.lastPosition, this.lastDuration);
        } else if (!this.endedSent) {
          this.cleanup();
          this.manager.sessionEnded(this);
        }
        return;
      } else {
        return;
      }
      this.manager.emitStatus(this.device.id, state, this.lastPosition, this.lastDuration);
    } catch (e) {
      if (++this.failures >= 5 && !this.closed) {
        this.cleanup();
        this.manager.fail(this, "Se perdió la conexión con la TV");
      }
    }
  }

  control(action, value) {
    let p = null;
    if (action === "play") p = this.soap("Play", "<Speed>1</Speed>");
    else if (action === "pause") p = this.soap("Pause", "");
    else if (action === "seek") p = this.soap("Seek", `<Unit>REL_TIME</Unit><Target>${formatTime(value)}</Target>`);
    if (p) p.catch(() => {});
  }

  close() {
    this.cleanup();
    this.soap("Stop", "").catch(() => {});
  }

  cleanup() {
    this.closed = true;
    clearInterval(this.timer);
  }
}

// ---------------------------------------------------------------------------
// Administrador
// ---------------------------------------------------------------------------

class CastManager {
  constructor({ onDevices, onStatus }) {
    this.onDevices = onDevices;
    this.onStatus = onStatus;
    this.devices = new Map();
    this.proxy = new MediaProxy();
    this.session = null;
    this.sessionDeviceId = null;
    this.discovering = false;
  }

  emitDevices() {
    this.onDevices([...this.devices.values()].map((d) => ({ id: d.id, name: d.name, type: d.type })));
  }

  emitStatus(deviceId, state, position, duration, message) {
    const status = { deviceId, state };
    if (Number.isFinite(position) && position >= 0) status.position = position;
    if (duration > 0) status.duration = duration;
    if (message) status.message = message;
    this.onStatus(status);
  }

  addDevice(d) {
    const isNew = !this.devices.has(d.id);
    this.devices.set(d.id, d);
    if (isNew) this.emitDevices();
  }

  async discover() {
    if (this.discovering) {
      this.emitDevices();
      return;
    }
    this.discovering = true;
    for (const id of [...this.devices.keys()]) if (id !== this.sessionDeviceId) this.devices.delete(id);
    try {
      await Promise.all([this.discoverChromecasts().catch(() => {}), this.discoverDlna().catch(() => {})]);
    } finally {
      this.discovering = false;
      this.emitDevices();
    }
  }

  discoverChromecasts() {
    const query = buildPtrQuery("_googlecast._tcp.local");
    const onMessage = (msg, rinfo) => {
      for (const svc of parseMdns(msg, "_googlecast._tcp.local")) {
        const host = svc.address || rinfo.address;
        const port = svc.port || 8009;
        this.addDevice({ id: `cc:${host}:${port}`, type: "chromecast", host, port, name: svc.friendlyName || svc.instance });
      }
    };
    const sockets = [];
    // Una consulta por cada tarjeta de red (Wi-Fi, Ethernet...), con respuesta directa
    for (const addr of ipv4Interfaces()) {
      const s = dgram.createSocket({ type: "udp4", reuseAddr: true });
      s.on("message", onMessage);
      s.on("error", () => s.close());
      s.bind(0, addr, () => {
        try {
          s.setMulticastInterface(addr);
        } catch (e) {}
        const send = () => s.send(query, 5353, "224.0.0.251", () => {});
        send();
        s._resend = setInterval(send, 1200);
      });
      sockets.push(s);
    }
    // Además se escucha el grupo mDNS por si la TV responde ahí
    const group = dgram.createSocket({ type: "udp4", reuseAddr: true });
    group.on("message", onMessage);
    group.on("error", () => group.close());
    group.bind(5353, () => {
      for (const addr of ipv4Interfaces()) {
        try {
          group.addMembership("224.0.0.251", addr);
        } catch (e) {}
      }
    });
    sockets.push(group);
    return new Promise((resolve) => setTimeout(() => {
      for (const s of sockets) {
        clearInterval(s._resend);
        try {
          s.close();
        } catch (e) {}
      }
      resolve();
    }, DISCOVERY_MS));
  }

  discoverDlna() {
    const search = (st) => Buffer.from(
      "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n");
    const queries = [search(AVTRANSPORT), search("urn:schemas-upnp-org:device:MediaRenderer:1")];
    const locations = new Set();
    const lookups = [];
    const sockets = [];
    for (const addr of ipv4Interfaces()) {
      const s = dgram.createSocket({ type: "udp4", reuseAddr: true });
      s.on("message", (msg) => {
        const m = /^LOCATION:\s*(\S+)/im.exec(msg.toString("utf8"));
        if (m && !locations.has(m[1])) {
          locations.add(m[1]);
          lookups.push(this.describeDlna(m[1]).catch(() => {}));
        }
      });
      s.on("error", () => s.close());
      s.bind(0, addr, () => {
        try {
          s.setMulticastInterface(addr);
        } catch (e) {}
        const send = () => queries.forEach((q) => s.send(q, 1900, "239.255.255.250", () => {}));
        send();
        s._resend = setInterval(send, 1500);
      });
      sockets.push(s);
    }
    return new Promise((resolve) => setTimeout(async () => {
      for (const s of sockets) {
        clearInterval(s._resend);
        try {
          s.close();
        } catch (e) {}
      }
      await Promise.all(lookups);
      resolve();
    }, DISCOVERY_MS));
  }

  async describeDlna(location) {
    const xml = await httpText(location, { timeout: 3000 });
    let controlPath = null;
    const re = /<(?:\w+:)?service>([\s\S]*?)<\/(?:\w+:)?service>/g;
    let m;
    while ((m = re.exec(xml))) {
      const type = xmlTag(m[1], "serviceType");
      if (type && type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) {
        controlPath = xmlTag(m[1], "controlURL");
        break;
      }
    }
    if (!controlPath) return;
    const base = xmlTag(xml, "URLBase") || location;
    const host = new URL(location).hostname;
    this.addDevice({
      id: "dlna:" + location,
      type: "dlna",
      host,
      controlUrl: new URL(controlPath, base).toString(),
      name: xmlTag(xml, "friendlyName") || "TV " + host
    });
  }

  async start(deviceId, mediaJson) {
    try {
      const device = this.devices.get(deviceId);
      if (!device) throw new Error("TV no encontrada");
      const media = JSON.parse(mediaJson);
      if (!this.session || this.sessionDeviceId !== deviceId) {
        this.closeSession();
        this.session = device.type === "chromecast" ? new ChromecastSession(this, device) : new DlnaSession(this, device);
        this.sessionDeviceId = deviceId;
      }
      const target = this.session;
      this.emitStatus(deviceId, "connecting");
      await target.load(media);
    } catch (e) {
      if (this.sessionDeviceId === deviceId) this.closeSession();
      this.emitStatus(deviceId, "error", -1, -1, "No se pudo transmitir a la TV: " + e.message);
    }
  }

  control(action, value) {
    const s = this.session;
    if (!s) return;
    if (action === "stop") {
      this.session = null;
      this.sessionDeviceId = null;
      s.close();
    } else {
      s.control(action, Number(value) || 0);
    }
  }

  closeSession() {
    if (this.session) this.session.close();
    this.session = null;
    this.sessionDeviceId = null;
  }

  fail(session, message) {
    if (this.session === session) {
      this.session = null;
      this.sessionDeviceId = null;
    }
    this.emitStatus(session.device.id, "error", -1, -1, message);
  }

  sessionEnded(session) {
    if (this.session === session) {
      this.session = null;
      this.sessionDeviceId = null;
    }
    this.emitStatus(session.device.id, "stopped");
  }

  release() {
    this.closeSession();
    this.proxy.stop();
  }
}

module.exports = { CastManager, parseMdns, buildPtrQuery, encodeCastMessage, decodeCastMessage, MediaProxy };
