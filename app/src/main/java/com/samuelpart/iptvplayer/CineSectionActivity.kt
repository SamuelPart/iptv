package com.samuelpart.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seccion de Inicio en pantalla propia: MISMA interfaz del Inicio (buscador
 * con lupa + cuadricula 3x3 + loading sin texto) pero mostrando SOLO el
 * catalogo de la seccion elegida (Peliculas, Series, Animacion, Plataformas).
 * El contenido carga por tandas y busca dentro de la seccion.
 */
class CineSectionActivity : AppCompatActivity() {

    private lateinit var adapter: CineSearchResultAdapter
    private lateinit var edtSearch: EditText
    private lateinit var rv: RecyclerView
    private lateinit var pb: View
    private lateinit var txtEmpty: TextView
    private lateinit var btnClear: ImageView

    private var full: List<CineMedia> = emptyList()
    private var filtered: List<CineMedia> = emptyList()
    private var shown = 0
    private var loadingMore = false
    private var useBadges = false
    private val PAGE = 12
    private val handler = Handler(Looper.getMainLooper())
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cine_section)

        val title = intent.getStringExtra("title") ?: "SECCIÓN"
        val kind = intent.getStringExtra("kind") ?: "type"
        val param = intent.getStringExtra("param") ?: ""
        useBadges = intent.getBooleanExtra("show_platform_badges", false)

        findViewById<TextView>(R.id.txtSectionTitle).text = title.uppercase()
        findViewById<ImageView>(R.id.btnSectionBack).setOnClickListener { finish() }

        rv = findViewById(R.id.rvSectionGrid)
        edtSearch = findViewById(R.id.edtSectionSearch)
        pb = findViewById(R.id.pbSectionLoader)
        txtEmpty = findViewById(R.id.txtSectionEmpty)
        btnClear = findViewById(R.id.btnSectionClear)

        adapter = CineSearchResultAdapter(emptyList(), onMediaClick = { m ->
            startActivity(
                Intent(
                    this,
                    if (m.type == "movie") CineMovieDetailActivity::class.java
                    else CineTvShowDetailActivity::class.java
                ).apply { putExtra("media", m) }
            )
        })
        adapter.gridColumns = 3
        if (useBadges) adapter.platformBadgeResolver = { m -> PlatformCatalog.logoOf(m) }
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) maybeLoadMore()
            }
        })

        btnClear.setOnClickListener { edtSearch.setText("") }
        edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        edtSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applyFilter(edtSearch.text.toString().trim())
                true
            } else false
        }
        var pending: Runnable? = null
        edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                pending?.let { handler.removeCallbacks(it) }
                val r = Runnable { applyFilter(s.toString().trim()) }
                pending = r
                handler.postDelayed(r, 350)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        lifecycleScope.launch {
            val catalog = withContext(Dispatchers.IO) {
                try { CineRepository.getCineCatalog(this@CineSectionActivity) } catch (e: Exception) { emptyList() }
            }
            full = withContext(Dispatchers.Default) {
                when (kind) {
                    "type" -> catalog
                        .filter { if (param == "series") it.type != "movie" else it.type == "movie" }
                        .sortedBy { it.title }
                    "genre" -> catalog
                        .filter { m ->
                            if (param == "animación") {
                                m.title.lowercase().contains("animac") ||
                                    m.group.lowercase().contains("animac") ||
                                    m.group.lowercase().contains("anime") ||
                                    TasteProfile.genreKeysOf(m).contains("animación")
                            } else {
                                TasteProfile.genreKeysOf(m).contains(param)
                            }
                        }
                        .sortedByDescending { it.rating ?: -1.0 }
                    "platform_all" -> {
                        val aliases = listOf("netflix", "disney", "hbo", "max", "prime", "amazon", "apple", "hulu", "paramount", "peacock", "skyshowtime")
                        catalog.filter { m ->
                            aliases.any { p ->
                                m.group.lowercase().contains(p) ||
                                    m.title.lowercase().contains(p) ||
                                    m.searchTitle.lowercase().contains(p) ||
                                    (m.platformName?.lowercase()?.contains(p) == true)
                            }
                        }.sortedBy { it.title }
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
                                    (m.platformName?.lowercase()?.contains(p) == true)
                            }
                        }.sortedBy { it.title }
                    }
                    else -> catalog.sortedBy { it.title }
                }
            }
            filtered = full
            showFirstPage()
        }
    }

    private fun applyFilter(q: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            filtered = withContext(Dispatchers.Default) {
                if (q.isEmpty()) full
                else full.filter {
                    it.title.lowercase().contains(q.lowercase()) ||
                        it.searchTitle.lowercase().contains(q.lowercase())
                }
            }
            showFirstPage()
        }
    }

    private fun showFirstPage() {
        shown = minOf(PAGE, filtered.size)
        loadingMore = false
        adapter.updateList(filtered.take(shown))
        txtEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Al llegar cerca del final: loading SIN TEXTO + 12 mas. */
    private fun maybeLoadMore() {
        if (loadingMore || shown >= filtered.size) return
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val total = rv.adapter?.itemCount ?: return
        if (lm.findLastVisibleItemPosition() < total - 4) return
        loadingMore = true
        pb.visibility = View.VISIBLE
        rv.postDelayed({
            shown = minOf(shown + PAGE, filtered.size)
            adapter.updateList(filtered.take(shown))
            pb.visibility = View.GONE
            loadingMore = false
        }, 350)
    }
}
