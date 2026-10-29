# 🎬 Memetflix (Android TV, Tablet & Mobile)

Aplicación nativa ligera para **Android TV / Google TV / Fire TV**, **Tablets** y **Móviles**, diseñada con su propia interfaz responsiva estilo **Netflix** (negro y rojo) y extractor directo de streams HLS (`.m3u8`) libre de anuncios y ventanas emergentes.

## ✨ Características Principales

- **Interfaz Propia Responsiva (`memetflix.html`)**:
  - Diseño oscuro (`#070709`) con identidad visual **Memetflix** en rojo (`#E50914`).
  - Secciones dedicadas para **Inicio**, **Películas**, **Series**, **Anime**, **Buscador en tiempo real** e **Inicio de Sesión**.
  - Soporte completo para navegación con **Control Remoto de TV (D-Pad)**, puntero virtual opcional y pantalla táctil en móviles/tablets.
- **Extractor Nativo de Video sin Publicidad (`MemetflixBridge`)**:
  - Consume directamente los endpoints JSON del catálogo y reproductor.
  - Desencripta en Java los bloques `eval(function(p,a,c,k,e,d)...)` del servidor de video para extraer la URL directa del manifiesto HLS (`master.m3u8`) y los subtítulos (`.vtt`).
  - Reproduce el flujo directamente con `hls.js` sin cargar anuncios ni bloqueos anti-AdBlock.
- **Selector de Audio, Subtítulos y Calidad en Vivo**:
  - Cambio instantáneo entre pistas de audio (**Español Latino**, **Inglés**, **Japonés**, etc.), subtítulos en español y resoluciones HD.
- **Soporte Oficial para Android TV (`LEANBACK_LAUNCHER`)**:
  - Incluye banner oficial `320x180dp` (`tv_banner.png`) e íconos adaptativos para aparecer directamente en el launcher principal del televisor.

## 📦 Instalación Rápida

El archivo compilado y firmado listo para instalar se encuentra en la raíz del repositorio:
- **[`Memetflix.apk`](./Memetflix.apk)**

## 💻 Windows

También hay versión de escritorio para Windows en [`desktop/`](./desktop/README.md) (instalador `.exe` con acceso directo en el escritorio).

## 🛠️ Estructura del Proyecto

- `app/src/main/java/com/memetflix/tv/MainActivity.java`: Actividad principal en modo pantalla completa inmersiva, puente nativo `@JavascriptInterface` (`MemetflixBridge`), desempacador de streams HLS y cursor virtual para TV.
- `app/src/main/assets/memetflix.html`: Interfaz de usuario responsiva de Memetflix y reproductor HLS personalizado.
- `desktop/`: App de escritorio (Electron) que reutiliza `memetflix.html` y reimplementa el puente nativo en `main.js`.
- `app/src/main/AndroidManifest.xml`: Configuración de permisos y `intent-filter` para `LAUNCHER` (móvil/tablet) y `LEANBACK_LAUNCHER` (Android TV).
