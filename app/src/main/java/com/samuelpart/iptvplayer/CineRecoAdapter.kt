package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

/** Rail horizontal "Porque viste X" del Cine v2: posters compactos 110dp con titulo chiquito. */
class CineRecoAdapter(private val onTap: (CineMedia) -> Unit) :
    RecyclerView.Adapter<CineRecoAdapter.VH>() {

    private val items = ArrayList<CineMedia>()

    fun submitAll(list: List<CineMedia>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.imgRecoPoster)
        val title: TextView = v.findViewById(R.id.txtRecoTitle)
        val rating: TextView = v.findViewById(R.id.txtRecoRating)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cine_reco, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val media = items[position]
        holder.title.text = media.title
        val r = media.rating ?: 0.0
        holder.rating.visibility = if (r > 0.0) View.VISIBLE else View.GONE
        if (r > 0.0) holder.rating.text = "★ %.1f".format(r)
        Glide.with(holder.poster)
            .load(media.posterUrl)
            .transition(DrawableTransitionOptions.withCrossFade(300))
            .centerCrop()
            .into(holder.poster)
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onTap(items[p])
        }
    }
}
