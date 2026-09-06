package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemCineSearchResultBinding

/**
 * Cuadricula de posters con el TITULO DEBAJO del poster (estilo buscador).
 * Se usa en: buscador de cine, "See all / Ver todo" y la cuadricula principal
 * de Cine. Soporta favoritos opcionales (estrella sobre el poster).
 */
class CineSearchResultAdapter(
    private var mediaList: List<CineMedia>,
    private val onMediaClick: (CineMedia) -> Unit,
    private val isFavorite: ((CineMedia) -> Boolean)? = null,
    private val onFavoriteToggle: ((CineMedia) -> Unit)? = null
) : RecyclerView.Adapter<CineSearchResultAdapter.VH>() {

    inner class VH(private val binding: ItemCineSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(media: CineMedia) {
            binding.txtTitle.text = media.title

            val isMovie = media.type == "movie"
            val year = media.releaseDate?.take(4)
            binding.txtSubtitle.text = buildString {
                append(if (isMovie) "Película" else "Serie")
                if (!year.isNullOrEmpty()) append(" · $year")
            }

            val fallback = if (isMovie) R.drawable.ic_ios_movie else R.drawable.ic_ios_tv
            val img = if (!media.posterUrl.isNullOrEmpty()) media.posterUrl else media.rawLogo
            Glide.with(binding.imgPoster.context)
                .load(img)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_placeholder)
                .error(fallback)
                .fallback(fallback)
                .into(binding.imgPoster)

            // Favoritos opcionales (solo en la cuadricula principal de Cine)
            if (isFavorite != null && onFavoriteToggle != null) {
                binding.imgFav.visibility = View.VISIBLE
                paintFav(binding, isFavorite.invoke(media))
                binding.imgFav.setOnClickListener {
                    onFavoriteToggle.invoke(media)
                    paintFav(binding, isFavorite.invoke(media))
                }
            } else {
                binding.imgFav.visibility = View.GONE
            }

            binding.root.setOnClickListener { onMediaClick(media) }
        }

        private fun paintFav(binding: ItemCineSearchResultBinding, fav: Boolean) {
            if (fav) {
                binding.imgFav.setImageResource(R.drawable.ic_ios_star_fill)
                binding.imgFav.imageTintList =
                    android.content.res.ColorStateList.valueOf(0xFFFFD60A.toInt())
            } else {
                binding.imgFav.setImageResource(R.drawable.ic_ios_star)
                binding.imgFav.imageTintList =
                    android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(
            ItemCineSearchResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(mediaList[position])

    override fun getItemCount(): Int = mediaList.size

    fun updateList(list: List<CineMedia>) {
        mediaList = list
        notifyDataSetChanged()
    }
}
