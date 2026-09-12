package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemFavoriteBinding

/** Horizontal Home strip for Favorites (channels + cine items in one list). */
class FavoriteAdapter(
    private var items: List<FavoritesManager.FavoriteItem>,
    private val onClick: (FavoritesManager.FavoriteItem) -> Unit,
    private val onRemove: (FavoritesManager.FavoriteItem) -> Unit
) : RecyclerView.Adapter<FavoriteAdapter.FavViewHolder>() {

    private var lastAnimatedPosition = -1

    inner class FavViewHolder(val binding: ItemFavoriteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FavoritesManager.FavoriteItem) {
            binding.root.springPress()

            if (item.isChannel) {
                val ch = item.channel!!
                binding.txtFavTitle.text = ch.name
                binding.txtFavType.text = "TV"
                Glide.with(binding.imgFavPoster.context)
                    .load(ch.logoUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_placeholder)
                    .error(R.drawable.ic_ios_tv)
                    .fallback(R.drawable.ic_ios_tv)
                    .into(binding.imgFavPoster)
            } else {
                val media = item.media!!
                binding.txtFavTitle.text = media.title
                binding.txtFavType.text = if (media.type == "movie") "CINE" else "SERIE"
                val imgToLoad = if (!media.posterUrl.isNullOrEmpty()) media.posterUrl else media.rawLogo
                Glide.with(binding.imgFavPoster.context)
                    .load(imgToLoad)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_placeholder)
                    .error(R.drawable.ic_ios_movie)
                    .fallback(R.drawable.ic_ios_movie)
                    .into(binding.imgFavPoster)
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onRemove(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavViewHolder {
        val binding = ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FavViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavViewHolder, position: Int) {
        if (position > lastAnimatedPosition) {
            lastAnimatedPosition = position
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 0f
            holder.itemView.translationX = 60f
            holder.itemView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<FavoritesManager.FavoriteItem>) {
        lastAnimatedPosition = -1
        items = newItems
        notifyDataSetChanged()
    }
}
