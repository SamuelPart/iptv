package com.samuelpart.iptvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ActivityCineMovieDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ficha de PELÍCULA rediseñada desde cero (mismo estilo que la ficha de serie):
 * cabecera con backdrop, póster, título original/local, año, duración, sinopsis,
 * equipo completo (dirección, guion, producción, música, fotografía, reparto),
 * datos generales (país, idioma, géneros, duración, plataforma) y contenido
 * (calificación por edad + advertencias). La regla de reproducción NO se toca:
 * enlace directo -> VLC, página/iframe/embed -> WebPlayer.
 */
class CineMovieDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineMovieDetailBinding
    private lateinit var media: CineMedia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        media = (intent.getSerializableExtra("media") as? CineMedia) ?: run {
            finish()
            return
        }
        TasteProfile.recordOpen(this, media)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { playNow() }
        binding.btnShare.setOnClickListener { showShareOptions() }

        renderFromMedia()
        paintFavorite()

        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) { CineRepository.fetchMovieDetails(media) }
            withContext(Dispatchers.IO) {
                if (media.trailerUrl.isNullOrEmpty()) CineRepository.fetchTmdTrailer(media)
                if (media.platformLogoUrl.isNullOrBlank()) CineRepository.fetchWatchProviders(media)
            }
            renderFromMedia()
            if (details != null) renderDetails(details)
            binding.progress.visibility = View.GONE
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

        binding.badgeFormat.text = "PELÍCULA"

        val year = media.releaseDate?.take(4).orEmpty()
        binding.txtMetaLine.text = buildString {
            if (year.isNotBlank()) append("$year · ")
            append("Película")
        }

        // Tráiler
        binding.btnTrailer.visibility = if (media.trailerUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.btnTrailer.setOnClickListener { showTrailerModal(media.trailerUrl!!) }

        // Plataforma (logo)
        if (!media.platformLogoUrl.isNullOrBlank()) {
            binding.imgPlatformLogo.visibility = View.VISIBLE
            Glide.with(this).load(media.platformLogoUrl).into(binding.imgPlatformLogo)
        } else {
            binding.imgPlatformLogo.visibility = View.GONE
        }

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

    private fun renderDetails(d: MovieDetails) {
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

        binding.badgeFormat.text = "PELÍCULA"

        // Línea meta: año · duración · género
        binding.txtMetaLine.text = buildString {
            val y = d.releaseDate?.take(4).orEmpty()
            if (y.isNotBlank()) append("$y · ")
            d.runtime?.let { append(formatRuntime(it) + " · ") }
            if (d.genres.isNotEmpty()) append(d.genres.joinToString(", "))
        }.ifBlank { "Película" }

        renderCast(if (d.cast.isNotEmpty()) d.cast else media.cast)

        // --- Información general ---
        val gen = binding.containerGeneral
        gen.removeAllViews()
        addInfoRow(gen, "Título original", d.originalTitle)
        addInfoRow(gen, "Título local", d.localTitle)
        addInfoRow(gen, "Año", d.releaseDate?.take(4))
        addInfoRow(gen, "Duración", d.runtime?.let { formatRuntime(it) })
        addInfoRow(gen, "País de origen", d.originCountries.joinToString(", "))
        addInfoRow(gen, "Idioma original", d.originalLanguage)
        addInfoRow(gen, "Género", d.genres.joinToString(", "))
        addInfoRow(gen, "Plataforma", media.platformName)

        // --- Equipo y créditos ---
        val eq = binding.containerEquipo
        eq.removeAllViews()
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

    private fun formatRuntime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
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

    // ---------------- Favorito ----------------

    private fun paintFavorite() {
        fun refresh() {
            val fav = FavoritesManager.isFavorite(this, media.url)
            binding.btnFav.setImageResource(
                if (fav) R.drawable.ic_heart_fill_modern else R.drawable.ic_heart_modern
            )
        }
        refresh()
        binding.btnFav.setOnClickListener {
            FavoritesManager.toggleMedia(this, media)
            refresh()
        }
    }

    // ---------------- Play (regla SIN tocar: directo -> VLC, web -> WebPlayer) ----------------

    private fun playNow() {
        val streamUrl = if (media.urls.isNotEmpty()) media.urls[0] else media.url
        // Anuncio recompensado obligatorio: solo reproduce si se ve completo.
        RewardGate.requireAdThen(this) { playStream(streamUrl) }
    }

    private fun playStream(streamUrl: String) {
        if (CineRepository.isDirectStreamUrl(streamUrl)) {
            openPlayer(streamUrl)
        } else {
            // PAGINA / EMBED / IFRAME -> WebPlayer fullscreen + BOT (cero extractor)
            startActivity(
                Intent(this, WebVideoPlayerActivity::class.java).apply {
                    putExtra("channelName", media.title)
                    putExtra("channelUrl", streamUrl)
                }
            )
        }
    }

    private fun openPlayer(url: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra("channelName", media.title)
                putExtra("channelUrl", url)
                putStringArrayListExtra("allSources", media.urls)
                putExtra("cineMedia", media)
            }
        )
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
