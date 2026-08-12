package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemChannelBinding

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.txtChannelName.text = channel.name

            // Load logo using Glide with placeholder and error fallbacks
            Glide.with(binding.imgLogo.context)
                .load(channel.logoUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_placeholder)
                .error(R.drawable.ic_tv)
                .fallback(R.drawable.ic_tv)
                .into(binding.imgLogo)

            binding.root.setOnClickListener {
                onChannelClick(channel)
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
        holder.bind(channels[position])
    }

    override fun getItemCount(): Int = channels.size

    fun updateList(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }
}
