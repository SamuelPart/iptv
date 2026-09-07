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
 *
 * Usa TODAS las unidades de recompensa configuradas en AdMob y rota entre
 * ellas para que trabajen todas:
 *   - Intersticial recompensado: 1699160240
 *   - Recompensado A (RECOMPENSA): 7417379028
 *   - Recompensado B: 6307893594
 */
object RewardGate {

    private const val REWARDED_INTERSTITIAL_ID = "ca-app-pub-8124327134735952/1699160240"
    private const val REWARDED_ID_A = "ca-app-pub-8124327134735952/7417379028"
    private const val REWARDED_ID_B = "ca-app-pub-8124327134735952/6307893594"

    private var rewardedInterstitial: RewardedInterstitialAd? = null
    private var rewardedA: RewardedAd? = null
    private var rewardedB: RewardedAd? = null

    private var loadingInterstitial = false
    private var loadingA = false
    private var loadingB = false
    private var dialogVisible = false
    private var toggle = false

    fun load(context: Context) {
        val request = AdRequest.Builder().build()
        val ctx = context.applicationContext

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

        if (rewardedA == null && !loadingA) {
            loadingA = true
            RewardedAd.load(ctx, REWARDED_ID_A, request, object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedA = ad
                    loadingA = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedA = null
                    loadingA = false
                }
            })
        }

        if (rewardedB == null && !loadingB) {
            loadingB = true
            RewardedAd.load(ctx, REWARDED_ID_B, request, object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedB = ad
                    loadingB = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedB = null
                    loadingB = false
                }
            })
        }
    }

    /**
     * Bloquea la reproducción hasta que el usuario complete el anuncio.
     * [onGranted] se invoca SOLO si el anuncio se vio completo.
     */
    fun requireAdThen(activity: Activity, onGranted: () -> Unit) {
        val rwi = rewardedInterstitial
        if (rwi != null) {
            rewardedInterstitial = null
            showRewardedInterstitial(activity, rwi, onGranted)
            return
        }

        // Rota entre las dos unidades de recompensado para que ambas trabajen.
        val rw = if (toggle) (rewardedA ?: rewardedB) else (rewardedB ?: rewardedA)
        toggle = !toggle
        if (rw != null) {
            if (rw === rewardedA) rewardedA = null else rewardedB = null
            showRewarded(activity, rw, onGranted)
            return
        }

        load(activity)
        showBlockedDialog(
            activity,
            "El anuncio aún no está listo.\n\nPulsa \u201CVer anuncio\u201D para reintentar. " +
                "No podrás reproducir hasta ver el anuncio completo.",
            onGranted
        )
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
