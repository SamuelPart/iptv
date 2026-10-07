package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * Adapter del historial de reproduccion: poster + titulo + "te quedaste en el
 * minuto X". Soporta modo seleccion (editar/borrar) y apertura de la ficha.
 */
class WatchHistoryAdapter(
    private val onItemClick: (ContinueWatchingManager.ResumeEntry) -> Unit,
    private val onLongPress: () -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<WatchHistoryAdapter.Holder>() {

    private val items = mutableListOf<ContinueWatchingManager.ResumeEntry>()
    private val posterByKey = mutableMapOf<String, String>()
    var selectionMode: Boolean = false
        private set
    private val selected = LinkedHashSet<String>()

    fun submit(list: List<ContinueWatchingManager.ResumeEntry>, posters: Map<String, String>) {
        items.clear()
        items.addAll(list)
        posterByKey.clear()
        posterByKey.putAll(posters)
        notifyDataSetChanged()
    }

    fun setSelectionMode(active: Boolean) {
        selectionMode = active
        if (!active) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun selectedUrls(): List<String> = selected.toList()

    private fun toggle(url: String) {
        if (!selected.add(url)) selected.remove(url)
        onSelectionChanged(selected.size)
        val pos = items.indexOfFirst { it.url == url }
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_watch_history, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        holder.txtTitle.text = e.title
        val min = (e.positionMs / 60000L).toInt()
        holder.txtMinute.text = "Te quedaste en el minuto $min"

        val poster = e.media?.posterUrl ?: e.media?.rawLogo
            ?: posterByKey[e.title.lowercase().trim()] ?: ""
        Glide.with(holder.img.context)
            .load(poster)
            .centerCrop()
            .placeholder(R.drawable.bg_tile_glass)
            .into(holder.img)

        holder.chk.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.chk.alpha = if (e.url in selected) 1f else 0.45f

        holder.itemView.setOnClickListener {
            if (selectionMode) toggle(e.url) else onItemClick(e)
        }
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selected.clear()
                selected.add(e.url)
                notifyDataSetChanged()
                onSelectionChanged(selected.size)
                onLongPress()
                true
            } else false
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgWatchPoster)
        val chk: ImageView = v.findViewById(R.id.chkWatchSelect)
        val txtTitle: TextView = v.findViewById(R.id.txtWatchTitle)
        val txtMinute: TextView = v.findViewById(R.id.txtWatchMinute)
    }
}
