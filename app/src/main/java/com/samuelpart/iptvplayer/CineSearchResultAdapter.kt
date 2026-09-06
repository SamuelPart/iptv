package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemCineSearchResultBinding

/**
 * Resultados del buscador de cine: cuadricula de posters con el TITULO DEBAJO
 * del poster (no superpuesto), estilo catalogo limpio.
 */
class CineSearchResultAdapter(
    private var mediaList: List<CineMedia>,
    private val onMediaClick: (CineMedia) -> Unit
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

            binding.root.setOnClickListener { onMediaClick(media) }
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
