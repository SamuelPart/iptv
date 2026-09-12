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

/** Rail horizontal "Popular Movies": chip de calidad + play rojo + views. */
class CinePopularAdapter(
    private val items: List<CineMedia>,
    private val onClick: (CineMedia) -> Unit
) : RecyclerView.Adapter<CinePopularAdapter.VH>() {

    private val tmdbRequested = mutableSetOf<String>()

    inner class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_cine_popular, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val v = holder.itemView
        v.tag = m.url

        val iv = v.findViewById<ImageView>(R.id.imgPopPoster)
        v.findViewById<TextView>(R.id.txtPopTitle)?.text = m.title
        v.findViewById<TextView>(R.id.txtPopQuality)?.text = when (Math.abs(m.title.hashCode()) % 3) {
            0 -> "HD"; 1 -> "4K"; else -> "HQ"
        }

        val rt = m.rating
        val views = (Math.abs(m.title.hashCode()) % 30) + 8
        v.findViewById<TextView>(R.id.txtPopMeta)?.text =
            if (rt != null && rt > 0) "★ ${"%.1f".format(rt)} · ${views}M+ Views"
            else "${views}M+ Views"

        v.setOnClickListener { onClick(m) }
        v.findViewById<View>(R.id.btnPopPlay)?.setOnClickListener { onClick(m) }

        val src = if (!m.posterUrl.isNullOrEmpty()) m.posterUrl else m.rawLogo
        if (!src.isNullOrEmpty()) {
            Glide.with(iv).load(src).centerCrop().placeholder(R.drawable.bg_placeholder).into(iv)
        } else {
            iv.setImageResource(R.drawable.bg_placeholder)
        }

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
