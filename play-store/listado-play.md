# 🚀 Kit de publicación — Play Console (Lumen / IPTV Player PRO)

Paquete: `com.samuelpart.iptvplayer` · versionCode **13** · versionName **3.0** · targetSdk 35
AAB firmado: `~/iptv/app/release/app-release.aab` (95 MB) ✅

> ⚠️ Cada futura actualización debe subir `versionCode` (13 → 14 → 15…) en
> `app/build.gradle.kts`, y firmarse SIEMPRE con el mismo keystore.

---

## 1. Título de la app (máx. 30 caracteres) — 27 ✓

```
Lumen: Player IPTV y Listas
```

## 2. Descripción corta (máx. 80 caracteres) — 73 ✓

```
Reproductor IPTV elegante: tus listas M3U, TV en vivo, películas y series.
```

## 3. Descripción completa (máx. 4000 caracteres)

```
Convierte tus listas M3U en una experiencia de televisión elegante.

Lumen es un reproductor IPTV rápido y sencillo, con un diseño moderno de
vidrio oscuro, pensado para ver tu TV en vivo, tus películas y tus series
sin complicaciones.

📺 TV EN VIVO
• Reproduce canales de tus listas M3U locales o remotas
• Navegación clara: Inicio, Canales, Buscar, Cine y Ajustes
• Cambia de canal y de servidor con un toque

🎬 PELÍCULAS Y SERIES
• Fichas con póster, año y detalles de cada título
• Catálogo de cine navegable y ordenado
• Marca tus favoritos para tenerlos siempre a mano

🔍 BÚSQUEDA INSTANTÁNEA
• Encuentra cualquier canal, película o serie al instante

🧲 COMPARTE TUS HALLAZGOS
• Crea tarjetas con póster y código QR para recomendar títulos
• Quien recibe la recomendación abre la app directo en ese título

⚙️ POTENTE Y COMPATIBLE
• Doble motor de reproducción (Media3/ExoPlayer y LibVLC)
• Compatibilidad amplia de formatos: HLS, DASH, RTSP y más
• Envía a tu Chromecast con un toque
• Colores de acento personalizables y modo oscuro elegante

📌 IMPORTANTE
Lumen es un REPRODUCTOR. No producimos ni suministramos contenido
audiovisual: la app reproduce las listas M3U que cada usuario posea o
tenga derecho a usar. Respeta los derechos de autor del contenido que
reproduzcas.

📲 Descarga Lumen y dale a tu pantalla el reproductor que se merece.

Contiene anuncios.
```

> 💡 No menciones en el listado nombres de películas/canales con copyright:
> mientras más "reproductor neutral" se vea la ficha, mejor pasa la revisión.

---

## 4. Gráficos listos en esta carpeta

| Archivo | Uso | Tamaño |
|---|---|---|
| `feature_graphic.png` | Gráfico de funciones (obligatorio) | 1024×500 ✓ |
| `icon_512.png` | Ícono de la ficha de Play | 512×512 ✓ |
| Capturas de pantalla | **Tómalas de tu teléfono** (mín. 2): Inicio, Canales, ficha de peli, reproductor | 1080×1920 aprox. |

## 5. Formulario "Seguridad de datos" (respuestas)

| Pregunta | Respuesta |
|---|---|
| ¿Recopila o comparte datos de usuario? | **Sí** |
| Tipo | Identificadores de dispositivo u otros (ID de publicidad) |
| ¿Recopilado? / ¿Compartido? | Sí / Sí (por Google AdMob, para publicidad) |
| Finalidad | Publicidad |
| ¿Transmisión cifrada? | **Sí** |
| ¿Botón de eliminación de datos? | Sí — por solicitud al correo de contacto (la app no tiene servidor propio ni cuentas) |
| ¿Es una app dirigida a niños? | **No** |

## 6. Clasificación de contenido (IARC) — respuestas rápidas

Violencia: No · Sexual: No · Lenguaje ofensivo: No · Drogas: No ·
Apuestas: No · Terror: No · Temas sensibles: No.
(El cuestionario de "anuncios" responde que los anuncios están presentes pero sin contenido para adultos.)

## 7. Categoría y anuncios

- Categoría: **Reproductores y editores de video**
- ¿Contiene anuncios? **Sí** (AdMob)

## 8. Pasos finales en Play Console

1. Crear app → nombre del listado (sección 1) → type: Aplicación / gratis
2. Subir el AAB primero a **Prueba cerrada** (si tu cuenta es personal y nueva: 20 testers × 14 días antes de producción)
3. Completar: Política de privacidad (URL del archivo `politica-privacidad.html` publicado), Seguridad de datos (sección 5), Clasificación (sección 6), Público objetivo, Anuncios (sección 7)
4. Subir gráficos: `feature_graphic.png`, `icon_512.png` + tus capturas
5. Enviar a revisión 📤

## 9. Dónde publicar la política de privacidad (gratis)

- **Google Sites** (sites.google.com) — pega el texto del HTML, publicas y copias el enlace
- **Neocities** o **GitHub Pages** — sube el `politica-privacidad.html` tal cual

⚠️ Antes de publicar el HTML: reemplaza `[TU_CORREO@ejemplo.com]` por tu correo real.
