package com.samuelpart.iptvplayer

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
 * - [attach] infla el bloque en un contenedor, carga el anuncio y lo muestra
 *   solo cuando hay anuncio listo.
 * - Reintenta automáticamente si AdMob aún no tiene relleno (unidades nuevas
 *   pueden tardar en empezar a servir).
 */
object NativeAds {

    const val UNIT_ID = "ca-app-pub-8124327134735952/3120774272"

    const val VARIANT_MEDIA = 0
    const val VARIANT_COMPACT = 1

    private val RETRY_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L, 60_000L)

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

    fun inflate(activity: Activity, slot: ViewGroup, variant: Int = VARIANT_MEDIA): NativeAdView {
        val layoutRes = if (variant == VARIANT_COMPACT) R.layout.view_native_ad_compact else R.layout.view_native_ad
        val adView = LayoutInflater.from(activity).inflate(layoutRes, slot, false) as NativeAdView
        slot.removeAllViews()
        slot.addView(adView)
        return adView
    }

    /** Carga un anuncio nativo de la unidad de producción (con reintentos). */
    fun load(
        activity: Activity,
        adView: NativeAdView,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        loadWithRetry(activity, adView, onLoaded, onFailed, 0)
    }

    private fun loadWithRetry(
        activity: Activity,
        adView: NativeAdView,
        onLoaded: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        attempt: Int
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
                populate(adView, ad)
                onLoaded?.invoke()
            }
            .withNativeAdOptions(options)
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    if (attempt < RETRY_DELAYS_MS.size) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            loadWithRetry(activity, adView, onLoaded, onFailed, attempt + 1)
                        }, RETRY_DELAYS_MS[attempt])
                    } else {
                        onFailed?.invoke()
                    }
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
