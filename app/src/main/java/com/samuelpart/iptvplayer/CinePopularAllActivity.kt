package com.samuelpart.iptvplayer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityCinePopularAllBinding
import kotlinx.coroutines.launch

/** Pantalla completa "POPULARES": todo el catalogo de peliculas populares. */
class CinePopularAllActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCinePopularAllBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCinePopularAllBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPopularBack.setOnClickListener { finish() }
        binding.rvPopularAll.layoutManager = GridLayoutManager(this, 3)

        lifecycleScope.launch {
            val catalog = CineRepository.getCineCatalog(this@CinePopularAllActivity)
            val movies = catalog
                .filter { it.type == "movie" }
                .sortedBy { Math.abs(it.title.hashCode()) }
            binding.rvPopularAll.adapter = CineMediaAdapter(movies) { m ->
                val intent = Intent(
                    this@CinePopularAllActivity,
                    if (m.type == "movie") CineMovieDetailActivity::class.java else CineTvShowDetailActivity::class.java
                )
                intent.putExtra("media", m)
                startActivity(intent)
            }
        }
    }
}
