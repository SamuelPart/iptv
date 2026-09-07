package com.samuelpart.iptvplayer

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback

/**
 * Anuncio recompensado OBLIGATORIO antes de reproducir.
 *
 * Regla estricta: el usuario solo puede ver el contenido si completa el
 * anuncio (onUserEarnedReward). Si lo cierra antes de terminar, NO se
 * reproduce y se le vuelve a ofrecer hasta que lo vea entero.
 * Aplica a canales de TV, películas y series.
 */
object RewardGate {

    private const val REWARDED_ID = "ca-app-pub-8124327134735952/7417379028"
    private const val REWARDED_INTERSTITIAL_ID = "ca-app-pub-8124327134735952/1699160240"

    private var rewardedAd: RewardedAd? = null
    private var rewardedInterstitial: RewardedInterstitialAd? = null

    private var loadingRewarded = false
    private var loadingInterstitial = false
    private var dialogVisible = false

    fun load(context: Context) {
        val request = AdRequest.Builder().build()
        val ctx = context.applicationContext

        if (rewardedAd == null && !loadingRewarded) {
            loadingRewarded = true
            RewardedAd.load(ctx, REWARDED_ID, request, object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    loadingRewarded = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    loadingRewarded = false
                }
            })
        }

        if (rewardedInterstitial == null && !loadingInterstitial) {
            loadingInterstitial = true
            RewardedInterstitialAd.load(ctx, REWARDED_INTERSTITIAL_ID, request, object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    rewardedInterstitial = ad
                    loadingInterstitial = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedInterstitial = null
                    loadingInterstitial = false
                }
            })
        }
    }

    /**
     * Bloquea la reproducción hasta que el usuario complete el anuncio.
     * [onGranted] se invoca SOLO si el anuncio se vio completo.
     */
    fun requireAdThen(activity: Activity, onGranted: () -> Unit) {
        val rw = rewardedAd
        val rwi = rewardedInterstitial

        when {
            rw != null -> {
                rewardedAd = null
                showRewarded(activity, rw, onGranted)
            }
            rwi != null -> {
                rewardedInterstitial = null
                showRewardedInterstitial(activity, rwi, onGranted)
            }
            else -> {
                load(activity)
                showBlockedDialog(
                    activity,
                    "El anuncio aún no está listo.\n\nPulsa \u201CVer anuncio\u201D para reintentar. " +
                        "No podrás reproducir hasta ver el anuncio completo.",
                    onGranted
                )
            }
        }
    }

    private fun showRewarded(activity: Activity, ad: RewardedAd, onGranted: () -> Unit) {
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                load(activity)
                if (earned) onGranted()
                else showBlockedDialog(
                    activity,
                    "Cerraste el anuncio antes de terminarlo.\n\nDebes verlo completo para poder reproducir.",
                    onGranted
                )
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                load(activity)
                showBlockedDialog(
                    activity,
                    "No se pudo mostrar el anuncio.\n\nReintenta para poder reproducir.",
                    onGranted
                )
            }
        }
        ad.show(activity) { earned = true }
    }

    private fun showRewardedInterstitial(activity: Activity, ad: RewardedInterstitialAd, onGranted: () -> Unit) {
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                load(activity)
                if (earned) onGranted()
                else showBlockedDialog(
                    activity,
                    "Cerraste el anuncio antes de terminarlo.\n\nDebes verlo completo para poder reproducir.",
                    onGranted
                )
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                load(activity)
                showBlockedDialog(
                    activity,
                    "No se pudo mostrar el anuncio.\n\nReintenta para poder reproducir.",
                    onGranted
                )
            }
        }
        ad.show(activity) { earned = true }
    }

    private fun showBlockedDialog(activity: Activity, message: String, onGranted: () -> Unit) {
        if (dialogVisible || activity.isFinishing || activity.isDestroyed) return
        dialogVisible = true

        AlertDialog.Builder(activity)
            .setTitle("Anuncio obligatorio")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Ver anuncio") { _, _ ->
                dialogVisible = false
                requireAdThen(activity, onGranted)
            }
            .setNegativeButton("Cancelar") { _, _ ->
                dialogVisible = false
            }
            .show()
    }
}
