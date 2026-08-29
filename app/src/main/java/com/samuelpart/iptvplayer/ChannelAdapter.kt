package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemChannelBinding

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelClick: (Channel) -> Unit,
    private val isFavorite: ((Channel) -> Boolean)? = null,
    private val onFavoriteToggle: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.root.springPress() // iPhone-style bounce on tap
            binding.txtChannelName.text = channel.name

            // Load logo using Glide with placeholder and error fallbacks
            Glide.with(binding.imgLogo.context)
                .load(channel.logoUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_placeholder)
                .error(R.drawable.ic_tv)
                .fallback(R.drawable.ic_tv)
                .into(binding.imgLogo)

            // Favorites star (visible when callbacks are provided)
            if (isFavorite != null) {
                binding.imgChannelFav.visibility = android.view.View.VISIBLE
                updateFavIcon(isFavorite(channel))
                binding.imgChannelFav.setOnClickListener {
                    onFavoriteToggle?.invoke(channel)
                    val nowFav = isFavorite(channel)
                    updateFavIcon(nowFav)
                    android.animation.ObjectAnimator.ofFloat(it, "scaleX", 0.6f, 1f).apply { duration = 280; start() }
                    android.animation.ObjectAnimator.ofFloat(it, "scaleY", 0.6f, 1f).apply { duration = 280; start() }
                }
            } else {
                binding.imgChannelFav.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener {
                onChannelClick(channel)
            }

            Unit
        }

        private fun updateFavIcon(fav: Boolean) {
            if (fav) {
                binding.imgChannelFav.setImageResource(R.drawable.ic_ios_star_fill)
                binding.imgChannelFav.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFD60A.toInt())
            } else {
                binding.imgChannelFav.setImageResource(R.drawable.ic_ios_star)
                binding.imgChannelFav.imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        animateEntrance(holder.itemView, position)
        holder.bind(channels[position])
    }

    override fun getItemCount(): Int = channels.size


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

    fun updateList(newChannels: List<Channel>) {
        lastAnimatedPosition = -1
        channels = newChannels
        notifyDataSetChanged()
    }
}
