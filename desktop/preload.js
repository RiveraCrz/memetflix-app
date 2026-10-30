// Expone el mismo puente `MemetflixNative` que usa la app de Android.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("MemetflixNative", {
  httpGet: (url, token) => ipcRenderer.sendSync("native:httpGet", url, token),
  httpPost: (url, body, token) => ipcRenderer.sendSync("native:httpPost", url, body, token),
  resolveVideoStream: (postId) => ipcRenderer.sendSync("native:resolveVideoStream", postId),
  platform: "desktop",
  castDiscover: () => ipcRenderer.send("native:castDiscover"),
  castStart: (deviceId, mediaJson) => ipcRenderer.send("native:castStart", deviceId, mediaJson),
  castControl: (action, value) => ipcRenderer.send("native:castControl", action, value),
  downloadStart: (metaJson) => ipcRenderer.sendSync("native:downloadStart", metaJson),
  downloadList: () => ipcRenderer.sendSync("native:downloadList"),
  downloadPause: (id) => ipcRenderer.send("native:downloadPause", id),
  downloadResume: (id) => ipcRenderer.send("native:downloadResume", id),
  downloadDelete: (id) => ipcRenderer.send("native:downloadDelete", id)
});
