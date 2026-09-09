package com.samuelpart.iptvplayer

import android.app.Activity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Anuncio NATIVO AVANZADO reutilizable para TODAS las pantallas.
 *
 * - [UNIT_ID]: unidad de producción.
 * - Dos variantes de bloque: [VARIANT_MEDIA] (tarjeta vertical con vídeo/imagen)
 *   y [VARIANT_COMPACT] (fila horizontal chica).
 * - [attach] infla el bloque, hace UNA sola petición de anuncio y lo muestra
 *   cuando llega. Sin reintentos.
 */
object NativeAds {

    const val UNIT_ID = "ca-app-pub-8124327134735952/3120774272"

    const val VARIANT_MEDIA = 0
    const val VARIANT_COMPACT = 1

    /** Cada cuántas cards se intercala un anuncio nativo en las cuadrículas. */
    const val GRID_AD_INTERVAL = 4

    /** ViewType reservado para las posiciones de anuncio dentro de los adapters. */
    const val GRID_AD_TYPE = 0x7E0000F7

    private const val TAG = "NativeAds"

    // Marcador interno para las posiciones de anuncio en las listas intercaladas.
    private val AD_MARKER = Any()

    /** Intercala un marcador de anuncio cada [GRID_AD_INTERVAL] items reales. */
    fun <T> interleaveWithAds(items: List<T>): List<Any?> {
        if (items.isEmpty()) return emptyList()
        val out = mutableListOf<Any?>()
        items.forEachIndexed { index, item ->
            out.add(item)
            if ((index + 1) % GRID_AD_INTERVAL == 0) out.add(AD_MARKER)
        }
        return out
    }

    /** True si la posición de una lista intercalada es un anuncio. */
    fun isAdMarker(item: Any?): Boolean = item === AD_MARKER

    /**
     * Crea el hueco de anuncio para un RecyclerView (cuadrícula). Arranca
     * colapsado (altura 0) y, cuando AdMob sirve el anuncio, crece al tamaño
     * del bloque. [onResult] se llama al cargar/fallar para que el adapter
     * pida re-medir esa posición.
     */
    fun attachRecycler(
        activity: Activity,
        slot: FrameLayout,
        variant: Int = VARIANT_COMPACT,
        onResult: (Boolean) -> Unit = {}
    ) {
        slot.tag = "grid_ad"
        slot.visibility = View.VISIBLE
        slot.layoutParams = slot.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slot.layoutParams.height = 0
        val adView = inflate(activity, slot, variant)
        load(
            activity,
            adView,
            onLoaded = {
                slot.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                slot.visibility = View.VISIBLE
                onResult(true)
            },
            onFailed = {
                slot.layoutParams.height = 0
                slot.visibility = View.VISIBLE
                onResult(false)
            }
        )
    }

    /** Infla el bloque en [slot] y lo mantiene oculto hasta que carga el anuncio. */
    fun attach(activity: Activity, slot: ViewGroup, variant: Int = VARIANT_MEDIA) {
        slot.visibility = View.GONE
        val adView = inflate(activity, slot, variant)
        load(
            activity,
            adView,
            onLoaded = { slot.visibility = View.VISIBLE },
            onFailed = { slot.visibility = View.GONE }
        )
    }

    /** Crea el hueco (FrameLayout) para un anuncio dentro de una cuadrícula. */
    fun createAdSlot(parent: ViewGroup): FrameLayout {
        val slot = FrameLayout(parent.context)
        slot.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slot.tag = "grid_ad"
        return slot
    }

    fun inflate(activity: Activity, slot: ViewGroup, variant: Int = VARIANT_MEDIA): NativeAdView {
        val layoutRes = if (variant == VARIANT_COMPACT) R.layout.view_native_ad_compact else R.layout.view_native_ad
        val adView = LayoutInflater.from(activity).inflate(layoutRes, slot, false) as NativeAdView
        slot.removeAllViews()
        slot.addView(adView)
        return adView
    }

    /** Hace UNA sola petición de anuncio nativo (sin reintentos). */
    fun load(
        activity: Activity,
        adView: NativeAdView,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onFailed?.invoke()
            return
        }

        // Soporta vídeo (MediaView) e imagen (ImageView) para maximizar el relleno.
        val options = NativeAdOptions.Builder()
            .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
            .build()

        val loader = AdLoader.Builder(activity, UNIT_ID)
            .forNativeAd { ad: NativeAd ->
                Log.d(TAG, "OK: anuncio nativo cargado")
                populate(adView, ad)
                onLoaded?.invoke()
            }
            .withNativeAdOptions(options)
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.d(
                        TAG,
                        "FALLO: code=${loadAdError.code} " +
                            "msg=${loadAdError.message} domain=${loadAdError.domain} " +
                            "mediation=${loadAdError.responseInfo?.mediationAdapterClassName ?: "?"}"
                    )
                    onFailed?.invoke()
                }
            })
            .build()

        loader.loadAd(AdRequest.Builder().build())
    }

    /** Rellena un NativeAdView con los ids estándar del bloque (null-safe). */
    fun populate(adView: NativeAdView, ad: NativeAd) {
        val headline = adView.findViewById<TextView>(R.id.global_ad_headline)
        val body = adView.findViewById<TextView>(R.id.global_ad_body)
        val icon = adView.findViewById<ImageView>(R.id.global_ad_app_icon)
        val cta = adView.findViewById<Button>(R.id.global_ad_call_to_action)
        val media = adView.findViewById<MediaView>(R.id.global_ad_media)
        val image = adView.findViewById<ImageView>(R.id.global_ad_image)

        headline?.let {
            it.text = ad.headline
            adView.headlineView = it
        }

        body?.let {
            if (ad.body != null) {
                it.visibility = View.VISIBLE
                it.text = ad.body
                adView.bodyView = it
            } else {
                it.visibility = View.GONE
            }
        }

        icon?.let {
            if (ad.icon != null) {
                it.visibility = View.VISIBLE
                it.setImageDrawable(ad.icon?.drawable)
                adView.iconView = it
            } else {
                it.visibility = View.GONE
            }
        }

        cta?.let {
            if (ad.callToAction != null) {
                it.visibility = View.VISIBLE
                it.text = ad.callToAction
                adView.callToActionView = it
            } else {
                it.visibility = View.GONE
            }
        }

        // Vídeo (MediaView) o imagen grande (ImageView), según lo que traiga el anuncio.
        if (media != null && ad.mediaContent != null) {
            media.visibility = View.VISIBLE
            media.setMediaContent(ad.mediaContent)
            adView.mediaView = media
            image?.visibility = View.GONE
        } else if (image != null && !ad.images.isNullOrEmpty()) {
            image.visibility = View.VISIBLE
            image.setImageDrawable(ad.images.first().drawable)
            media?.visibility = View.GONE
        } else {
            media?.visibility = View.GONE
            image?.visibility = View.GONE
        }

        adView.setNativeAd(ad)
    }
}
