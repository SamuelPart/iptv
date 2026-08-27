package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemCineMediaBinding

class CineMediaAdapter(
    private var mediaList: List<CineMedia>,
    private val onMediaClick: (CineMedia) -> Unit
) : RecyclerView.Adapter<CineMediaAdapter.CineMediaViewHolder>() {

    inner class CineMediaViewHolder(private val binding: ItemCineMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(media: CineMedia) {
            binding.root.springPress() // iPhone-style bounce on tap
            binding.txtCineTitle.text = media.title
            
            val isMovie = (media.type == "movie")
            binding.txtCineSubtitle.text = if (isMovie) "Película" else "Serie de TV"
            
            // Format rating text
            val ratingVal = media.rating ?: 0.0
            if (ratingVal > 0.0) {
                binding.txtRatingBadge.visibility = View.VISIBLE
                binding.txtRatingBadge.text = String.format("★ %.1f", ratingVal)
            } else {
                binding.txtRatingBadge.visibility = View.GONE
            }

            // Load poster with Glide
            val fallbackIcon = if (isMovie) R.drawable.ic_ios_movie else R.drawable.ic_ios_tv
            
            // Check if the item is a Spider-Man Multiverse movie/series to display the gorgeous GIF!
            val lowerTitle = media.title.lowercase()
            val isSpidermanMultiverse = lowerTitle.contains("spider") && 
                (lowerTitle.contains("multiver") || lowerTitle.contains("universo") || lowerTitle.contains("spider-verse"))
            
            if (isSpidermanMultiverse) {
                Glide.with(binding.imgPoster.context)
                    .asGif()
                    .load(R.drawable.spiderman_multiverse)
                    .placeholder(R.drawable.bg_placeholder)
                    .error(fallbackIcon)
                    .listener(object : com.bumptech.glide.request.RequestListener<com.bumptech.glide.load.resource.gif.GifDrawable> {
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<com.bumptech.glide.load.resource.gif.GifDrawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            return false
                        }

                        override fun onResourceReady(
                            resource: com.bumptech.glide.load.resource.gif.GifDrawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<com.bumptech.glide.load.resource.gif.GifDrawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            resource.setLoopCount(1) // Play the Spider-Man animation exactly once per search!
                            return false
                        }
                    })
                    .into(binding.imgPoster)
            } else {
                val imgToLoad = if (!media.posterUrl.isNullOrEmpty()) media.posterUrl else media.rawLogo
                Glide.with(binding.imgPoster.context)
                    .load(imgToLoad)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_placeholder)
                    .error(fallbackIcon)
                    .fallback(fallbackIcon)
                    .into(binding.imgPoster)
            }

            binding.root.setOnClickListener {
                onMediaClick(media)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CineMediaViewHolder {
        val binding = ItemCineMediaBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CineMediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CineMediaViewHolder, position: Int) {
        holder.bind(mediaList[position])
    }

    override fun getItemCount(): Int = mediaList.size

    fun updateList(newList: List<CineMedia>) {
        mediaList = newList
        notifyDataSetChanged()
    }
}
