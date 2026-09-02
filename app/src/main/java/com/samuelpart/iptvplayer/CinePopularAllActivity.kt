package com.samuelpart.iptvplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityCinePopularAllBinding
import kotlinx.coroutines.launch

/**
 * Pantalla de lista generica del Cine. Recibe extras:
 *   title: texto del encabezado
 *   kind:  all | favorites | continue | recent | genre | platform | alerts | history
 *   param: valor del filtro (genero/plataforma)
 */
class CinePopularAllActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCinePopularAllBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCinePopularAllBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra("title") ?: "POPULARES"
        val kind = intent.getStringExtra("kind") ?: "all"
        val param = intent.getStringExtra("param") ?: ""

        binding.txtPopularAllTitle.text = title
        binding.btnPopularBack.setOnClickListener { finish() }
        binding.rvPopularAll.layoutManager = GridLayoutManager(this, 3)

        lifecycleScope.launch {
            val catalog = CineRepository.getCineCatalog(this@CinePopularAllActivity)

            val list: List<CineMedia> = when (kind) {
                "favorites" -> catalog.filter { FavoritesManager.isFavorite(this@CinePopularAllActivity, it.url) }

                "continue" -> {
                    val watching = ContinueWatchingManager.getAll(this@CinePopularAllActivity)
                        .filter { !it.isChannel }.map { it.title }.toHashSet()
                    catalog.filter { it.title in watching }
                }

                "recent" -> catalog
                    .sortedByDescending { it.releaseDate ?: "" }
                    .take(60)

                "genre" -> catalog.filter { m ->
                    if (param == "anime") {
                        m.title.lowercase().contains("anime") || m.group.lowercase().contains("anime")
                    } else {
                        TasteProfile.genreKeysOf(m).contains(param)
                    }
                }

                "platform" -> {
                    val aliases = when (param.lowercase()) {
                        "hbo" -> listOf("hbo", "max")
                        "netflix" -> listOf("netflix")
                        "disney" -> listOf("disney")
                        "prime" -> listOf("prime", "amazon")
                        else -> listOf(param.lowercase())
                    }
                    catalog.filter { m ->
                        aliases.any { p ->
                            m.group.lowercase().contains(p) ||
                                m.title.lowercase().contains(p) ||
                                m.searchTitle.lowercase().contains(p) ||
                                (m.platformName?.lowercase()?.contains(p) == true) ||
                                android.net.Uri.parse(m.url).host?.contains(p) == true
                        }
                    }
                }

                "alerts" -> {
                    val log = getSharedPreferences("cine_latest_state", Context.MODE_PRIVATE)
                        .getString("alerts_log_v1", "") ?: ""
                    log.split("\n").filter { it.isNotBlank() }.map { line ->
                        val parts = line.split("|")
                        val t = parts.getOrNull(0) ?: ""
                        CineMedia(
                            title = t,
                            searchTitle = t,
                            url = t, // el click buscara la copia real del catalogo
                            rawLogo = parts.getOrNull(2) ?: "",
                            type = "movie",
                            group = parts.getOrNull(1) ?: "",
                            posterUrl = parts.getOrNull(2)?.ifBlank { null }
                        )
                    }
                }

                "history" -> {
                    val terms = getSharedPreferences("IPTV_PREFS", Context.MODE_PRIVATE)
                        .getString("CINE_SEARCH_HISTORY", "") ?: ""
                    val queries = terms.split("|||").filter { it.isNotEmpty() }
                    catalog.filter { m -> queries.any { q -> m.title.lowercase().contains(q.lowercase()) } }
                }

                else -> catalog.filter { it.type == "movie" }
                    .sortedBy { Math.abs(it.title.hashCode()) }
            }

            binding.rvPopularAll.adapter = CineMediaAdapter(list, onMediaClick = { m ->
                val real = if (kind == "alerts") catalog.find { it.title == m.title } ?: m else m
                startActivity(
                    Intent(
                        this@CinePopularAllActivity,
                        if (real.type == "movie") CineMovieDetailActivity::class.java else CineTvShowDetailActivity::class.java
                    ).apply { putExtra("media", real) }
                )
            })
        }
    }
}
