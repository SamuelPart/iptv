package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemContinueWatchingBinding

/** Horizontal Home strip for "Continue Watching" (channels + resumable cine with progress). */
class ContinueWatchingAdapter(
    private var items: List<ContinueWatchingManager.ResumeEntry>,
    private val onClick: (ContinueWatchingManager.ResumeEntry) -> Unit,
    private val onRemove: (ContinueWatchingManager.ResumeEntry) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.CwViewHolder>() {

    private var lastAnimatedPosition = -1

    inner class CwViewHolder(val binding: ItemContinueWatchingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContinueWatchingManager.ResumeEntry) {
            binding.root.springPress()
            binding.txtCwTitle.text = item.title

            if (item.isChannel) {
                binding.txtCwSubtitle.text = "Canal de TV en vivo"
                binding.pbCwProgress.visibility = View.GONE
                binding.txtCwLive.visibility = View.VISIBLE
                Glide.with(binding.imgCwPoster.context)
                    .load(item.channelLogo)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_placeholder)
                    .error(R.drawable.ic_ios_tv)
                    .fallback(R.drawable.ic_ios_tv)
                    .into(binding.imgCwPoster)
            } else {
                val dur = item.durationMs
                val pos = item.positionMs
                binding.txtCwSubtitle.text = if (pos > 0) "Reanudar en ${formatClock(pos)}" else "Tocar para ver"
                binding.txtCwLive.visibility = View.GONE
                binding.pbCwProgress.visibility = View.VISIBLE
                binding.pbCwProgress.progress =
                    if (dur > 0) ((pos * 100) / dur).toInt().coerceIn(0, 100) else 0
                val imgToLoad = if (!item.media?.posterUrl.isNullOrEmpty()) item.media?.posterUrl else item.media?.rawLogo
                Glide.with(binding.imgCwPoster.context)
                    .load(imgToLoad)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_placeholder)
                    .error(R.drawable.ic_ios_movie)
                    .fallback(R.drawable.ic_ios_movie)
                    .into(binding.imgCwPoster)
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.btnCwPlay.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onRemove(item); true }
        }

        private fun formatClock(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CwViewHolder {
        val binding = ItemContinueWatchingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CwViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CwViewHolder, position: Int) {
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

    fun updateList(newItems: List<ContinueWatchingManager.ResumeEntry>) {
        lastAnimatedPosition = -1
        items = newItems
        notifyDataSetChanged()
    }
}
