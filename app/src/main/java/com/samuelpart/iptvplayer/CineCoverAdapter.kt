package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Carrusel 3D del Destacado: tarjeta grande centrada con info flotante. */
class CineCoverAdapter(
    private val items: List<CineMedia>,
    private val onClick: (CineMedia) -> Unit
) : RecyclerView.Adapter<CineCoverAdapter.VH>() {

    private val tmdbRequested = mutableSetOf<String>()

    inner class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_cine_coverflow, parent, false)
    )

    override fun getItemCount() = items.size

    fun itemAt(pos: Int): CineMedia? = items.getOrNull(pos)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val v = holder.itemView
        v.tag = m.url
        v.cameraDistance = 6000f * v.resources.displayMetrics.density

        val iv = v.findViewById<ImageView>(R.id.imgCoverPoster)
        iv.setOnClickListener { onClick(m) }
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

        val src = if (!m.posterUrl.isNullOrEmpty()) m.posterUrl else m.rawLogo
        if (!src.isNullOrEmpty()) {
            Glide.with(iv).load(src).centerCrop().placeholder(R.drawable.bg_placeholder).into(iv)
        } else {
            iv.setImageResource(R.drawable.bg_placeholder)
        }

        // Enriquecimiento TMDB perezoso (igual que el grid del catalogo)
        if (m.posterUrl.isNullOrEmpty() && m.url !in tmdbRequested) {
            tmdbRequested.add(m.url)
            CoroutineScope(Dispatchers.IO).launch {
                try { CineRepository.fetchTmdMetadata(m) } catch (_: Exception) { }
                withContext(Dispatchers.Main) {
                    if (v.tag == m.url) {
                        val recovered = if (!m.posterUrl.isNullOrEmpty()) m.posterUrl else m.rawLogo
                        if (!recovered.isNullOrEmpty()) {
                            Glide.with(iv).load(recovered).centerCrop().into(iv)
                        }
                    }
                }
            }
        }
    }
}
