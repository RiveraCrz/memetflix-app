# Memetflix para Windows

App de escritorio (Electron) que reutiliza la misma interfaz de Android
(`app/src/main/assets/memetflix.html`). El puente `MemetflixNative` que en
Android vive en `MainActivity.java` aquí está en `main.js` (peticiones a la API,
extracción del `.m3u8` y bloqueo de anuncios).

## Probar en modo desarrollo
```
cd desktop
npm install
npm start
```

## Generar el instalador
En Windows:
```
cd desktop
npm install
npm run dist:win
```
Quedan en `desktop/dist/`:
- `Memetflix-Setup-<versión>.exe`: instalador (crea acceso directo en el escritorio y el menú Inicio).
- `Memetflix-Portable-<versión>.exe`: se abre sin instalar.

Desde Linux también funciona si está instalado `wine` (64 y 32 bits).

Sube `version` en `package.json` en cada versión nueva.

## Transmitir a la TV
El botón **📺 Transmitir** del reproductor busca Chromecast / Google TV y Smart TV
(DLNA: Samsung, LG, Sony...) en la misma red. La TV pide el video a esta PC
(`cast.js`), así que la primera vez Windows pregunta si Memetflix puede usar la
red: marca **Redes privadas** y pulsa **Permitir**. Si no aparece ninguna TV,
también se puede duplicar la pantalla con `Win + K`.

## Descargas
El botón ⬇ Descargar guarda la película o capítulo en
`%APPDATA%\Memetflix\offline` (`downloads.js`) para verlo sin internet desde
la pestaña **Descargas**.

## Teclas
- `Esc` o el botón Atrás del ratón: igual que el botón Atrás de Android.
- `F11`: pantalla completa.

## Aviso de Windows
El instalador no está firmado con certificado, así que Windows SmartScreen puede
mostrar "Windows protegió su PC". Pulsa **Más información > Ejecutar de todas formas**.
