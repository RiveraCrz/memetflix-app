// Expone el mismo puente `MemetflixNative` que usa la app de Android.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("MemetflixNative", {
  httpGet: (url, token) => ipcRenderer.sendSync("native:httpGet", url, token),
  httpPost: (url, body, token) => ipcRenderer.sendSync("native:httpPost", url, body, token),
  resolveVideoStream: (postId) => ipcRenderer.sendSync("native:resolveVideoStream", postId)
});
