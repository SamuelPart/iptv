package com.samuelpart.iptvplayer

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ItemChannelBinding
import com.samuelpart.iptvplayer.databinding.ItemChannelTunerBinding

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelClick: (Channel) -> Unit,
    private val isFavorite: ((Channel) -> Boolean)? = null,
    private val onFavoriteToggle: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** true = el modo lista (1 columna) se vuelve "Sintonizador": fila central con zoom + anillo champagne. */
    var tunerMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private var pulsingDot: View? = null

    /** Lista real + marcadores de anuncio (null → no; objeto AD_MARKER → sí). */
    private var displayList: List<Any?> = emptyList()

    /** Columnas de la cuadrícula: el anuncio se inserta cada [NativeAds.GRID_AD_ROWS] filas. */
    var gridColumns: Int = 3
        set(value) {
            if (field != value) {
                field = value
                rebuildDisplayList()
                notifyDataSetChanged()
            }
        }

    companion object {
        private const val TYPE_CARD = 0
        private const val TYPE_TUNER = 1
    }

    init { rebuildDisplayList() }

    private fun rebuildDisplayList() {
        displayList = NativeAds.interleaveWithAds(channels, gridColumns)
    }

    fun isAdAt(position: Int): Boolean = NativeAds.isAdMarker(displayList.getOrNull(position))

    // ================= VIEW HOLDERS =================

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.root.springPress()
            binding.txtChannelName.text = channel.name
            loadLogo(binding.imgLogo, channel)
            bindStar(binding.imgChannelFav, channel)
            binding.root.setOnClickListener { onChannelClick(channel) }
        }
    }

    inner class TunerViewHolder(private val binding: ItemChannelTunerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel, position: Int) {
            binding.root.springPress()
            binding.txtChannelNumber.text = String.format("%02d", position + 1)
            binding.txtChannelName.text = channel.name
            val meta = listOfNotNull(
                channel.group?.takeIf { it.isNotBlank() },
                channel.country?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            binding.txtChannelMeta.text =
                if (meta.isNotBlank()) meta else "Toca para ver en vivo"
            loadLogo(binding.imgLogo, channel)
            bindStar(binding.imgChannelFav, channel)
            binding.viewChannelFocusRing.visibility = View.GONE
            binding.viewChannelLiveDot.alpha = 1f
            binding.root.setOnClickListener { onChannelClick(channel) }
        }
    }

    private fun loadLogo(target: android.widget.ImageView, channel: Channel) {
        Glide.with(target.context)
            .load(channel.logoUrl)
            .transition(DrawableTransitionOptions.withCrossFade())
            .placeholder(R.drawable.bg_placeholder)
            .error(R.drawable.ic_tv)
            .fallback(R.drawable.ic_tv)
            .into(target)
    }

    private fun bindStar(star: android.widget.ImageView, channel: Channel) {
        if (isFavorite != null) {
            star.visibility = View.VISIBLE
            updateFavIcon(star, isFavorite?.invoke(channel) == true)
            star.setOnClickListener {
                onFavoriteToggle?.invoke(channel)
                val nowFav = isFavorite?.invoke(channel) == true
                updateFavIcon(star, nowFav)
                android.animation.ObjectAnimator.ofFloat(it, "scaleX", 0.6f, 1f)
                    .apply { duration = 280; start() }
                android.animation.ObjectAnimator.ofFloat(it, "scaleY", 0.6f, 1f)
                    .apply { duration = 280; start() }
            }
        } else {
            star.visibility = View.GONE
        }
    }

    private fun updateFavIcon(star: android.widget.ImageView, fav: Boolean) {
        if (fav) {
            star.setImageResource(R.drawable.ic_ios_star_fill)
            star.imageTintList =
                android.content.res.ColorStateList.valueOf(0xFFFFD60A.toInt())
        } else {
            star.setImageResource(R.drawable.ic_ios_star)
            star.imageTintList =
                android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
        }
    }

    inner class AdViewHolder(val slot: android.widget.FrameLayout) :
        RecyclerView.ViewHolder(slot) {
        private var loaded = false
        fun ensureLoaded() {
            if (loaded) return
            loaded = true
            val activity = slot.context as? Activity ?: return
            NativeAds.attachRecycler(activity, slot, NativeAds.VARIANT_COMPACT) {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) notifyItemChanged(p)
            }
        }
    }

    // ================= ADAPTER CORE =================

    override fun getItemViewType(position: Int): Int {
        if (NativeAds.isAdMarker(displayList.getOrNull(position))) return NativeAds.GRID_AD_TYPE
        return if (tunerMode) TYPE_TUNER else TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            NativeAds.GRID_AD_TYPE -> AdViewHolder(NativeAds.createAdSlot(parent))
            TYPE_TUNER -> TunerViewHolder(ItemChannelTunerBinding.inflate(inf, parent, false))
            else -> ChannelViewHolder(ItemChannelBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AdViewHolder) {
            holder.ensureLoaded()
            return
        }
        animateEntrance(holder.itemView, position)
        // Índice real del canal (saltando los anuncios intercalados).
        val contentIndex = contentIndexAt(position)
        when (holder) {
            is TunerViewHolder -> holder.bind(channels[contentIndex], contentIndex)
            is ChannelViewHolder -> holder.bind(channels[contentIndex])
        }
    }

    override fun getItemCount(): Int = displayList.size

    /** Posición real dentro de [channels] para una posición de la lista intercalada. */
    private fun contentIndexAt(position: Int): Int {
        var real = 0
        for (i in 0 until position) {
            if (!NativeAds.isAdMarker(displayList.getOrNull(i))) real++
        }
        return real
    }

    /** iPhone/PS stagger: each new card rises + fades in as it gets scrolled into view. */
    private var lastAnimatedPosition = -1

    private fun animateEntrance(view: View, position: Int) {
        if (position <= lastAnimatedPosition) return
        lastAnimatedPosition = position
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 44f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction(null)
            .start()
    }

    fun updateList(newChannels: List<Channel>) {
        lastAnimatedPosition = -1
        channels = newChannels
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    // ================= SINTONIZADOR (foco central vertical) =================

    /** Instala el zoom de la fila cerca del centro de pantalla cuando tunerMode esta activo. */
    fun attachTuner(rv: RecyclerView) {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (tunerMode) applyTunerTransforms(recyclerView) else resetTunerTransforms(recyclerView)
            }
        })
    }

    private fun applyTunerTransforms(rv: RecyclerView) {
        val cy = rv.height / 2f
        val radius = rv.height * 0.55f
        var best: View? = null
        var bestT = 0f
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            if (v.tag == "grid_ad") continue // no tocar los anuncios nativos
            val vcy = v.top + v.height / 2f
            val t = (1f - kotlin.math.abs(vcy - cy) / radius).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            val s = 0.92f + 0.10f * e
            v.scaleX = s
            v.scaleY = s
            v.alpha = 0.55f + 0.45f * e
            v.translationZ = 10f * e
            val ring = v.findViewById<View>(R.id.viewChannelFocusRing)
            ring?.visibility = if (t > 0.80f) View.VISIBLE else View.GONE
            if (t > bestT) { bestT = t; best = v }
        }
        val dot = best?.findViewById<View>(R.id.viewChannelLiveDot)
        if (dot != null && dot != pulsingDot) {
            pulsingDot?.animate()?.cancel()
            pulsingDot?.alpha = 1f
            dot.alpha = 0.25f
            dot.animate()
                .alpha(1f)
                .setDuration(420)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .start()
            pulsingDot = dot
        }
    }

    private fun resetTunerTransforms(rv: RecyclerView) {
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            if (v.tag == "grid_ad") continue // no tocar los anuncios nativos
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = 1f
            v.translationZ = 0f
            v.findViewById<View>(R.id.viewChannelFocusRing)?.visibility = View.GONE
        }
        pulsingDot?.animate()?.cancel()
        pulsingDot?.alpha = 1f
        pulsingDot = null
    }
}
