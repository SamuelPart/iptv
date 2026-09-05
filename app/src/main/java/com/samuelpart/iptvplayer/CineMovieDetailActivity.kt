package com.samuelpart.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.samuelpart.iptvplayer.databinding.ActivityCineMovieDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detalle de película — lienzo blanco rebuild (mock "Venom"):
 * hero full-bleed + play central, título gigante + subtítulo,
 * chips (género | 16+ | ★ | ♥), Story Line con "More", Star Cast circular.
 * Cero player embebido: el play abre SIEMPRE pantalla completa.
 */
class CineMovieDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineMovieDetailBinding
    private lateinit var media: CineMedia
    private var storyExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        @Suppress("DEPRECATION")
        media = (intent.getSerializableExtra("media") as? CineMedia) ?: run {
            finish()
            return
        }
        TasteProfile.recordOpen(this, media)

        binding.btnDetailBack.setOnClickListener { finish() }
        binding.btnHeroPlay.setOnClickListener { playNow() }

        paintHero()
        paintHeader()
        paintChips()
        paintStory()
        paintCast()
        paintFavorite()

        // Enriquecimiento TMDB en segundo plano (cast + backdrop/poster)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try { CineRepository.fetchTmdCredits(media) } catch (_: Exception) { }
            }
            paintCast()
        }
    }

    private fun paintHero() {
        val hero = media.backdropUrl ?: media.posterUrl
        Glide.with(this).load(hero).centerCrop().into(binding.imgDetailHero)
        if (!media.platformLogoUrl.isNullOrBlank()) {
            binding.imgDetailPlatform.visibility = View.VISIBLE
            Glide.with(this).load(media.platformLogoUrl).into(binding.imgDetailPlatform)
        }
    }

    private fun paintHeader() {
        binding.txtDetailTitle.text = media.title
        val year = media.releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4) ?: ""
        val parts = listOfNotNull(
            media.group.takeIf { it.isNotBlank() },
            year.takeIf { it.isNotBlank() }
        )
        binding.txtDetailSubtitle.text =
            if (parts.isEmpty()) "Película" else parts.joinToString("  ·  ")
    }

    private fun paintChips() {
        val genre = media.group
            .substringAfterLast(" ")
            .ifBlank { media.group.ifBlank { "Cine" } }
        binding.chipGenreDetail.text = genre.uppercase()
        binding.chipAgeDetail.text = "16+"
        binding.txtDetailRating.text = media.rating
            ?.takeIf { it > 0 }
            ?.let { String.format("%.1f", it) } ?: "—"
    }

    private fun paintStory() {
        val story = media.overview?.takeIf { it.isNotBlank() }
            ?: "Aún no tenemos sinopsis en español para este título."
        binding.txtDetailStory.text = story
        binding.txtDetailStory.maxLines = 5
        // Muestra "More" si el texto pasa de 5 líneas
        binding.txtDetailStory.post {
            if (binding.txtDetailStory.lineCount >= 5) {
                binding.btnStoryMore.visibility = View.VISIBLE
            }
        }
        binding.btnStoryMore.setOnClickListener {
            storyExpanded = !storyExpanded
            binding.txtDetailStory.maxLines = if (storyExpanded) 200 else 5
            binding.btnStoryMore.text = if (storyExpanded) "Less" else "More"
        }
    }

    private fun paintCast() {
        val cast = media.cast
        if (cast.isEmpty()) {
            binding.txtCastHeader.visibility = View.GONE
            binding.rvDetailCast.visibility = View.GONE
            return
        }
        binding.txtCastHeader.visibility = View.VISIBLE
        binding.rvDetailCast.visibility = View.VISIBLE
        binding.rvDetailCast.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvDetailCast.adapter = CastCircleAdapter(cast)
    }

    private fun paintFavorite() {
        fun refresh() {
            val fav = FavoritesManager.isFavorite(this, media.url)
            binding.btnDetailFav.setImageResource(
                if (fav) R.drawable.ic_heart_fill_modern else R.drawable.ic_heart_modern
            )
        }
        refresh()
        binding.btnDetailFav.setOnClickListener {
            FavoritesManager.toggleMedia(this, media)
            refresh()
        }
    }

    // ---------------- Play ----------------

    private fun playNow() {
        val streamUrl = if (media.urls.isNotEmpty()) media.urls[0] else media.url
        if (CineRepository.looksLikeDirectVideo(streamUrl)) {
            openPlayer(streamUrl, null, null)
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

    private fun openPlayer(url: String, referer: String?, ua: String?) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra("channelName", media.title)
                putExtra("channelUrl", url)
                putStringArrayListExtra("allSources", media.urls)
                putExtra("cineMedia", media)
                if (referer != null) putExtra("streamReferer", referer)
                if (ua != null) putExtra("streamUserAgent", ua)
            }
        )
    }

    // ---------------- Cast circulo ----------------

    private class CastCircleAdapter(
        private val items: List<CastMember>
    ) : RecyclerView.Adapter<CastCircleAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val face: ImageView = view.findViewById(R.id.imgCastFace)
            val name: TextView = view.findViewById(R.id.txtCastName)
            val role: TextView = view.findViewById(R.id.txtCastRole)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cine_cast_circle, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            h.name.text = c.name
            h.role.text = c.character
            Glide.with(h.face).load(c.profileUrl)
                .placeholder(R.drawable.bg_circle_glass)
                .centerCrop()
                .into(h.face)
        }
    }
}
