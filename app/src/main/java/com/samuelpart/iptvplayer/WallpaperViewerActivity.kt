package com.samuelpart.iptvplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.app.WallpaperManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.samuelpart.iptvplayer.databinding.ActivityWallpaperViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Visor a pantalla completa de un fondo, con acciones para establecerlo
 * como wallpaper del sistema (inicio), de bloqueo o ambos.
 *
 * - API 24+: usa WallpaperManager.setBitmap con FLAG_SYSTEM / FLAG_LOCK.
 * - API 21-23: Android no permite fondo de bloqueo independiente; se aplica
 *   solo al sistema y se avisa al usuario.
 * - La imagen se recorta al centro con la proporcion exacta de la pantalla
 *   para que el wallpaper quede sin bordes ni estiramientos.
 * - Si la imagen viene de TMDB (w780), se pide la version "original".
 */
class WallpaperViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWallpaperViewerBinding
    private var imageUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWallpaperViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra("title") ?: "Fondo"
        imageUrl = intent.getStringExtra("url")

        binding.txtWallpaperTitle.text = title
        binding.btnWallpaperBack.setOnClickListener { finish() }
        Glide.with(this)
            .load(hdUrl(imageUrl))
            .centerCrop()
            .placeholder(R.drawable.bg_tile_glass)
            .into(binding.imgWallpaperFull)

        binding.btnWallpaperHome.setOnClickListener { applyWallpaper(system = true, lock = false) }
        binding.btnWallpaperLock.setOnClickListener {
            if (Build.VERSION.SDK_INT < 24) {
                Toast.makeText(this, "Tu Android no soporta fondo de bloqueo separado (requiere 7.0+)", Toast.LENGTH_LONG).show()
            } else {
                applyWallpaper(system = false, lock = true)
            }
        }
        binding.btnWallpaperBoth.setOnClickListener { applyWallpaper(system = true, lock = true) }
    }

    /** TMDB w780 -> original para maxima calidad como wallpaper. */
    private fun hdUrl(u: String?): String? = u?.replace("/t/p/w780", "/t/p/original")

    private fun applyWallpaper(system: Boolean, lock: Boolean) {
        val url = hdUrl(imageUrl) ?: return
        Toast.makeText(this, "Aplicando fondo…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val src = downloadBitmap(url) ?: return@withContext false
                    val bmp = centerCropToScreen(src)
                    val wm = WallpaperManager.getInstance(this@WallpaperViewerActivity)
                    if (Build.VERSION.SDK_INT >= 24) {
                        if (lock) wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                        if (system) wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                    } else {
                        wm.setBitmap(bmp)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@WallpaperViewerActivity,
                if (ok) "✅ Fondo aplicado" else "No se pudo aplicar el fondo, intenta de nuevo",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun downloadBitmap(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (conn.responseCode in 200..299) BitmapFactory.decodeStream(conn.inputStream) else null
    } catch (_: Exception) {
        null
    }

    /** Recorte centrado a la resolucion de la pantalla (wallpaper sin bordes). */
    private fun centerCropToScreen(src: Bitmap): Bitmap {
        val tw = resources.displayMetrics.widthPixels
        val th = resources.displayMetrics.heightPixels
        if (tw <= 0 || th <= 0) return src
        val scale = max(tw.toFloat() / src.width, th.toFloat() / src.height)
        val dw = (src.width * scale).toInt()
        val dh = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, (tw - dw) / 2f, (th - dh) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
