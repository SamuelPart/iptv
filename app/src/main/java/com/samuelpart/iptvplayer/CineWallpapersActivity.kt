package com.samuelpart.iptvplayer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityCineWallpapersBinding
import kotlinx.coroutines.launch

/**
 * Galeria de fondos de pantalla: cuadricula de imagenes (backdrops de
 * peliculas y series). Al tocar una imagen se abre el visor para
 * establecerla como fondo de inicio o de bloqueo.
 */
class CineWallpapersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineWallpapersBinding
    private lateinit var adapter: WallpaperAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineWallpapersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtWallpapersTitle.text = "FONDOS DE PANTALLA"
        binding.btnWallpapersBack.setOnClickListener { finish() }

        adapter = WallpaperAdapter { media ->
            val url = adapter.imageUrlOf(media)
            if (url.isNotBlank()) {
                startActivity(Intent(this, WallpaperViewerActivity::class.java).apply {
                    putExtra("title", media.title)
                    putExtra("url", url)
                })
            }
        }
        binding.rvWallpapers.layoutManager = GridLayoutManager(this, 3)
        binding.rvWallpapers.adapter = adapter

        lifecycleScope.launch {
            val catalog = CineRepository.getCineCatalog(this@CineWallpapersActivity)
            val items = catalog
                .filter { m ->
                    !(m.backdropUrl.isNullOrBlank() && m.posterUrl.isNullOrBlank() && m.rawLogo.isBlank())
                }
                .distinctBy { it.title.lowercase().trim() }
            adapter.submit(items)
            binding.txtWallpapersCount.text = "${items.size}"
        }
    }
}
