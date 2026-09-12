package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

/** Item del carrusel coverflow del Home v4: un canal en vivo, un titulo de cine o la tarjeta especial vacia. */
data class HomeCoverItem(
    val poster: String?,
    val title: String,
    val meta: String,
    val badge: String?,
    val channel: Channel?,
    val media: CineMedia?
)

class HomeCoverAdapter(private val onTap: (Int) -> Unit) :
    RecyclerView.Adapter<HomeCoverAdapter.VH>() {

    private val items = ArrayList<HomeCoverItem>()

    fun submitAll(list: List<HomeCoverItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.imgCoverPoster)
        val badge: TextView = v.findViewById(R.id.txtCoverBadge)
        val empty: TextView = v.findViewById(R.id.txtCoverEmpty)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_coverflow, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        if (item.poster.isNullOrBlank()) {
            // tarjeta especial (sin datos): tile de marca con el titulo
            holder.poster.setImageDrawable(null)
            holder.poster.visibility = View.INVISIBLE
            holder.empty.visibility = View.VISIBLE
            holder.empty.text = item.title
        } else {
            holder.poster.visibility = View.VISIBLE
            holder.empty.visibility = View.GONE
            Glide.with(holder.poster)
                .load(item.poster)
                .transition(DrawableTransitionOptions.withCrossFade(350))
                .centerCrop()
                .into(holder.poster)
        }
        holder.badge.visibility = if (item.badge != null) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onTap(p)
        }
    }
}
