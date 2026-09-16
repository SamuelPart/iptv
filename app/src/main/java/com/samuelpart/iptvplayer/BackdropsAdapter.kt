package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Fila horizontal de fondos del titulo dentro de la ficha (detalle). */
class BackdropsAdapter(
    private val urls: List<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<BackdropsAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_backdrop_tile, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val url = urls[position]
        Glide.with(holder.img.context)
            .load(url)
            .centerCrop()
            .placeholder(R.drawable.bg_tile_glass)
            .into(holder.img)
        holder.itemView.setOnClickListener { onClick(url) }
    }

    override fun getItemCount(): Int = urls.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgBackdropTile)
    }
}
