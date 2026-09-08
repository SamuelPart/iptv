package com.samuelpart.iptvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ActivityCineTvShowDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ficha de SERIE rediseñada desde cero: cabecera con backdrop, póster,
 * título original/local, año, formato, temporadas, episodios, sinopsis,
 * equipo completo (showrunner, dirección, guion, producción, música,
 * fotografía, reparto), datos generales (país, idioma, géneros, duración,
 * plataforma) y contenido (calificación por edad + advertencias).
 */
class CineTvShowDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineTvShowDetailBinding
    private lateinit var media: CineMedia
    private lateinit var episodeAdapter: CineEpisodeAdapter
    private var seasons: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineTvShowDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        val extraMedia = intent.getSerializableExtra("media") as? CineMedia
        if (extraMedia == null) {
            finish()
            return
        }
        media = extraMedia

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { playFirst() }
        binding.btnShare.setOnClickListener { showShareOptions() }

        NativeAds.attach(this, binding.adSlotNative, NativeAds.VARIANT_MEDIA)

        setupEpisodes()
        renderFromMedia()

        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) { CineRepository.fetchTvDetails(media) }
            var episodesFetched = false
            withContext(Dispatchers.IO) {
                if (media.trailerUrl.isNullOrEmpty()) CineRepository.fetchTmdTrailer(media)
                if (media.episodes.isEmpty() && media.url.isNotBlank() && CineRepository.isRemotePlaylist(media.url)) {
                    media.episodes = CineRepository.fetchEpisodesFromPlaylist(media.url)
                    episodesFetched = true
                }
            }
            if (episodesFetched) setupEpisodes() // main thread
            renderFromMedia()
            if (details != null) renderDetails(details)
            binding.progress.visibility = View.GONE
        }
    }

    // ---------------- Episodios ----------------

    private fun setupEpisodes() {
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        episodeAdapter = CineEpisodeAdapter(emptyList()) { episode -> playEpisode(episode) }
        binding.rvEpisodes.adapter = episodeAdapter

        seasons = media.episodes.map { it.season }.distinct().sorted()
        if (seasons.isNotEmpty()) {
            binding.layoutEpisodesHeader.visibility = View.VISIBLE
            val labels = seasons.map { "Temporada $it" }
            val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerSeason.adapter = spinnerAdapter
            binding.spinnerSeason.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val s = seasons[position]
                    episodeAdapter.updateList(media.episodes.filter { it.season == s })
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            binding.layoutEpisodesHeader.visibility = View.GONE
        }
    }

    // ---------------- Render básico (sin TMDb) ----------------

    private fun renderFromMedia() {
        binding.txtTitle.text = media.title
        binding.txtOverview.text = if (media.overview.isNullOrEmpty()) "Sin sinopsis disponible." else media.overview

        // Backdrop (imagen destacada)
        Glide.with(this)
            .load(
                when {
                    !media.backdropUrl.isNullOrEmpty() -> media.backdropUrl
                    !media.posterUrl.isNullOrEmpty() -> media.posterUrl
                    else -> media.rawLogo
                }
            )
            .transition(DrawableTransitionOptions.withCrossFade())
            .placeholder(R.drawable.bg_placeholder)
            .into(binding.imgBackdrop)

        val rating = media.rating ?: 0.0
        binding.txtRatingBadge.visibility = if (rating > 0.0) View.VISIBLE else View.GONE
        if (rating > 0.0) binding.txtRatingBadge.text = String.format("★ %.1f", rating)

        binding.badgeFormat.text = "SERIE"

        val epSeasons = media.episodes.map { it.season }.distinct().size
        val epCount = media.episodes.size
        binding.txtMetaLine.text = buildString {
            val year = media.releaseDate?.take(4)
            if (!year.isNullOrEmpty()) append("$year · ")
            append(if (epSeasons > 0) "$epSeasons temporadas" else "Serie")
            if (epCount > 0) append(" · $epCount episodios")
        }

        // Tráiler
        binding.btnTrailer.visibility = if (media.trailerUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.btnTrailer.setOnClickListener { showTrailerModal(media.trailerUrl!!) }

        // Reparto (desde media.cast si ya lo trajimos antes)
        renderCast(media.cast)
    }

    private fun renderCast(cast: List<CastMember>) {
        val hasCast = cast.isNotEmpty()
        binding.rvCast.visibility = if (hasCast) View.VISIBLE else View.GONE
        binding.lblCast.visibility = if (hasCast) View.VISIBLE else View.GONE
        if (hasCast) {
            binding.rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.rvCast.adapter = CineCastAdapter(cast)
        }
    }

    // ---------------- Render con ficha TMDb completa ----------------

    private fun renderDetails(d: TvDetails) {
        if (!d.localTitle.isNullOrBlank()) binding.txtTitle.text = d.localTitle
        if (!d.originalTitle.isNullOrBlank()) {
            binding.txtOriginalTitle.text = d.originalTitle
            binding.txtOriginalTitle.visibility = View.VISIBLE
        } else {
            binding.txtOriginalTitle.visibility = View.GONE
        }
        if (!d.overview.isNullOrBlank()) binding.txtOverview.text = d.overview

        if (!d.backdropUrl.isNullOrBlank()) {
            Glide.with(this).load(d.backdropUrl).transition(DrawableTransitionOptions.withCrossFade()).into(binding.imgBackdrop)
        }
        val r = d.rating
        if (r != null && r > 0.0) {
            binding.txtRatingBadge.visibility = View.VISIBLE
            binding.txtRatingBadge.text = String.format("★ %.1f", r)
        }

        binding.badgeFormat.text = formatLabel(d)

        // Línea meta
        binding.txtMetaLine.text = buildString {
            val y = yearLabel(d)
            if (y.isNotBlank()) append("$y · ")
            append(formatLabel(d).lowercase().replaceFirstChar { it.uppercase() })
            if (d.numberOfSeasons > 0) append(" · ${d.numberOfSeasons} temporadas")
            if (d.numberOfEpisodes > 0) append(" · ${d.numberOfEpisodes} episodios")
        }

        renderCast(if (d.cast.isNotEmpty()) d.cast else media.cast)

        // --- Información general ---
        val gen = binding.containerGeneral
        gen.removeAllViews()
        addInfoRow(gen, "Título original", d.originalTitle)
        addInfoRow(gen, "Título local", d.localTitle)
        addInfoRow(gen, "Año de estreno", yearLabel(d))
        addInfoRow(gen, "Formato", formatLabel(d))
        if (d.numberOfSeasons > 0) addInfoRow(gen, "Temporadas", "${d.numberOfSeasons} temporadas")
        if (d.numberOfEpisodes > 0) addInfoRow(gen, "Episodios", "${d.numberOfEpisodes} episodios")
        addInfoRow(gen, "País de origen", d.originCountries.joinToString(", "))
        addInfoRow(gen, "Idioma original", d.originalLanguage)
        addInfoRow(gen, "Género", d.genres.joinToString(", "))
        addInfoRow(gen, "Duración", d.episodeRuntime?.let { "~$it min por episodio" })
        addInfoRow(gen, "Plataforma / canal", d.networks.joinToString(", "))

        // --- Equipo y créditos ---
        val eq = binding.containerEquipo
        eq.removeAllViews()
        addInfoRow(eq, "Showrunner / Creador", d.creators.joinToString(", "))
        addInfoRow(eq, "Dirección", d.director)
        addInfoRow(eq, "Guion", d.writers.joinToString(", "))
        addInfoRow(eq, "Producción", d.productionCompanies.joinToString(", "))
        addInfoRow(eq, "Música / Banda sonora", d.musicComposer)
        addInfoRow(eq, "Fotografía", d.cinematographer)

        // --- Contenido ---
        val cont = binding.containerContenido
        cont.removeAllViews()
        addInfoRow(cont, "Calificación por edad", d.ageRating)
        addInfoRow(cont, "Advertencias", d.advisories.joinToString(" · "))
    }

    private fun formatLabel(d: TvDetails?): String {
        val genres = (d?.genres ?: emptyList()).joinToString(" ").lowercase()
        return when {
            genres.contains("documental") || genres.contains("documentary") -> "DOCUMENTAL"
            d != null && d.numberOfSeasons <= 1 && d.numberOfEpisodes in 1..13 -> "MINISERIE"
            else -> "SERIE"
        }
    }

    private fun yearLabel(d: TvDetails?): String {
        val first = d?.firstAirDate?.take(4)
        val last = d?.lastAirDate?.take(4)
        return when {
            first.isNullOrBlank() -> ""
            last.isNullOrBlank() || last == first -> first
            else -> "$first–$last"
        }
    }

    private fun addInfoRow(container: LinearLayout, label: String, value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isBlank() || v == "null") return
        if (container.childCount > 0) {
            container.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(0x14FFFFFF)
                }
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val lbl = TextView(this).apply {
            text = label.uppercase()
            setTextColor(0xFF8A8A93.toInt())
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        val valTv = TextView(this).apply {
            text = v
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(0, dp(3), 0, 0)
        }
        row.addView(lbl)
        row.addView(valTv)
        container.addView(row)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- Reproducción ----------------

    private fun playFirst() {
        val url = media.episodes.firstOrNull()?.url?.ifBlank { null }
            ?: media.urls.firstOrNull()
            ?: media.url
        if (url.isNullOrBlank()) {
            android.widget.Toast.makeText(this, "Sin enlace de reproducción", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        playUrl(url, media.title)
    }

    private fun playEpisode(episode: Episode) {
        playUrl(episode.url, "${media.title} · S${episode.season}E${episode.episodeNumber}: ${episode.title}")
    }

    private fun playUrl(url: String, title: String) {
        // Anuncio recompensado obligatorio: solo reproduce si se ve completo.
        RewardGate.requireAdThen(this) { launchPlayback(url, title) }
    }

    private fun launchPlayback(url: String, title: String) {
        if (CineRepository.isDirectStreamUrl(url)) {
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putExtra("channelName", title)
                    putExtra("channelUrl", url)
                    putStringArrayListExtra("allSources", media.urls)
                    putExtra("cineMedia", media)
                }
            )
        } else {
            startActivity(
                Intent(this, WebVideoPlayerActivity::class.java).apply {
                    putExtra("channelName", title)
                    putExtra("channelUrl", url)
                    putStringArrayListExtra("allSources", media.urls)
                }
            )
        }
    }

    // ---------------- Tráiler ----------------

    private fun showTrailerModal(url: String) {
        val videoId = extractYoutubeId(url) ?: return
        val embedUrl = "https://www.youtube.com/embed/$videoId?autoplay=1"

        val dialogView = layoutInflater.inflate(R.layout.dialog_trailer_player, null)
        val webView = dialogView.findViewById<android.webkit.WebView>(R.id.webViewTrailer)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnTrailerClose)
        val txtTitle = dialogView.findViewById<android.widget.TextView>(R.id.txtTrailerTitle)

        txtTitle.text = "Tráiler: ${media.title}"
        webView.settings.javaScriptEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = android.webkit.WebViewClient()
        webView.loadUrl(embedUrl)

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setView(dialogView)
            .create()
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { webView.loadUrl("about:blank") }
        dialog.show()
    }

    private fun extractYoutubeId(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            if (url.contains("youtu.be")) uri.lastPathSegment else uri.getQueryParameter("v")
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Compartir ----------------

    private fun showShareOptions() {
        AlertDialog.Builder(this)
            .setTitle("Compartir")
            .setItems(arrayOf("Compartir como texto 📄", "Mostrar código QR 📲")) { _, which ->
                val link = if (media.urls.isNotEmpty()) media.urls[0] else media.url
                when (which) {
                    0 -> shareText(link)
                    1 -> QrHelper.showQrDialog(this, media.title, link)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun shareText(link: String) {
        val text = buildString {
            append("🎬 ¡Mira esto en IPTV Player PRO!\n\n")
            append("📺 Título: ${media.title}\n\n")
            append("📝 Sinopsis: ${media.overview ?: "Sin sinopsis disponible."}\n\n")
            append("📲 ¡Descarga la aplicación IPTV Player PRO para ver películas, series y TV en vivo gratis!")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Compartir"))
    }
}
