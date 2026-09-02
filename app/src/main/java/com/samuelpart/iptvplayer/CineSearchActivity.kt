package com.samuelpart.iptvplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityCineSearchBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Buscador INDEPENDIENTE de cine (películas + series).
 * No tiene nada que ver con la pestaña Search (que es solo para canales de TV).
 * Guarda historial en CINE_SEARCH_HISTORY (sep "|||") que alimenta el item
 * "Historial de búsqueda" del menú hamburguesa.
 */
class CineSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineSearchBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var catalog: List<CineMedia> = emptyList()
    private var lastSavedQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        binding.rvCineSearch.layoutManager = GridLayoutManager(this, 3)

        binding.btnCineSearchBack.setOnClickListener { finish() }

        binding.editCineQuery.doAfterTextChanged { t ->
            runSearch(t?.toString().orEmpty())
        }
        binding.editCineQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                saveToHistory(binding.editCineQuery.text?.toString().orEmpty())
                true
            } else false
        }

        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try { CineRepository.getCineCatalog(applicationContext) } catch (_: Exception) { emptyList() }
            }
            catalog = list
            runSearch(binding.editCineQuery.text?.toString().orEmpty())
            binding.editCineQuery.requestFocus()
        }
    }

    private fun runSearch(q0: String) {
        val q = q0.trim().lowercase()
        if (q.length < 2) {
            binding.txtCineSearchHint.visibility = View.VISIBLE
            binding.txtCineSearchHint.text = if (catalog.isEmpty())
                "Descargando catálogo..." else "Escribe para buscar en películas y series"
            binding.rvCineSearch.adapter = CineMediaAdapter(emptyList(), onMediaClick = { })
            return
        }
        val results = catalog
            .filter { m ->
                m.title.lowercase().contains(q) ||
                    m.searchTitle.lowercase().contains(q) ||
                    m.group.lowercase().contains(q)
            }
            .sortedBy { !it.title.lowercase().startsWith(q) }
            .take(90)

        binding.txtCineSearchHint.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        if (results.isEmpty()) {
            binding.txtCineSearchHint.text = "Sin resultados para \"$q0\""
        }
        binding.rvCineSearch.adapter = CineMediaAdapter(results, onMediaClick = { m ->
            saveToHistory(q0)
            startActivity(
                Intent(
                    this,
                    if (m.type == "movie") CineMovieDetailActivity::class.java else CineTvShowDetailActivity::class.java
                ).apply { putExtra("media", m) }
            )
        })

        // Guarda en historial si la consulta se estabiliza (sin duplicar seguidas)
        if (q.length >= 3 && lastSavedQuery != q) {
            lastSavedQuery = q
            saveToHistory(q0)
        }
    }

    private fun saveToHistory(query: String) {
        val clean = query.trim()
        if (clean.length < 3) return
        val prefs = getSharedPreferences("IPTV_PREFS", Context.MODE_PRIVATE)
        val prev = (prefs.getString("CINE_SEARCH_HISTORY", "") ?: "")
            .split("|||").filter { it.isNotEmpty() && !it.equals(clean, true) }
        val next = (listOf(clean) + prev).take(30)
        prefs.edit().putString("CINE_SEARCH_HISTORY", next.joinToString("|||")).apply()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editCineQuery.windowToken, 0)
    }
}
