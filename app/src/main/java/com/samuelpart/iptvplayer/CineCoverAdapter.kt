package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Carrusel 3D del Destacado: tarjeta grande centrada con info flotante. */
class CineCoverAdapter(
    private val items: List<CineMedia>,
    private val onClick: (CineMedia) -> Unit
) : RecyclerView.Adapter<CineCoverAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_cine_coverflow, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val v = holder.itemView
        v.cameraDistance = 6000f * v.resources.displayMetrics.density
        v.findViewById<ImageView>(R.id.imgCoverPoster)?.let { iv ->
            Glide.with(iv).load(m.posterUrl).centerCrop().into(iv)
            iv.setOnClickListener { onClick(m) }
        }
        v.findViewById<TextView>(R.id.txtCoverTitle)?.text = m.title
        val yr = m.releaseDate?.take(4) ?: ""
        val rt = m.rating
        v.findViewById<TextView>(R.id.txtCoverMeta)?.text = when {
            rt != null && rt > 0 && yr.isNotEmpty() -> "★ ${"%.1f".format(rt)}  ·  Estreno: $yr"
            rt != null && rt > 0 -> "★ ${"%.1f".format(rt)}"
            yr.isNotEmpty() -> "Estreno: $yr"
            else -> ""
        }
        v.findViewById<TextView>(R.id.btnCoverVer)?.setOnClickListener { onClick(m) }
    }
}
