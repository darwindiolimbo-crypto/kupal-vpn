package com.kupalvpn.app

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Opt-in rewarded ads. A reward is granted only through AdMob's earned-reward callback. */
class AdsController(private val activity: Activity) {
    private var rewardedAd: RewardedAd? = null
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false
    private var initialized = false
    var lastStatus: String by mutableStateOf("Waiting for privacy consent")
        private set

    fun requestConsentAndInitialize() {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val parameters = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) lastStatus = "Consent form: ${formError.message}"
                    if (consentInformation.canRequestAds()) initializeAds()
                    else lastStatus = "Ads are unavailable until consent permits requests"
                },
            },
            { requestError ->
                lastStatus = "Consent update: ${requestError.message}"
                if (consentInformation.canRequestAds()) initializeAds()
            }
        )
    }

    private fun initializeAds() {
        if (initialized) return
        initialized = true
        Thread {
            MobileAds.initialize(activity) {
                activity.runOnUiThread {
                    lastStatus = "AdMob initialized (rewarded test + configured interstitial)"
                    loadRewardedAd()
                    loadInterstitialAd()
                }
            }
        }.start()
    }

    private fun loadRewardedAd() {
        if (!initialized || rewardedAd != null) return
        RewardedAd.load(
            activity,
            TEST_REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    lastStatus = "Rewarded ad ready"
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    lastStatus = "Rewarded ad unavailable: ${error.message}"
                }
            }
        )
    }

    fun remainingMillis(): Long = LocalStore.remainingAdCooldownMillis(activity)

    fun interstitialRemainingMillis(intervalMinutes: Int): Long =
        LocalStore.interstitialRemainingMillis(activity, intervalMinutes)

    private fun loadInterstitialAd() {
        if (!initialized || interstitialAd != null || interstitialLoading) return
        interstitialLoading = true
        InterstitialAd.load(
            activity,
            BuildConfig.INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialAd = ad
                    lastStatus = "Timed interstitial ready"
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    interstitialAd = null
                    lastStatus = "Interstitial unavailable: ${error.message}"
                }
            }
        )
    }

    fun tryShowInterstitial(vpnConnected: Boolean, intervalMinutes: Int): String {
        if (!LocalStore.adsEnabled(activity)) return "Ads are paused in settings"
        if (vpnConnected) return "Interstitial blocked while VPN is connected"
        val cooldown = interstitialRemainingMillis(intervalMinutes)
        if (cooldown > 0L) return "Timer not ready · ${(cooldown + 999L) / 1000L} seconds remaining"
        if (!initialized) return "AdMob or UMP consent is not ready"
        val ad = interstitialAd
        if (ad == null) {
            loadInterstitialAd()
            return "Interstitial is loading. Try again shortly."
        }
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                LocalStore.markInterstitialShown(activity)
                lastStatus = "Timed interstitial shown"
            }
            override fun onAdDismissedFullScreenContent() {
                lastStatus = "Interstitial closed"
                loadInterstitialAd()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                lastStatus = "Interstitial failed: ${error.message}"
                loadInterstitialAd()
            }
        }
        ad.show(activity)
        return "Showing interstitial"
    }

    fun tryShowRewardedAd(onReward: () -> Unit): String {
        if (!LocalStore.adsEnabled(activity)) return "Rewarded ads are paused in settings"
        val cooldown = remainingMillis()
        if (cooldown > 0L) return "Wait ${(cooldown + 999L) / 1000L} seconds before the next ad"
        if (!initialized) return "AdMob is initializing or consent is unavailable"
        val ad = rewardedAd
        if (ad == null) {
            loadRewardedAd()
            return "Rewarded video is loading. Try again shortly."
        }
        rewardedAd = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                LocalStore.markAdShown(activity)
                lastStatus = "Rewarded ad shown"
            }
            override fun onAdDismissedFullScreenContent() {
                lastStatus = if (earned) "Reward granted: 2 hours VPN time" else "Ad closed; no reward earned"
                loadRewardedAd()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                lastStatus = "Ad failed to show: ${error.message}"
                loadRewardedAd()
            }
        }
        ad.show(activity, OnUserEarnedRewardListener {
            earned = true
            onReward()
            lastStatus = "Reward granted: 2 hours VPN time"
        })
        return "Showing rewarded video"
    }

    fun showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            lastStatus = formError?.message ?: "Privacy options closed"
        }
    }

    companion object {
        // Google's official Android rewarded test unit; replace before production.
        const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }
}
