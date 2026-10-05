package com.gbxps.fasea

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * AdMob rewarded (24-hour unlock) and interstitial (between free practice sessions) ads.
 * Ad unit IDs come from BuildConfig: Google's test IDs in debug, gradle.properties in release.
 * All methods must be called on the main thread.
 */
class AdManager(private val activity: Activity) {

    companion object {
        private const val INTERSTITIAL_MIN_INTERVAL_MS = 3 * 60 * 1000L
    }

    private var rewardedAd: RewardedAd? = null
    private var interstitialAd: InterstitialAd? = null
    private var loadingRewarded = false
    private var loadingInterstitial = false
    private var lastInterstitialAt = 0L

    init {
        MobileAds.initialize(activity) {}
        loadRewardedAd()
        loadInterstitialAd()
    }

    private fun loadRewardedAd() {
        if (rewardedAd != null || loadingRewarded) return
        loadingRewarded = true
        RewardedAd.load(activity, BuildConfig.REWARDED_AD_UNIT_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
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

    private fun loadInterstitialAd() {
        if (interstitialAd != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(activity, BuildConfig.INTERSTITIAL_AD_UNIT_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    loadingInterstitial = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    loadingInterstitial = false
                }
            })
    }

    /** Shows a rewarded ad; [onReward] runs only if the user earned the reward. */
    fun showRewardedAd(onReward: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            loadRewardedAd()
            // Debug builds unlock anyway so the flow can be tested without fill.
            if (BuildConfig.DEBUG) onReward() else onUnavailable()
            return
        }
        rewardedAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = loadRewardedAd()
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                loadRewardedAd()
                onUnavailable()
            }
        }
        ad.show(activity) { onReward() }
    }

    /** Shows an interstitial if one is loaded and the frequency cap allows it. */
    fun showInterstitialAd() {
        val now = System.currentTimeMillis()
        val ad = interstitialAd
        if (ad == null) {
            loadInterstitialAd()
            return
        }
        if (now - lastInterstitialAt < INTERSTITIAL_MIN_INTERVAL_MS) return
        interstitialAd = null
        lastInterstitialAt = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = loadInterstitialAd()
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) =
                loadInterstitialAd()
        }
        ad.show(activity)
    }
}
