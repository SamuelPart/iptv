package com.samuelpart.iptvplayer

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemCineMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CineMediaAdapter(
    private var mediaList: List<CineMedia>,
    private val onMediaClick: (CineMedia) -> Unit,
    private val isFavorite: ((CineMedia) -> Boolean)? = null,
    private val onFavoriteToggle: ((CineMedia) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Items already asked to TMDB this session (avoids refetch spam while scrolling). */
    private val tmdbRequested = mutableSetOf<String>()

    /** Lista real + marcadores de anuncio. */
    private var displayList: List<Any?> = emptyList()

    init { rebuildDisplayList() }

    private fun rebuildDisplayList() {
        displayList = NativeAds.interleaveWithAds(mediaList)
    }

    fun isAdAt(position: Int): Boolean = NativeAds.isAdMarker(displayList.getOrNull(position))

    inner class AdViewHolder(val slot: android.widget.FrameLayout) :
        RecyclerView.ViewHolder(slot) {
        private var loaded = false
        fun ensureLoaded() {
            if (loaded) return
            loaded = true
            val activity = slot.context as? Activity ?: return
            NativeAds.attachRecycler(activity, slot, NativeAds.VARIANT_COMPACT) {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) notifyItemChanged(p)
            }
        }
    }

    inner class CineMediaViewHolder(private val binding: ItemCineMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(media: CineMedia) {
            binding.root.springPress() // iPhone-style bounce on tap
            binding.root.tag = media.url // holder identity for lazy TMDB callbacks
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

            // Badge NUEVO champagne para estrenos (2024+)
            binding.txtCineNewBadge.visibility =
                if ((media.releaseDate?.take(4)?.toIntOrNull() ?: 0) >= 2024) View.VISIBLE else View.GONE

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

                // Poster a la vista: si este item no tiene imagen y TMDB no lo resolvio
                // todavia, pedirlo al vuelo (solo items visibles, asi no se satura la API
                // como antes con el prefetch de las 9.485 fichas).
                val isMediaItem = media.type == "movie" || media.type == "series"
                if (imgToLoad.isNullOrEmpty() && media.tmdbId == null && isMediaItem &&
                    media.url !in tmdbRequested
                ) {
                    tmdbRequested.add(media.url)
                    CoroutineScope(Dispatchers.IO).launch {
                        try { CineRepository.fetchTmdMetadata(media) } catch (_: Exception) { }
                        withContext(Dispatchers.Main) {
                            // Only paint if this recycled holder is still showing the same item
                            if (binding.root.tag == media.url) {
                                val recovered = if (!media.posterUrl.isNullOrEmpty()) media.posterUrl else media.rawLogo
                                if (!recovered.isNullOrEmpty()) {
                                    Glide.with(binding.imgPoster.context)
                                        .load(recovered)
                                        .transition(DrawableTransitionOptions.withCrossFade())
                                        .error(fallbackIcon)
                                        .into(binding.imgPoster)
                                }
                                val refreshedRating = media.rating ?: 0.0
                                if (refreshedRating > 0.0) {
                                    binding.txtRatingBadge.visibility = View.VISIBLE
                                    binding.txtRatingBadge.text = String.format("★ %.1f", refreshedRating)
                                }
                            }
                        }
                    }
                }
            }

            // Favorites star overlay
            if (isFavorite != null) {
                binding.imgCineFav.visibility = View.VISIBLE
                val fav = isFavorite?.invoke(media) == true
                if (fav) {
                    binding.imgCineFav.setImageResource(R.drawable.ic_ios_star_fill)
                    binding.imgCineFav.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFD60A.toInt())
                } else {
                    binding.imgCineFav.setImageResource(R.drawable.ic_ios_star)
                    binding.imgCineFav.imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
                }
                binding.imgCineFav.setOnClickListener {
                    onFavoriteToggle?.invoke(media)
                    val nowFav = isFavorite?.invoke(media) == true
                    if (nowFav) {
                        binding.imgCineFav.setImageResource(R.drawable.ic_ios_star_fill)
                        binding.imgCineFav.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFD60A.toInt())
                    } else {
                        binding.imgCineFav.setImageResource(R.drawable.ic_ios_star)
                        binding.imgCineFav.imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
                    }
                    android.animation.ObjectAnimator.ofFloat(it, "scaleX", 0.6f, 1f).apply { duration = 280; start() }
                    android.animation.ObjectAnimator.ofFloat(it, "scaleY", 0.6f, 1f).apply { duration = 280; start() }
                }
            } else {
                binding.imgCineFav.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onMediaClick(media)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (NativeAds.isAdMarker(displayList.getOrNull(position))) return NativeAds.GRID_AD_TYPE
        return 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == NativeAds.GRID_AD_TYPE) {
            return AdViewHolder(NativeAds.createAdSlot(parent))
        }
        val binding = ItemCineMediaBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CineMediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AdViewHolder) {
            holder.ensureLoaded()
            return
        }
        animateEntrance(holder.itemView, position)
        (holder as CineMediaViewHolder).bind(mediaList[contentIndexAt(position)])
    }

    override fun getItemCount(): Int = displayList.size

    private fun contentIndexAt(position: Int): Int {
        var real = 0
        for (i in 0 until position) {
            if (!NativeAds.isAdMarker(displayList.getOrNull(i))) real++
        }
        return real
    }


    /** iPhone/PS stagger: each new card rises + fades in as it gets scrolled into view. */
    private var lastAnimatedPosition = -1

    private fun animateEntrance(view: View, position: Int) {
        if (position <= lastAnimatedPosition) return
        lastAnimatedPosition = position
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 44f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction(null)
            .start()
    }

    fun updateList(newList: List<CineMedia>) {
        lastAnimatedPosition = -1
        mediaList = newList
        rebuildDisplayList()
        notifyDataSetChanged()
    }
}
