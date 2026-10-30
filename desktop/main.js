// Memetflix para escritorio: reutiliza la interfaz de Android (memetflix.html)
// y reemplaza el puente Java `MemetflixNative` con el proceso principal de Electron.
const { app, BrowserWindow, ipcMain, net, session, Menu, protocol } = require("electron");
const path = require("path");
const { CastManager } = require("./cast");
const { OfflineStore, OFFLINE_SCHEME } = require("./downloads");

// Lo descargado se sirve como mfoffline://local/<id>/... para que hls.js lo lea con fetch
protocol.registerSchemesAsPrivileged([
  { scheme: OFFLINE_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true } }
]);

const WEB_HOME_URL = "https://lamovie.la/";
const UA_DESKTOP =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

// Mismas listas que MainActivity.java
const AD_DOMAINS = [
  "agl0", "agl001.", "agl002.", "agl003.", "agl004.", "agl005.", "adangle",
  "doubleclick.net", "googlesyndication.com", "googleadservices.com",
  "googletagmanager.com", "google-analytics.com", "adservice.google.",
  "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
  "exoclick.com", "juicyads.com", "onclickads.net", "hilltopads.net",
  "clickadu.com", "adcash.com", "trafficjunky.net", "ero-advertising.com",
  "tsyndicate.com", "realsrv.com", "histats.com", "acscdn.com",
  "highperformanceformat", "alwingulla.com", "pushame.com", "monetag.",
  "outbrain.com", "taboola.com", "mgid.com", "revcontent.com",
  "adskeeper.com", "admaven.com", "richads.com", "evadav.com",
  "pushhouse.", "galaksion.com", "rollerads.com", "kadam.net",
  "bidgear.com", "pubfuture.com", "criteo.com", "criteo.net",
  "rubiconproject.com", "pubmatic.com", "openx.net", "adnxs.com",
  "smartadserver.com", "teads.tv", "magsrv.com", "sb-cd.com",
  "bet365.", "1xbet.", "caliente.mx", "codere.", "bwin.", "melbet.",
  "mostbet.", "pin-up.", "1win.", "parimatch.", "betway.", "stake.com",
  "casino", "kazino", "plinko", "megamoolah", "chicken-road", "chickenroad"
];

const AD_PATH_PATTERNS = [
  "/o/s/", "/b/data", "/pop.js", "/popunder", "/pophit", "/pop-under",
  "/ads.js", "/ad.js", "/adscript", "/adserver", "/adframe", "/adbanner",
  "/vast.xml", "/vmap.xml", "/preroll", "/ima3.js", "adangle"
];

const MEDIA_EXTENSIONS = [".m3u8", ".ts", ".mp4", ".vtt", ".srt", ".webm", ".mkv", ".m4s", ".aac"];

function isAdUrl(urlStr) {
  let url;
  try {
    url = new URL(urlStr);
  } catch (e) {
    return false;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return false;
  const host = url.hostname.toLowerCase();
  const full = urlStr.toLowerCase();
  const pathname = url.pathname.toLowerCase();
  if (MEDIA_EXTENSIONS.some((ext) => pathname.endsWith(ext)) || full.includes("/hls")) return false;
  if (AD_DOMAINS.some((ad) => host.includes(ad))) return true;
  return AD_PATH_PATTERNS.some((p) => full.includes(p));
}

function webRoot() {
  return app.isPackaged
    ? path.join(process.resourcesPath, "web")
    : path.join(__dirname, "..", "app", "src", "main", "assets");
}

async function performHttpRequest(urlStr, method, body, bearer) {
  try {
    const headers = {
      "User-Agent": UA_DESKTOP,
      "Accept": "application/json, text/html, */*",
      "Referer": WEB_HOME_URL
    };
    if (bearer && bearer.trim()) headers["Authorization"] = "Bearer " + bearer.trim();
    const init = { method, headers, signal: AbortSignal.timeout(12000) };
    if (method === "POST" && body != null) {
      headers["Content-Type"] = "application/json; charset=UTF-8";
      init.body = body;
    }
    const res = await net.fetch(urlStr, init);
    return await res.text();
  } catch (e) {
    return JSON.stringify({ error: true, message: String(e && e.message) });
  }
}

// Desempaqueta bloques ofuscados con Dean Edwards Packer (igual que en Android)
function unpackDeanEdwards(html) {
  if (!html) return "";
  try {
    const m = /eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)\)\)/.exec(html);
    if (!m) return "";
    const p = m[1];
    const a = parseInt(m[2], 10);
    const c = parseInt(m[3], 10);
    const k = m[4].split("|");
    const dict = new Map();
    for (let i = 0; i < c; i++) {
      if (i < k.length && k[i]) dict.set(toBase(i, a), k[i]);
    }
    return p.replace(/\b[0-9a-zA-Z]+\b/g, (word) => (dict.has(word) ? dict.get(word) : word));
  } catch (e) {
    return "";
  }
}

function toBase(num, radix) {
  const chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  if (num === 0) return "0";
  let out = "";
  let n = num;
  while (n > 0) {
    out = chars.charAt(n % radix) + out;
    n = Math.floor(n / radix);
  }
  return out;
}

function extractFirstMatch(text, regex) {
  const m = regex.exec(text || "");
  return m ? m[0] : null;
}

async function resolveVideoStream(postId) {
  try {
    const playerApiUrl = "https://lamovie.la/wp-api/v1/player?postId=" + encodeURIComponent(postId) + "&demo=0";
    const root = JSON.parse(await performHttpRequest(playerApiUrl, "GET", null, null));
    const embeds = root && root.data && root.data.embeds;
    if (!Array.isArray(embeds) || embeds.length === 0) return JSON.stringify({ ok: false });

    let selectedEmbedUrl = null;
    for (const item of embeds) {
      const u = (item && item.url) || "";
      if (u.includes("vimeos")) {
        selectedEmbedUrl = u;
        break;
      }
      if (!selectedEmbedUrl && u) selectedEmbedUrl = u;
    }
    if (!selectedEmbedUrl) return JSON.stringify({ ok: false });

    const embedHtml = await performHttpRequest(selectedEmbedUrl, "GET", null, null);
    const unpacked = unpackDeanEdwards(embedHtml);
    const searchSpace = unpacked ? unpacked + "\n" + embedHtml : embedHtml;

    const m3u8Url = extractFirstMatch(searchSpace, /https:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*/);
    const posterUrl = extractFirstMatch(searchSpace, /https:\/\/[^"'\s<>]+\.(?:jpg|webp|png)/);
    const subtitles = (searchSpace.match(/https:\/\/[^"'\s<>]+\.vtt/g) || []).map((file) => ({ file, label: "Español" }));

    return JSON.stringify({
      ok: true,
      m3u8: m3u8Url || "",
      poster: posterUrl || "",
      embedUrl: selectedEmbedUrl,
      subtitles
    });
  } catch (e) {
    return JSON.stringify({ ok: false });
  }
}

let castManager = null;
let offlineStore = null;

// Avisos para la interfaz: window.__onCastDevices(lista) / window.__onCastStatus(estado)
function sendToUi(fn, data) {
  for (const win of BrowserWindow.getAllWindows()) {
    win.webContents
      .executeJavaScript(`window.${fn} && window.${fn}(${JSON.stringify(data)})`)
      .catch(() => {});
  }
}

// El HTML llama al puente de forma síncrona (como @JavascriptInterface en Android),
// así que respondemos por sendSync rellenando event.returnValue.
function registerBridge() {
  ipcMain.on("native:httpGet", async (event, url, token) => {
    event.returnValue = await performHttpRequest(String(url), "GET", null, token);
  });
  ipcMain.on("native:httpPost", async (event, url, body, token) => {
    event.returnValue = await performHttpRequest(String(url), "POST", body, token);
  });
  ipcMain.on("native:resolveVideoStream", async (event, postId) => {
    event.returnValue = await resolveVideoStream(String(postId));
  });

  // Transmitir a la TV (asíncrono: las respuestas llegan por sendToUi)
  castManager = new CastManager({
    onDevices: (list) => sendToUi("__onCastDevices", list),
    onStatus: (status) => sendToUi("__onCastStatus", status)
  });
  ipcMain.on("native:castDiscover", () => {
    castManager.discover();
  });
  ipcMain.on("native:castStart", (_event, deviceId, mediaJson) => {
    castManager.start(String(deviceId), String(mediaJson));
  });
  ipcMain.on("native:castControl", (_event, action, value) => {
    castManager.control(String(action), Number(value) || 0);
  });

  // Descargas para ver sin conexión (el avance llega por __onDownloadUpdate)
  offlineStore = new OfflineStore(path.join(app.getPath("userData"), "offline"), {
    onUpdate: (item) => sendToUi("__onDownloadUpdate", item),
    resolve: async (postId) => {
      try {
        const data = JSON.parse(await resolveVideoStream(String(postId)));
        return data && data.m3u8 ? data : null;
      } catch (e) {
        return null;
      }
    }
  });
  protocol.handle(OFFLINE_SCHEME, (request) => offlineStore.serve(request));
  ipcMain.on("native:downloadList", (event) => {
    event.returnValue = JSON.stringify(offlineStore.list());
  });
  ipcMain.on("native:downloadStart", (event, metaJson) => {
    try {
      offlineStore.start(String(metaJson));
      event.returnValue = "ok";
    } catch (e) {
      event.returnValue = "fail";
    }
  });
  ipcMain.on("native:downloadPause", (_event, id) => offlineStore.pause(String(id)));
  ipcMain.on("native:downloadResume", (_event, id) => offlineStore.resume(String(id)));
  ipcMain.on("native:downloadDelete", (_event, id) => offlineStore.delete(String(id)));
}

function configureSession() {
  const ses = session.defaultSession;
  ses.setUserAgent(UA_DESKTOP);

  ses.webRequest.onBeforeRequest((details, callback) => {
    callback({ cancel: isAdUrl(details.url) });
  });

  // La interfaz se carga desde file://, así que hls.js necesita CORS abierto para
  // descargar el .m3u8 y los segmentos (en Android lo cubre AllowUniversalAccessFromFileURLs).
  // Solo se tocan las peticiones con Origin "null" (las que hace la página file://).
  const localUiRequests = new Set();
  ses.webRequest.onBeforeSendHeaders((details, callback) => {
    const origin = Object.entries(details.requestHeaders).find(([k]) => k.toLowerCase() === "origin");
    if (origin && origin[1] === "null") localUiRequests.add(details.id);
    callback({ requestHeaders: details.requestHeaders });
  });
  const forget = (details) => localUiRequests.delete(details.id);
  ses.webRequest.onCompleted(forget);
  ses.webRequest.onErrorOccurred(forget);

  ses.webRequest.onHeadersReceived((details, callback) => {
    if (!localUiRequests.has(details.id)) {
      callback({});
      return;
    }
    const headers = {};
    for (const [key, value] of Object.entries(details.responseHeaders || {})) {
      if (!key.toLowerCase().startsWith("access-control-")) headers[key] = value;
    }
    headers["Access-Control-Allow-Origin"] = ["*"];
    headers["Access-Control-Allow-Headers"] = ["*"];
    callback({ responseHeaders: headers });
  });
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 800,
    minHeight: 500,
    backgroundColor: "#070709",
    title: "Memetflix",
    icon: path.join(__dirname, "build", "icon.png"),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  // Solo se navega dentro de la interfaz local; nada de ventanas emergentes.
  win.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  win.webContents.on("will-navigate", (event, url) => {
    if (!url.startsWith("file:")) event.preventDefault();
  });

  // Esc / Retroceso del ratón actúan como el botón Atrás de Android.
  win.webContents.on("before-input-event", (event, input) => {
    if (input.type !== "keyDown") return;
    if (input.key === "F11") {
      win.setFullScreen(!win.isFullScreen());
      event.preventDefault();
    } else if (input.key === "Escape" || input.key === "BrowserBack") {
      if (win.isFullScreen()) return;
      win.webContents.executeJavaScript("window.__handleAndroidBack && window.__handleAndroidBack()").catch(() => {});
    }
  });
  win.on("app-command", (_e, cmd) => {
    if (cmd === "browser-backward") {
      win.webContents.executeJavaScript("window.__handleAndroidBack && window.__handleAndroidBack()").catch(() => {});
    }
  });

  win.loadFile(path.join(webRoot(), "memetflix.html"));
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    const [win] = BrowserWindow.getAllWindows();
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    registerBridge();
    configureSession();
    createWindow();
    app.on("activate", () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on("before-quit", () => {
    if (castManager) castManager.release();
  });

  app.on("window-all-closed", () => {
    if (process.platform !== "darwin") app.quit();
  });
}
