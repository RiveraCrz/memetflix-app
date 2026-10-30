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
- **Continuar viendo**:
  - Guarda el minuto de cada película o capítulo y, al volver a abrirlo, pregunta si continuar o empezar desde el inicio. En Inicio aparece la fila **Continuar viendo** y en el menú la sección **Seguir viendo** con todo lo que quedó a medias (se puede quitar cada título).
- **Siguiente capítulo**:
  - En series y anime, durante los créditos sugiere el siguiente capítulo (pasa también a la siguiente temporada) y lo reproduce solo al terminar.
- **Transmitir a la TV**:
  - Botón **📺 Transmitir** en el reproductor: envía el video a **Chromecast / Google TV** (protocolo Cast) o a **Smart TV DLNA** (Samsung, LG, Sony...) en la misma red Wi-Fi, sin librerías externas (`CastManager.java`). En el celular también ofrece **Duplicar la pantalla**.
- **Reproductor estilo Netflix**:
  - Pausa, retroceder y avanzar 10 s sobre el video (también doble toque a los lados); audio, subtítulos, calidad, pantalla y descarga agrupados en el panel de **Opciones**.
  - Gira solo según cómo se sostenga el celular, o se fija en horizontal o vertical desde Opciones › Pantalla.
- **Descargas para ver sin conexión**:
  - Botón ⬇ en películas, capítulos y el reproductor. Baja una calidad de hasta 720p con todos sus idiomas y subtítulos (`OfflineStore.java`, con notificación de avance en `DownloadService.java`). Sin internet la app abre directo en **Descargas**.
- **Soporte Oficial para Android TV (`LEANBACK_LAUNCHER`)**:
  - Incluye banner oficial `320x180dp` (`tv_banner.png`) e íconos adaptativos para aparecer directamente en el launcher principal del televisor.

## 📦 Instalación Rápida

El archivo compilado y firmado listo para instalar se encuentra en la raíz del repositorio:
- **[`Memetflix.apk`](./Memetflix.apk)**

## 💻 Windows

También hay versión de escritorio para Windows en [`desktop/`](./desktop/README.md) (instalador `.exe` con acceso directo en el escritorio).

## 🛠️ Estructura del Proyecto

- `app/src/main/java/com/memetflix/tv/MainActivity.java`: Actividad principal en modo pantalla completa inmersiva, puente nativo `@JavascriptInterface` (`MemetflixBridge`), desempacador de streams HLS y cursor virtual para TV.
- `app/src/main/java/com/memetflix/tv/CastManager.java`: Búsqueda de TVs (mDNS / SSDP), sesiones Chromecast y DLNA, y proxy HTTP local que le sirve el video a la TV.
- `app/src/main/java/com/memetflix/tv/OfflineStore.java` y `DownloadService.java`: Descargas para ver sin conexión.
- `app/src/main/assets/memetflix.html`: Interfaz de usuario responsiva de Memetflix y reproductor HLS personalizado.
- `desktop/`: App de escritorio (Electron) que reutiliza `memetflix.html` y reimplementa el puente nativo en `main.js`.
- `app/src/main/AndroidManifest.xml`: Configuración de permisos y `intent-filter` para `LAUNCHER` (móvil/tablet) y `LEANBACK_LAUNCHER` (Android TV).
