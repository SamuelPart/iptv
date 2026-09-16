package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * Adapter de la galeria de fondos: muestra el backdrop (o poster) de cada
 * titulo en celdas 16:9 y notifica el toque para abrir el visor.
 */
class WallpaperAdapter(
    private val onClick: (CineMedia) -> Unit
) : RecyclerView.Adapter<WallpaperAdapter.Holder>() {

    private val items = mutableListOf<CineMedia>()

    fun submit(list: List<CineMedia>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun imageUrlOf(m: CineMedia): String =
        m.backdropUrl ?: m.posterUrl ?: m.rawLogo

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wallpaper, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = items[position]
        Glide.with(holder.img.context)
            .load(imageUrlOf(m))
            .centerCrop()
            .placeholder(R.drawable.bg_tile_glass)
            .into(holder.img)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgWallpaperTile)
    }
}
