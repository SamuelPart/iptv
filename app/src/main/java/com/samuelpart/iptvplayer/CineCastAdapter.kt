package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemCineCastBinding

class CineCastAdapter(
    private var castList: List<CastMember>
) : RecyclerView.Adapter<CineCastAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemCineCastBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(member: CastMember) {
            binding.txtCastName.text = member.name
            binding.txtCastCharacter.text = member.character
            
            Glide.with(binding.imgCastProfile.context)
                .load(member.profileUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_placeholder)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.imgCastProfile)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCineCastBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(castList[position])
    }

    override fun getItemCount(): Int = castList.size

    fun updateList(newList: List<CastMember>) {
        castList = newList
        notifyDataSetChanged()
    }
}
