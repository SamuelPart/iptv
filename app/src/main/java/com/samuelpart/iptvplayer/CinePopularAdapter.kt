package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Rail horizontal "POPULAR": tarjetas con sello de calidad + play mint/champagne. */
class CinePopularAdapter(
    private val items: List<CineMedia>,
    private val onClick: (CineMedia) -> Unit
) : RecyclerView.Adapter<CinePopularAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_cine_popular, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val v = holder.itemView
        v.findViewById<ImageView>(R.id.imgPopPoster)?.let { iv ->
            Glide.with(iv).load(m.posterUrl).centerCrop().into(iv)
        }
        v.findViewById<TextView>(R.id.txtPopTitle)?.text = m.title
        val badge = when (Math.abs(m.title.hashCode()) % 3) {
            0 -> "HD"; 1 -> "4K"; else -> "HQ"
        }
        v.findViewById<TextView>(R.id.txtPopQuality)?.text = badge

        val rt = m.rating
        val views = (Math.abs(m.title.hashCode()) % 30) + 8
        v.findViewById<TextView>(R.id.txtPopMeta)?.text =
            if (rt != null && rt > 0) "★ ${"%.1f".format(rt)} · ${views}M+ Views"
            else "${views}M+ Views"
        v.setOnClickListener { onClick(m) }
        v.findViewById<View>(R.id.btnPopPlay)?.setOnClickListener { onClick(m) }
    }
}
