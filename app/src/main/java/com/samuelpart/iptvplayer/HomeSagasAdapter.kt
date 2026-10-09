package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Entrada de la fila SAGAS: separador con el nombre de la saga o un cartel. */
sealed class SagaEntry {
    class Header(val title: String) : SagaEntry()
    class Poster(val media: CineMedia) : SagaEntry()
}

/** Fila horizontal de sagas: [SAGA] cartel cartel cartel... [SAGA] cartel... */
class HomeSagasAdapter(private val onTap: (CineMedia) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SagaEntry>()

    fun submit(list: List<SagaEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] is SagaEntry.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderHolder(inf.inflate(R.layout.item_saga_header, parent, false))
        } else {
            PosterHolder(inf.inflate(R.layout.item_saga_poster, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val e = items[position]) {
            is SagaEntry.Header -> (holder as HeaderHolder).txt.text = e.title.uppercase()
            is SagaEntry.Poster -> {
                val h = holder as PosterHolder
                h.txtTitle.text = e.media.title
                Glide.with(h.img).load(e.media.posterUrl ?: e.media.rawLogo)
                    .centerCrop().placeholder(R.drawable.bg_tile_glass).into(h.img)
                h.itemView.setOnClickListener { onTap(e.media) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val txt: TextView = v.findViewById(R.id.txtSagaHeader)
    }

    class PosterHolder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgSagaPoster)
        val txtTitle: TextView = v.findViewById(R.id.txtSagaTitle)
    }
}
