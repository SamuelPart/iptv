package com.samuelpart.iptvplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * TARJETA de contenido compartible (diseno editorial horizontal 1200x675):
 *
 *   ┌────────────┬──────────────────────────────┐
 *   │            │ ◤ LUMEN                      │
 *   │  PÓSTER    │                              │
 *   │  (altura   │  TITULO grande (3 lineas)    │
 *   │  completa) │  ━━ (acento)                 │
 *   │            │  año · género                │
 *   │            │                ┌────┐        │
 *   │            │  Escanea y     │ QR │        │
 *   │            │  ábrelo en     └────┘        │
 *   │            │  Lumen                       │
 *   └────────────┴──────────────────────────────┘
 *
 * Todo ordenado con margenes consistentes: poster a sangre izquierda,
 * panel oscuro a la derecha con jerarquia titulo > acento > meta > QR.
 */
object ContentCardShare {

    private const val W = 1200
    private const val H = 675
    private const val POSTER_W = 440          // columna izquierda del poster
    private const val PAD = 56                // margen derecho del panel
    private const val TEXT_X = 496            // inicio del texto
    private const val TEXT_RIGHT = 1140       // fin del texto

    /** Genera el PNG de la tarjeta y devuelve su uri content:// lista para compartir. */
    suspend fun buildCardUri(
        context: Context,
        title: String,
        metaLine: String,
        posterUrl: String?,
        qrContent: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val poster = loadPoster(posterUrl)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val accent = AccentManager.color(context)

            // ── Fondo del panel derecho: degradado profundo con matiz ──
            p.shader = LinearGradient(
                POSTER_W.toFloat(), 0f, W.toFloat(), H.toFloat(),
                intArrayOf(0xFF0D0D15.toInt(), 0xFF1D1D2B.toInt()),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(POSTER_W.toFloat(), 0f, W.toFloat(), H.toFloat(), p)
            p.shader = null

            // ── Poster a sangre izquierda (cover, focus 40% superior) ──
            if (poster != null) {
                val scale = max(POSTER_W.toFloat() / poster.width, H.toFloat() / poster.height)
                val dw = (poster.width * scale).toInt()
                val dh = (poster.height * scale).toInt()
                val left = (POSTER_W - dw) / 2
                val top = ((H - dh) * 40 / 100).coerceAtMost(0)
                c.drawBitmap(poster, null, Rect(left, top, left + dw, top + dh), p)
                // scrim sutil para unificar con el panel
                p.shader = LinearGradient(
                    0f, 0f, POSTER_W.toFloat(), 0f,
                    intArrayOf(0x00000000, 0x2E0D0D15),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, POSTER_W.toFloat(), H.toFloat(), p)
                p.shader = null
            } else {
                // Sin poster: degradado + glifo play grande centrado
                p.shader = LinearGradient(
                    0f, 0f, POSTER_W.toFloat(), H.toFloat(),
                    intArrayOf(0xFF15151F.toInt(), 0xFF262638.toInt()),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, POSTER_W.toFloat(), H.toFloat(), p)
                p.shader = null
                drawGlyph(c, p, POSTER_W / 2f - 70, H / 2f - 70, 140f, 0x55FFFFFF.toInt())
            }

            // ── Separador de acento entre poster y panel ──
            p.color = accent
            c.drawRect(POSTER_W.toFloat(), 0f, POSTER_W + 7f, H.toFloat(), p)

            // ── Marca LUMEN (pastilla vidrio arriba del panel) ──
            val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = 22f; isFakeBoldText = true
            }
            val brandText = "LUMEN"
            val brandW = brand.measureText(brandText)
            val pillH = 58f
            val pillW = 20f + 26f + 10f + brandW + 20f
            val pill = RectF(TEXT_X.toFloat(), 54f, TEXT_X + pillW, 54f + pillH)
            p.color = 0x59050508.toInt()
            c.drawRoundRect(pill, pillH / 2, pillH / 2, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x26FFFFFF
            c.drawRoundRect(pill, pillH / 2, pillH / 2, p)
            p.style = Paint.Style.FILL
            drawGlyph(c, p, pill.left + 20f, pill.top + (pillH - 26f) / 2, 26f, accent)
            c.drawText(brandText, pill.left + 20f + 26f + 10f, pill.centerY() + brand.textSize * 0.35f, brand)

            // ── Titulo (3 lineas max, corte por palabras) ──
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = 60f; isFakeBoldText = true
            }
            val maxW = (TEXT_RIGHT - TEXT_X).toFloat()
            val lines = wrapTitle(title, titlePaint, maxW, 3)
            val lineH = titlePaint.textSize * 1.14f
            var y = 236f
            for (ln in lines) { c.drawText(ln, TEXT_X.toFloat(), y, titlePaint); y += lineH }

            // ── Barra de acento ──
            val barY = y - lineH + titlePaint.textSize + 26f
            p.color = accent
            c.drawRoundRect(RectF(TEXT_X.toFloat(), barY, TEXT_X + 88f, barY + 7f), 3.5f, 3.5f, p)

            // ── Meta (anio - genero) ──
            val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFC9C9CE.toInt(); textSize = 28f
            }
            if (metaLine.isNotBlank()) c.drawText(metaLine, TEXT_X.toFloat(), barY + 46f, meta)

            // ── QR (pastilla blanca abajo-derecha) + instruccion ──
            if (!qrContent.isNullOrBlank()) {
                try {
                    val qrBmp = QrHelper.qrBitmap(qrContent, 420)
                    val qrPad = 14f
                    val boxSize = 160f + qrPad * 2
                    val boxL = TEXT_RIGHT - boxSize
                    val boxT = H - 48f - boxSize
                    p.color = 0xFFFFFFFF.toInt()
                    c.drawRoundRect(RectF(boxL, boxT, boxL + boxSize, boxT + boxSize), 20f, 20f, p)
                    c.drawBitmap(
                        qrBmp, null,
                        Rect((boxL + qrPad).toInt(), (boxT + qrPad).toInt(),
                            (boxL + boxSize - qrPad).toInt(), (boxT + boxSize - qrPad).toInt()),
                        p
                    )
                    val inst = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFFC9C9CE.toInt(); textSize = 24f; isFakeBoldText = true
                    }
                    val instX = TEXT_X.toFloat()
                    val cy = boxT + boxSize / 2f
                    c.drawText("Escanea y míralo", instX, cy - 6f, inst)
                    c.drawText("directo en Lumen", instX, cy + 28f, inst)
                } catch (_: Exception) {}
            }

            // ── Guardar y devolver content:// ──
            val dir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
            val f = File(dir, "lumen_card_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 96, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        } catch (_: Exception) {
            null
        }
    }

    /** Glifo play de Lumen dibujado por path (reutilizable, vector 24 escalado). */
    private fun drawGlyph(c: Canvas, p: Paint, x: Float, y: Float, size: Float, color: Int) {
        val gl = size / 24f
        val path = Path().apply {
            moveTo(x + 6.2f * gl, y + 3.4f * gl)
            lineTo(x + 20.4f * gl, y + 12f * gl)
            lineTo(x + 6.2f * gl, y + 20.6f * gl)
            close()
        }
        p.color = color
        c.drawPath(path, p)
    }

    /** Corte por palabras; recorta con … si excede [maxLines]. */
    private fun wrapTitle(title: String, paint: Paint, maxW: Float, maxLines: Int): List<String> {
        val words = title.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) <= maxW) {
                cur = test
            } else {
                if (cur.isNotEmpty()) lines.add(cur)
                cur = w
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && cur.isNotEmpty()) lines.add(cur)
        if (lines.size == maxLines && words.joinToString(" ") != lines.joinToString(" ")) {
            var last = lines.last()
            while (last.isNotEmpty() && paint.measureText("$last…") > maxW) {
                last = last.dropLast(1)
            }
            lines[maxLines - 1] = "$last…"
        }
        return lines
    }

    private fun loadPoster(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            if (conn.responseCode in 200..299) BitmapFactory.decodeStream(conn.inputStream) else null
        } catch (_: Exception) {
            null
        }
    }
}
