// Descargas para ver sin conexión (igual que OfflineStore.java en Android):
// baja una calidad de hasta 720p con todos sus idiomas de audio, los segmentos y
// los subtítulos, y los sirve a la interfaz como mfoffline://local/<id>/...
const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const { Readable } = require("stream");
const { pipeline } = require("stream/promises");
const { net } = require("electron");

const UA_DESKTOP =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const OFFLINE_SCHEME = "mfoffline";
const MAX_HEIGHT = 720;
const WORKERS = 3;
const SAFE_NAME = /^[A-Za-z0-9._-]+$/;

class ExpiredError extends Error {}

function safeId(id) {
  return String(id).replace(/[^A-Za-z0-9_-]/g, "_") || "_";
}

function attr(line, name) {
  const m = new RegExp(`(?:^|[:,])${name}=("([^"]*)"|[^,]*)`).exec(line || "");
  if (!m) return null;
  return m[2] !== undefined ? m[2] : m[1];
}

async function fetchText(url) {
  const res = await net.fetch(url, { headers: { "User-Agent": UA_DESKTOP } });
  if ([403, 404, 410].includes(res.status)) throw new ExpiredError(`HTTP ${res.status}`);
  if (res.status >= 400) throw new Error(`HTTP ${res.status}`);
  return { text: await res.text(), url: res.url || url };
}

function describe(e) {
  const msg = String((e && e.message) || e);
  if (/ENOSPC/.test(msg)) return "Sin espacio en el disco";
  if (/ERR_INTERNET_DISCONNECTED|ERR_NAME_NOT_RESOLVED|ENOTFOUND|ECONNREFUSED|ETIMEDOUT/.test(msg)) return "Sin conexión";
  return msg.slice(0, 120);
}

class OfflineStore {
  constructor(root, { onUpdate, resolve }) {
    this.root = root;
    this.onUpdate = onUpdate;
    this.resolveStream = resolve;
    this.jobs = new Map();
    this.queue = Promise.resolve();
    this.lastNotify = 0;
    fs.mkdirSync(root, { recursive: true });
    this.loadJobs();
  }

  dir(job) {
    return path.join(this.root, safeId(job.id));
  }

  // -------------------------------------------------------------------------
  // API
  // -------------------------------------------------------------------------

  list() {
    return [...this.jobs.values()].map((job) => this.toJson(job));
  }

  start(metaJson) {
    const meta = JSON.parse(metaJson);
    const id = String(meta.id);
    const existing = this.jobs.get(id);
    if (existing) {
      if (existing.state !== "done") this.resume(id);
      return;
    }
    const job = { id, meta, state: "queued", done: 0, total: 0, bytes: 0, created: Date.now(), error: null, subtitles: [] };
    this.jobs.set(id, job);
    this.enqueue(job);
  }

  pause(id) {
    const job = this.jobs.get(String(id));
    if (!job || job.state === "done") return;
    job.cancelled = true;
    job.state = "paused";
    this.saveJob(job);
    this.notify(job, true);
  }

  resume(id) {
    const job = this.jobs.get(String(id));
    if (!job || ["done", "downloading", "queued"].includes(job.state)) return;
    job.error = null;
    this.enqueue(job);
  }

  delete(id) {
    const job = this.jobs.get(String(id));
    if (!job) return;
    job.cancelled = true;
    this.jobs.delete(job.id);
    fsp.rm(this.dir(job), { recursive: true, force: true }).catch(() => {});
    this.onUpdate({ id: job.id, state: "deleted" });
  }

  // Respuesta para mfoffline://local/<id>/<archivo>
  async serve(request) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const headers = { "Access-Control-Allow-Origin": "*" };
    if (parts.length !== 2 || !SAFE_NAME.test(parts[0]) || !SAFE_NAME.test(parts[1])) {
      return new Response("", { status: 404, headers });
    }
    const file = path.join(this.root, parts[0], parts[1]);
    try {
      const stat = await fsp.stat(file);
      const lower = parts[1].toLowerCase();
      headers["Content-Type"] = lower.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
        : lower.endsWith(".vtt") ? "text/vtt" : "application/octet-stream";
      headers["Content-Length"] = String(stat.size);
      return new Response(Readable.toWeb(fs.createReadStream(file)), { status: 200, headers });
    } catch (e) {
      return new Response("", { status: 404, headers });
    }
  }

  // -------------------------------------------------------------------------
  // Descarga (una a la vez, en orden)
  // -------------------------------------------------------------------------

  enqueue(job) {
    job.cancelled = false;
    job.state = "queued";
    this.saveJob(job);
    this.notify(job, true);
    const run = () => this.run(job);
    this.queue = this.queue.then(run, run);
  }

  async run(job) {
    if (job.cancelled || !this.jobs.has(job.id)) return;
    job.state = "downloading";
    this.notify(job, true);
    try {
      let tasks;
      let refreshed = false;
      try {
        tasks = await this.prepare(job, job.meta.m3u8, job.meta.subtitles);
      } catch (e) {
        if (!(e instanceof ExpiredError)) throw e;
        const fresh = await this.refreshStream(job);
        tasks = await this.prepare(job, fresh.m3u8, fresh.subtitles);
        refreshed = true;
      }
      try {
        await this.downloadAll(job, tasks);
      } catch (e) {
        if (!(e instanceof ExpiredError) || refreshed) throw e;
        const fresh = await this.refreshStream(job);
        await this.downloadAll(job, await this.prepare(job, fresh.m3u8, fresh.subtitles));
      }
      if (job.cancelled || !this.jobs.has(job.id)) return;
      job.state = "done";
      job.error = null;
      this.saveJob(job);
      this.notify(job, true);
    } catch (e) {
      if (job.cancelled || !this.jobs.has(job.id)) return;
      job.state = "error";
      job.error = describe(e);
      this.saveJob(job);
      this.notify(job, true);
    }
  }

  async refreshStream(job) {
    const fresh = this.resolveStream ? await this.resolveStream(job.id) : null;
    if (!fresh || !fresh.m3u8) throw new Error("El enlace del video caducó");
    job.meta.m3u8 = fresh.m3u8;
    if (fresh.subtitles) job.meta.subtitles = fresh.subtitles;
    this.saveJob(job);
    return fresh;
  }

  async prepare(job, masterUrl, subs) {
    const dir = this.dir(job);
    await fsp.mkdir(dir, { recursive: true });
    const tasks = [];
    const names = new Map();
    const master = await fetchText(masterUrl);

    if (master.text.includes("#EXT-X-STREAM-INF")) {
      const lines = master.text.split(/\r?\n/).map((l) => l.trim());
      let best = null;
      lines.forEach((line, i) => {
        if (!line.startsWith("#EXT-X-STREAM-INF:")) return;
        const uri = lines.slice(i + 1).find((t) => t && !t.startsWith("#"));
        if (!uri) return;
        const res = attr(line, "RESOLUTION");
        const height = res && res.includes("x") ? parseInt(res.split("x")[1], 10) || 0 : 0;
        const bandwidth = parseInt(attr(line, "BANDWIDTH") || "0", 10) || 0;
        // Hasta 720p la mejor; si todas son mayores, la más baja
        const score = height > 0
          ? (height <= MAX_HEIGHT ? height : -height)
          : (bandwidth <= 3000000 ? bandwidth / 1000 : -bandwidth / 1000);
        if (!best || score > best.score) best = { line, uri, score };
      });
      if (!best) throw new Error("Lista de video vacía");
      const group = attr(best.line, "AUDIO");
      let local = "#EXTM3U\n";
      let audioIndex = 0;
      for (let line of lines) {
        if (!line.startsWith("#EXT-X-MEDIA:") || !line.includes("TYPE=AUDIO")) continue;
        if (!group || attr(line, "GROUP-ID") !== group) continue;
        const uri = attr(line, "URI");
        if (uri) {
          const name = "a" + audioIndex++;
          await this.mediaPlaylist(new URL(uri, master.url).toString(), name, dir, tasks, names);
          line = line.replace(`URI="${uri}"`, `URI="${name}.m3u8"`);
        }
        local += line + "\n";
      }
      const variant = best.line
        .replace(/,?(SUBTITLES|CLOSED-CAPTIONS)=("[^"]*"|NONE)/g, "")
        .replace("#EXT-X-STREAM-INF:,", "#EXT-X-STREAM-INF:");
      local += variant + "\nv.m3u8\n";
      await this.mediaPlaylist(new URL(best.uri, master.url).toString(), "v", dir, tasks, names);
      await fsp.writeFile(path.join(dir, "master.m3u8"), local);
    } else {
      await this.writeMediaPlaylist(master.text, master.url, "v", dir, tasks, names);
      await fsp.writeFile(path.join(dir, "master.m3u8"), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nv.m3u8\n");
    }

    job.subtitles = [];
    (subs || []).forEach((sub, i) => {
      if (!sub || !sub.file) return;
      const name = `sub${i}.vtt`;
      tasks.push({ url: sub.file, file: name });
      job.subtitles.push({ file: `${OFFLINE_SCHEME}://local/${safeId(job.id)}/${name}`, label: sub.label || "Español" });
    });
    job.total = tasks.length;
    return tasks;
  }

  async mediaPlaylist(url, prefix, dir, tasks, names) {
    const res = await fetchText(url);
    await this.writeMediaPlaylist(res.text, res.url, prefix, dir, tasks, names);
  }

  async writeMediaPlaylist(text, baseUrl, prefix, dir, tasks, names) {
    const counter = { n: 0 };
    const out = [];
    for (const raw of text.split(/\r?\n/)) {
      const t = raw.trim();
      if (!t) continue;
      if (t.startsWith("#")) {
        out.push(t.replace(/URI="([^"]+)"/g, (_m, uri) =>
          `URI="${this.localName(new URL(uri, baseUrl).toString(), prefix, counter, tasks, names)}"`));
      } else {
        out.push(this.localName(new URL(t, baseUrl).toString(), prefix, counter, tasks, names));
      }
    }
    await fsp.writeFile(path.join(dir, prefix + ".m3u8"), out.join("\n") + "\n");
  }

  // El mismo archivo (ej. con EXT-X-BYTERANGE) se baja una sola vez
  localName(url, prefix, counter, tasks, names) {
    if (names.has(url)) return names.get(url);
    let ext = ".ts";
    try {
      const m = /\.([a-z0-9]{1,5})$/i.exec(new URL(url).pathname);
      if (m) ext = "." + m[1].toLowerCase();
    } catch (e) {}
    const name = `${prefix}_${String(counter.n++).padStart(5, "0")}${ext}`;
    names.set(url, name);
    tasks.push({ url, file: name });
    return name;
  }

  async downloadAll(job, tasks) {
    const dir = this.dir(job);
    let done = 0;
    let bytes = 0;
    const pending = [];
    for (const task of tasks) {
      try {
        const stat = await fsp.stat(path.join(dir, task.file));
        done++;
        bytes += stat.size;
      } catch (e) {
        pending.push(task);
      }
    }
    job.done = done;
    job.bytes = bytes;
    this.notify(job, true);

    let failure = null;
    const worker = async () => {
      while (pending.length && !job.cancelled && !failure) {
        const task = pending.shift();
        try {
          const size = await this.downloadFile(task.url, path.join(dir, task.file), job);
          job.done++;
          job.bytes += size;
          this.notify(job, false);
        } catch (e) {
          if (!failure) failure = e;
        }
      }
    };
    await Promise.all(Array.from({ length: WORKERS }, worker));
    if (job.cancelled) return;
    if (failure) throw failure;
    this.saveJob(job);
  }

  async downloadFile(url, target, job) {
    let last = null;
    for (let attempt = 0; attempt < 3; attempt++) {
      if (job.cancelled) throw new Error("Cancelado");
      const part = target + ".part";
      try {
        const res = await net.fetch(url, { headers: { "User-Agent": UA_DESKTOP } });
        if ([403, 404, 410].includes(res.status)) throw new ExpiredError(`HTTP ${res.status}`);
        if (res.status >= 400 || !res.body) throw new Error(`HTTP ${res.status}`);
        await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(part));
        await fsp.rename(part, target);
        return (await fsp.stat(target)).size;
      } catch (e) {
        if (e instanceof ExpiredError) throw e;
        last = e;
        await fsp.rm(part, { force: true }).catch(() => {});
        await new Promise((r) => setTimeout(r, 1000 * (attempt + 1)));
      }
    }
    throw last;
  }

  // -------------------------------------------------------------------------
  // Estado en disco
  // -------------------------------------------------------------------------

  loadJobs() {
    let dirs = [];
    try {
      dirs = fs.readdirSync(this.root);
    } catch (e) {
      return;
    }
    const loaded = [];
    for (const d of dirs) {
      try {
        const o = JSON.parse(fs.readFileSync(path.join(this.root, d, "job.json"), "utf8"));
        // Lo que quedó a medias al cerrar la app espera a que el usuario lo reanude
        if (o.state === "downloading" || o.state === "queued") o.state = "paused";
        loaded.push(Object.assign({ subtitles: [], error: null }, o));
      } catch (e) {}
    }
    loaded.sort((a, b) => (a.created || 0) - (b.created || 0)).forEach((job) => this.jobs.set(job.id, job));
  }

  saveJob(job) {
    if (!this.jobs.has(job.id)) return;
    const data = {
      id: job.id, meta: job.meta, state: job.state, done: job.done, total: job.total,
      bytes: job.bytes, created: job.created, error: job.error, subtitles: job.subtitles
    };
    try {
      fs.mkdirSync(this.dir(job), { recursive: true });
      const file = path.join(this.dir(job), "job.json");
      fs.writeFileSync(file + ".tmp", JSON.stringify(data));
      fs.renameSync(file + ".tmp", file);
    } catch (e) {}
  }

  toJson(job) {
    const o = {
      id: job.id,
      state: job.state,
      progress: job.state === "done" ? 1 : (job.total > 0 ? job.done / job.total : 0),
      bytes: job.bytes,
      created: job.created,
      meta: job.meta
    };
    if (job.error) o.error = job.error;
    if (job.state === "done") {
      o.playUrl = `${OFFLINE_SCHEME}://local/${safeId(job.id)}/master.m3u8`;
      o.subtitles = job.subtitles;
    }
    return o;
  }

  notify(job, force) {
    const now = Date.now();
    if (!force && now - this.lastNotify < 500) return;
    this.lastNotify = now;
    this.onUpdate(this.toJson(job));
  }
}

module.exports = { OfflineStore, OFFLINE_SCHEME };
