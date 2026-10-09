package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Fila horizontal de plataformas: logo oficial (TMDB) + nombre. Al tocar
 *  abre el catalogo exclusivo de esa plataforma. */
class PlatformRowAdapter(private val onTap: (PlatformCatalog.Platform) -> Unit) :
    RecyclerView.Adapter<PlatformRowAdapter.Holder>() {

    private val items = PlatformCatalog.ALL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_platform_logo, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]
        holder.txtName.text = p.name
        val logo = PlatformCatalog.logoByKey[p.key]
        if (logo != null) {
            Glide.with(holder.img).load(logo).fitCenter()
                .placeholder(R.drawable.bg_tile_glass).into(holder.img)
            holder.txtLetter.visibility = View.GONE
        } else {
            holder.img.setImageResource(R.drawable.bg_tile_glass)
            holder.txtLetter.text = p.name.take(1)
            holder.txtLetter.visibility = View.VISIBLE
        }
        holder.itemView.setOnClickListener { onTap(p) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgPlatformLogo)
        val txtLetter: TextView = v.findViewById(R.id.txtPlatformLetter)
        val txtName: TextView = v.findViewById(R.id.txtPlatformName)
    }
}
