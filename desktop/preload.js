// Expone el mismo puente `MemetflixNative` que usa la app de Android.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("MemetflixNative", {
  httpGet: (url, token) => ipcRenderer.sendSync("native:httpGet", url, token),
  httpPost: (url, body, token) => ipcRenderer.sendSync("native:httpPost", url, body, token),
  resolveVideoStream: (postId) => ipcRenderer.sendSync("native:resolveVideoStream", postId),
  platform: "desktop",
  castDiscover: () => ipcRenderer.send("native:castDiscover"),
  castStart: (deviceId, mediaJson) => ipcRenderer.send("native:castStart", deviceId, mediaJson),
  castControl: (action, value) => ipcRenderer.send("native:castControl", action, value)
});
