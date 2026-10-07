package com.samuelpart.iptvplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer

/** Titulo popular (trending TMDB) listo para pintar. Si [media] no es null,
 *  existe en el catalogo y el play abre la ficha. */
data class PopularItem(
    val title: String,
    val type: String, // "movie" | "tv"
    val poster: String,
    val rating: Double,
    val media: CineMedia?
)

/**
 * Buscador de Inicio: SOLO peliculas y series (nada de canales). Busca por
 * titulo y tambien por ACTOR o DIRECTOR (via TMDB search/person intersectado
 * con el catalogo). En reposo muestra el historial de busquedas (con papelera
 * por fila, ultimas 10) y la fila "Busqueda popular" con poster + HOT/TOP +
 * play. Boton Cancelar abajo cierra la pantalla. Anuncio en bloque abajo.
 */
class CineSearchActivity : AppCompatActivity() {

    private lateinit var catalog: List<CineMedia>
    private var catalogNorm: List<Pair<CineMedia, String>> = emptyList()
    private var catalogReady = false

    private lateinit var edtInput: EditText
    private lateinit var rvHistory: RecyclerView
    private lateinit var rvPopular: RecyclerView
    private lateinit var rvResults: RecyclerView
    private lateinit var scrollIdle: View
    private lateinit var txtHistoryEmpty: TextView
    private lateinit var txtResultsEmpty: TextView
    private lateinit var btnClear: ImageView
    private lateinit var historyAdapter: SearchHistoryRowAdapter
    private lateinit var popularAdapter: PopularAdapter
    private lateinit var resultsAdapter: CineSearchResultsAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var lastSubmitted = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cine_search)

        edtInput = findViewById(R.id.edtCineSearchInput)
        rvHistory = findViewById(R.id.rvCineSearchHistory)
        rvPopular = findViewById(R.id.rvCinePopular)
        rvResults = findViewById(R.id.rvCineResults)
        scrollIdle = findViewById(R.id.scrollIdle)
        txtHistoryEmpty = findViewById(R.id.txtHistoryEmpty)
        txtResultsEmpty = findViewById(R.id.txtResultsEmpty)
        btnClear = findViewById(R.id.btnCineSearchClear)

        rvHistory.layoutManager = LinearLayoutManager(this)
        historyAdapter = SearchHistoryRowAdapter(
            onClick = { q -> edtInput.setText(q); edtInput.setSelection(q.length); submitSearch(q) },
            onDelete = { q ->
                SearchHistoryStore.remove(this, q)
                refreshHistory()
            }
        )
        rvHistory.adapter = historyAdapter

        rvPopular.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        popularAdapter = PopularAdapter { item ->
            val m = item.media
            if (m != null) openDetail(m) else Toast.makeText(
                this, "\"${item.title}\" aún no está en el catálogo", Toast.LENGTH_SHORT
            ).show()
        }
        rvPopular.adapter = popularAdapter

        rvResults.layoutManager = LinearLayoutManager(this)
        resultsAdapter = CineSearchResultsAdapter { media ->
            if (lastSubmitted.isNotEmpty()) {
                SearchHistoryStore.add(this, lastSubmitted)
                refreshHistory()
            }
            openDetail(media)
        }
        rvResults.adapter = resultsAdapter

        findViewById<TextView>(R.id.btnSearchCancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {
            SearchHistoryStore.clear(this)
            refreshHistory()
        }
        btnClear.setOnClickListener {
            edtInput.setText("")
        }

        edtInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                pendingSearch?.let { handler.removeCallbacks(it) }
                if (s.isNullOrBlank()) {
                    lastSubmitted = ""
                    showIdle()
                } else {
                    val q = s.toString().trim()
                    val r = Runnable { submitSearch(q) }
                    pendingSearch = r
                    handler.postDelayed(r, 450)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        edtInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = edtInput.text.toString().trim()
                if (q.isNotEmpty()) submitSearch(q)
                hideKeyboard()
                true
            } else false
        }

        NativeAds.attach(this, findViewById(R.id.adSlotSearch), NativeAds.VARIANT_MEDIA)

        // Teclado visible al entrar
        edtInput.requestFocus()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        refreshHistory()

        lifecycleScope.launch {
            val cat = withContext(Dispatchers.IO) {
                try { CineRepository.getCineCatalog(this@CineSearchActivity) } catch (e: Exception) { emptyList() }
            }
            catalog = cat
            catalogNorm = cat.map { it to norm(it.searchTitle.ifEmpty { it.title }) }
            catalogReady = true
            loadPopular()
            val q = edtInput.text.toString().trim()
            if (q.isNotEmpty()) submitSearch(q)
        }
    }

    // ══════════════ Historial ══════════════

    private fun refreshHistory() {
        val list = SearchHistoryStore.get(this)
        historyAdapter.submit(list)
        txtHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ══════════════ Popular (trending TMDB ∩ catálogo) ══════════════

    private suspend fun loadPopular() {
        val raw = withContext(Dispatchers.IO) {
            val arr = tmdbJson(
                "https://api.themoviedb.org/3/trending/all/week" +
                    "?api_key=${CineRepository.TMDB_API_KEY}&language=es-ES"
            )?.optJSONArray("results")
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = r.optString("title", "").ifEmpty { r.optString("name", "") }
                if (title.isEmpty()) return@mapNotNull null
                val path = r.optString("poster_path", "")
                PopularItem(
                    title = title,
                    type = r.optString("media_type", "movie"),
                    poster = if (path.isNotEmpty()) "https://image.tmdb.org/t/p/w780$path" else "",
                    rating = r.optDouble("vote_average", 0.0),
                    media = null
                )
            }
        }
        if (raw.isEmpty()) return
        val matched = withContext(Dispatchers.IO) {
            raw.map { it.copy(media = if (catalogReady) findInCatalog(it.title) else null) }
                .sortedWith(compareByDescending<PopularItem> { it.media != null }.thenByDescending { it.rating })
                .take(15)
        }
        popularAdapter.submit(matched)
    }

    // ══════════════ Búsqueda ══════════════

    private fun submitSearch(q: String) {
        lastSubmitted = q
        showResultsMode()
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) {
                val nq = norm(q)
                if (nq.isEmpty()) emptyList()
                else catalogNorm
                    .filter { it.second.contains(nq) || norm(it.first.title).contains(nq) }
                    .map { it.first }
                    .sortedWith(compareBy({ !it.searchTitle.contains(q, true) && !it.title.contains(q, true) }, { it.title }))
                    .take(40)
            }
            val byPerson = if (q.length >= 2) personMatches(q).filter { p ->
                local.none { it.url == p.url }
            } else emptyList()
            val merged = local + byPerson
            resultsAdapter.submit(merged)
            txtResultsEmpty.visibility = if (merged.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Busca ACTOR o DIRECTOR: TMDB /search/person y cruza sus titulos
     *  (known_for) contra nuestro catalogo; solo devuelve lo reproducible. */
    private suspend fun personMatches(q: String): List<CineMedia> = withContext(Dispatchers.IO) {
        if (!catalogReady) return@withContext emptyList()
        val arr = tmdbJson(
            "https://api.themoviedb.org/3/search/person" +
                "?api_key=${CineRepository.TMDB_API_KEY}&query=${URLEncoder.encode(q, "UTF-8")}&language=es-ES"
        )?.optJSONArray("results") ?: return@withContext emptyList()
        val out = LinkedHashMap<String, CineMedia>()
        val persons = minOf(2, arr.length())
        for (p in 0 until persons) {
            val known = arr.getJSONObject(p).optJSONArray("known_for") ?: continue
            for (k in 0 until known.length()) {
                val item = known.optJSONObject(k) ?: continue
                val t = item.optString("title", "").ifEmpty { item.optString("name", "") }
                if (t.isEmpty()) continue
                val m = findInCatalog(t) ?: continue
                out[m.url] = m
                if (out.size >= 20) break
            }
            if (out.size >= 20) break
        }
        out.values.toList()
    }

    private fun findInCatalog(title: String): CineMedia? {
        val n = norm(title)
        if (n.isEmpty()) return null
        catalogNorm.forEach { (media, nSearch) ->
            if (nSearch == n) return media
        }
        catalogNorm.forEach { (media, nSearch) ->
            if (nSearch.length >= 4 && n.length >= 4 &&
                (nSearch.contains(n) || n.contains(nSearch))) return media
        }
        return null
    }

    // ══════════════ UI helpers ══════════════

    private fun showIdle() {
        scrollIdle.visibility = View.VISIBLE
        rvResults.visibility = View.GONE
        txtResultsEmpty.visibility = View.GONE
        refreshHistory()
    }

    private fun showResultsMode() {
        scrollIdle.visibility = View.GONE
        rvResults.visibility = View.VISIBLE
    }

    private fun openDetail(media: CineMedia) {
        hideKeyboard()
        val dest = if (media.type == "movie") CineMovieDetailActivity::class.java
        else CineTvShowDetailActivity::class.java
        startActivity(Intent(this, dest).apply { putExtra("media", media) })
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edtInput.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    private fun tmdbJson(urlString: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode == 200) {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingSearch?.let { handler.removeCallbacks(it) }
    }
}

// ══════════════════════════════════════════════════════════════
// Adapters del buscador
// ══════════════════════════════════════════════════════════════

/** Fila de historial: reloj + texto + papelera. */
class SearchHistoryRowAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryRowAdapter.Holder>() {

    private val items = mutableListOf<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cine_search_history, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val q = items[position]
        holder.txt.text = q
        holder.itemView.setOnClickListener { onClick(q) }
        holder.btnDelete.setOnClickListener { onDelete(q) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val txt: TextView = v.findViewById(R.id.txtHistoryQuery)
        val btnDelete: ImageView = v.findViewById(R.id.btnDeleteQuery)
    }
}

/** Tarjeta de la fila popular: poster + HOT/TOP + play. */
class PopularAdapter(private val onTap: (PopularItem) -> Unit) :
    RecyclerView.Adapter<PopularAdapter.Holder>() {

    private val items = mutableListOf<PopularItem>()

    fun submit(list: List<PopularItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_popular_cine, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.txtTitle.text = item.title
        Glide.with(holder.img).load(item.poster).centerCrop()
            .placeholder(R.drawable.bg_tile_glass).into(holder.img)
        // HOT: primeros 5 del ranking; TOP: calificacion alta
        holder.txtHot.visibility = if (position < 5) View.VISIBLE else View.GONE
        holder.txtTop.visibility = if (item.rating >= 7.5) View.VISIBLE else View.GONE
        holder.imgPlay.visibility = if (item.media != null) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onTap(item) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgPopularPoster)
        val txtTitle: TextView = v.findViewById(R.id.txtPopularTitle)
        val txtHot: TextView = v.findViewById(R.id.txtPopularHot)
        val txtTop: TextView = v.findViewById(R.id.txtPopularTop)
        val imgPlay: ImageView = v.findViewById(R.id.imgPopularPlay)
    }
}

/** Fila de resultado: poster + titulo + año · tipo. */
class CineSearchResultsAdapter(private val onTap: (CineMedia) -> Unit) :
    RecyclerView.Adapter<CineSearchResultsAdapter.Holder>() {

    private val items = mutableListOf<CineMedia>()

    fun submit(list: List<CineMedia>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cine_search_row, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = items[position]
        holder.txtTitle.text = m.title
        val year = m.releaseDate?.take(4) ?: ""
        val kind = if (m.type == "movie") "Película" else "Serie"
        holder.txtSub.text = if (year.isNotEmpty()) "$year · $kind" else kind
        Glide.with(holder.img).load(m.posterUrl ?: m.rawLogo).centerCrop()
            .placeholder(R.drawable.bg_tile_glass).into(holder.img)
        holder.itemView.setOnClickListener { onTap(m) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgResultPoster)
        val txtTitle: TextView = v.findViewById(R.id.txtResultTitle)
        val txtSub: TextView = v.findViewById(R.id.txtResultSub)
    }
}
