package com.samuelpart.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityWatchHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Historial de lo visto (series y peliculas, no canales): poster + nombre +
 * "te quedaste en el minuto X". El lapiz de editar activa el modo seleccion
 * para borrar entradas.
 */
class WatchHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchHistoryBinding
    private lateinit var adapter: WatchHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnWatchHistBack.setOnClickListener { finish() }

        adapter = WatchHistoryAdapter(
            onItemClick = { entry ->
                val m = entry.media ?: return@WatchHistoryAdapter
                val intent = Intent(
                    this,
                    if (m.type == "movie") CineMovieDetailActivity::class.java
                    else CineTvShowDetailActivity::class.java
                )
                intent.putExtra("media", m)
                startActivity(intent)
            },
            onLongPress = { setEditMode(true) },
            onSelectionChanged = { count -> updateSelectionUi(count) }
        )
        binding.rvWatchHistory.layoutManager = GridLayoutManager(this, 3)
        binding.rvWatchHistory.adapter = adapter

        binding.btnWatchHistEdit.setOnClickListener {
            setEditMode(adapter.selectionMode.not())
        }
        binding.btnWatchHistDelete.setOnClickListener { deleteSelected() }

        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                ContinueWatchingManager.getAll(this@WatchHistoryActivity)
                    .filter { !it.isChannel }
            }
            // Respaldo de poster: catalogo por titulo (entradas viejas sin media)
            val posters = withContext(Dispatchers.IO) {
                val catalog = CineRepository.getCineCatalog(this@WatchHistoryActivity)
                catalog.associate { it.title.lowercase().trim() to (it.posterUrl ?: it.rawLogo) }
            }
            adapter.submit(entries, posters)
            binding.txtWatchHistEmpty.visibility =
                if (entries.isEmpty()) View.VISIBLE else View.GONE
            setEditMode(false)
        }
    }

    private fun setEditMode(active: Boolean) {
        adapter.setSelectionMode(active)
        binding.btnWatchHistEdit.text = if (active) "Cancelar" else "Editar"
        binding.barWatchDelete.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun updateSelectionUi(count: Int) {
        binding.btnWatchHistDelete.text = "Borrar seleccionados ($count)"
        binding.btnWatchHistDelete.isEnabled = count > 0
        binding.btnWatchHistDelete.alpha = if (count > 0) 1f else 0.5f
    }

    private fun deleteSelected() {
        val urls = adapter.selectedUrls()
        if (urls.isEmpty()) return
        urls.forEach { ContinueWatchingManager.remove(this, it) }
        Toast.makeText(this, "${urls.size} eliminados del historial", Toast.LENGTH_SHORT).show()
        reload()
    }
}
