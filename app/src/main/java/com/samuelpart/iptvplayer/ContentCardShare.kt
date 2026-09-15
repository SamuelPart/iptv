package com.samuelpart.iptvplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Genera la TARJETA de contenido compartible (idea #1): 1080x1350 con el
 * poster a sangre completa, degradado cinematografico inferior, titulo con
 * corte inteligente, linea de meta (anio - genero) y firma de Lumen con
 * su glifo. Estilo glass Apple consistente con el reproductor y el dock.
 */
object ContentCardShare {

    private const val W = 1080
    private const val H = 1350

    /** Genera el PNG de la tarjeta y devuelve su uri content:// lista para compartir. */
    suspend fun buildCardUri(
        context: Context,
        title: String,
        metaLine: String,
        posterUrl: String?
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val poster = loadPoster(posterUrl)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val densityScale = W / 360f

            // ── Fondo ──
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (poster != null) {
                // cover centrado-recortado (focus 40% superior para no perder caras)
                val scale = max(W.toFloat() / poster.width, H.toFloat() / poster.height)
                val dw = (poster.width * scale).toInt()
                val dh = (poster.height * scale).toInt()
                val left = (W - dw) / 2
                val top = min(0, (H - dh) * 40 / 100)
                c.drawBitmap(poster, null, Rect(left, top, left + dw, top + dh), paint)
            } else {
                paint.shader = LinearGradient(
                    0f, 0f, W.toFloat(), H.toFloat(),
                    intArrayOf(0xFF14141C.toInt(), 0xFF2A2A3A.toInt()),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
            }

            // ── Scrims para legibilidad (igual que el reproductor) ──
            paint.shader = LinearGradient(
                0f, H * 0.28f, 0f, H.toFloat(),
                intArrayOf(0x00000000, 0xF2000000.toInt()),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, H * 0.28f, W.toFloat(), H.toFloat(), paint)
            paint.shader = LinearGradient(
                0f, 0f, 0f, H * 0.18f,
                intArrayOf(0x99000000.toInt(), 0x00000000),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, W.toFloat(), H * 0.18f, paint)
            paint.shader = null

            val accent = AccentManager.color(context)

            // ── Marca arriba-izquierda (pastilla de vidrio + glifo) ──
            val pillH = 64 * densityScale
            val glyphSize = 30 * densityScale
            val padX = 34 * densityScale
            val brandText = "LUMEN"
            val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 24 * densityScale
                isFakeBoldText = true
            }
            val brandW = brand.measureText(brandText)
            val pillW = padX + glyphSize + 10 * densityScale + brandW + padX
            val pill = RectF(padX, padX, padX + pillW, padX + pillH)
            paint.color = 0x59050508.toInt()
            c.drawRoundRect(pill, pillH / 2, pillH / 2, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * densityScale
            paint.color = 0x26FFFFFF
            c.drawRoundRect(pill, pillH / 2, pillH / 2, paint)
            paint.style = Paint.Style.FILL
            // glifo prisma
            val gl = 2.8f * densityScale // escala del vector 24 -> dibujo
            val gx = pill.left + padX
            val gy = pill.top + (pillH - glyphSize) / 2
            val tri = android.graphics.Path().apply {
                moveTo(gx + 12 * gl, gy + 2.6f * gl)
                lineTo(gx + 21.2f * gl, gy + 19.2f * gl)
                lineTo(gx + 2.8f * gl, gy + 19.2f * gl)
                close()
            }
            paint.color = accent
            c.drawPath(tri, paint)
            c.drawText(
                brandText, gx + glyphSize + 10 * densityScale,
                pill.centerY() + brand.textSize * 0.35f, brand
            )

            // ── Titulo con corte inteligente (max 3 lineas) ──
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 64 * densityScale
                isFakeBoldText = true
            }
            val maxTextW = W - 2 * padX
            val lines = wrapTitle(title, titlePaint, maxTextW, 3)
            val lineH = titlePaint.textSize * 1.16f
            val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFC9C9CE.toInt()
                textSize = 30 * densityScale
            }
            val metaText = metaLine.trim()
            val blockH = lines.size * lineH + (if (metaText.isEmpty()) 0f else meta.textSize + 18 * densityScale)
            val baseY = H - 92 * densityScale - blockH + titlePaint.textSize

            var y = baseY
            for (ln in lines) {
                c.drawText(ln, padX, y, titlePaint)
                y += lineH
            }
            if (metaText.isNotEmpty()) {
                c.drawText(metaText, padX, y + 10 * densityScale, meta)
            }

            // ── Linea de acento bajo el bloque ──
            paint.color = accent
            c.drawRoundRect(
                RectF(padX, H - 62 * densityScale, padX + 88 * densityScale, H - 54 * densityScale),
                4 * densityScale, 4 * densityScale, paint
            )

            // ── Guardar y devolver content:// ──
            val dir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
            val f = File(dir, "lumen_card_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 96, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        } catch (_: Exception) {
            null
        }
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
