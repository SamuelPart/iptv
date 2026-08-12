package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemCineEpisodeBinding

class CineEpisodeAdapter(
    private var episodes: List<Episode>,
    private val onEpisodeClick: (Episode) -> Unit
) : RecyclerView.Adapter<CineEpisodeAdapter.EpisodeViewHolder>() {

    inner class EpisodeViewHolder(private val binding: ItemCineEpisodeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(episode: Episode) {
            binding.txtEpisodeTitle.text = episode.title
            binding.txtEpisodeMeta.text = "Temporada ${episode.season}  •  Episodio ${episode.episodeNumber}"

            // Load episode logo or show thumbnail using Glide
            val imgToLoad = if (!episode.stillUrl.isNullOrEmpty()) episode.stillUrl else episode.rawLogo
            Glide.with(binding.imgEpisodeThumb.context)
                .load(imgToLoad)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_placeholder)
                .error(R.drawable.ic_movie)
                .fallback(R.drawable.ic_movie)
                .into(binding.imgEpisodeThumb)

            binding.root.setOnClickListener {
                onEpisodeClick(episode)
            }
            binding.btnPlayEpisode.setOnClickListener {
                onEpisodeClick(episode)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemCineEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(episodes[position])
    }

    override fun getItemCount(): Int = episodes.size

    fun updateList(newList: List<Episode>) {
        episodes = newList
        notifyDataSetChanged()
    }
}
