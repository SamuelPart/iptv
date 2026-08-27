package com.samuelpart.iptvplayer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date

class IPTVApplication : Application(), Application.ActivityLifecycleCallbacks, LifecycleEventObserver {

    private lateinit var appOpenAdManager: AppOpenAdManager
    private var currentActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        
        // Initialize Google Mobile Ads SDK
        MobileAds.initialize(this) {}
        
        appOpenAdManager = AppOpenAdManager()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Real-time scraper config: instantly load the last saved portal list,
        // then hot-update it from GitHub. If a source site changes its domain,
        // editing scraper_config.json in the repo fixes every app — no update needed.
        ScraperConfig.loadCached(this)
        CoroutineScope(Dispatchers.IO).launch {
            ScraperConfig.refresh(this@IPTVApplication)
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_START) {
            currentActivity?.let {
                appOpenAdManager.showAdIfAvailable(it)
            }
        }
    }

    // --- Activity Lifecycle Callbacks ---
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) {
            currentActivity = null
        }
    }

    // Inner class to handle loading and showing of the App Open Ads
    inner class AppOpenAdManager {
        private val AD_UNIT_ID = "ca-app-pub-8124327134735952/8565829229"
        private var appOpenAd: AppOpenAd? = null
        private var isLoadingAd = false
        var isShowingAd = false
        private var loadTime: Long = 0

        fun loadAd(context: Application) {
            if (isLoadingAd || isAdAvailable()) {
                return
            }

            isLoadingAd = true
            val request = AdRequest.Builder().build()
            AppOpenAd.load(
                context,
                AD_UNIT_ID,
                request,
                AppOpenAd.APP_OPEN_AD_ORIENTATION_PORTRAIT,
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        appOpenAd = ad
                        isLoadingAd = false
                        loadTime = Date().time
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        isLoadingAd = false
                    }
                }
            )
        }

        private fun wasLoadTimeLessThanNHoursAgo(numHours: Long): Boolean {
            val dateDifference: Long = Date().time - loadTime
            val numMilliSecondsPerHour: Long = 3600000
            return dateDifference < numMilliSecondsPerHour * numHours
        }

        private fun isAdAvailable(): Boolean {
            return appOpenAd != null && wasLoadTimeLessThanNHoursAgo(4)
        }

        fun showAdIfAvailable(activity: Activity) {
            // Do not interrupt the player activity or onboarding walkthrough with pop-ups
            if (activity is PlayerActivity) {
                return
            }

            if (isShowingAd) {
                return
            }

            if (!isAdAvailable()) {
                loadAd(this@IPTVApplication)
                return
            }

            appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    appOpenAd = null
                    isShowingAd = false
                    loadAd(this@IPTVApplication)
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    appOpenAd = null
                    isShowingAd = false
                    loadAd(this@IPTVApplication)
                }

                override fun onAdShowedFullScreenContent() {
                    isShowingAd = true
                }
            }
            
            appOpenAd?.show(activity)
        }
    }
}
