package com.kupalvpn.app

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Opt-in rewarded ads. A reward is granted only through AdMob's earned-reward callback. */
class AdsController(private val activity: Activity) {
    private var rewardedAd: RewardedAd? = null
    private var initialized = false
    var lastStatus: String by mutableStateOf("Waiting for privacy consent")
        private set
    var adsReady: Boolean by mutableStateOf(false)
        private set

    fun requestConsentAndInitialize() {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
            val parameters = ConsentRequestParameters.Builder().build()
            consentInformation.requestConsentInfoUpdate(
                activity,
                parameters,
                {
                    if (activity.isFinishing || activity.isDestroyed) return@requestConsentInfoUpdate
                    try {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                            if (formError != null) lastStatus = "Consent form: ${formError.message}"
                            if (consentInformation.canRequestAds()) initializeAds()
                            else lastStatus = "Ads are unavailable until consent permits requests"
                        }
                    } catch (error: Exception) {
                        lastStatus = "Consent form unavailable: ${error.message ?: "unknown error"}"
                        if (consentInformation.canRequestAds()) initializeAds()
                    }
                },
                { requestError ->
                    lastStatus = "Consent update: ${requestError.message}"
                    if (consentInformation.canRequestAds()) initializeAds()
                }
            )
        } catch (error: Exception) {
            lastStatus = "Privacy consent unavailable: ${error.message ?: "unknown error"}"
        }
    }

    private fun initializeAds() {
        if (initialized || activity.isFinishing || activity.isDestroyed) return
        initialized = true
        try {
            Thread {
                try {
                    MobileAds.initialize(activity) {
                        if (activity.isFinishing || activity.isDestroyed) return@initialize
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            adsReady = true
                            lastStatus = "AdMob initialized"
                            loadRewardedAd()
                        }
                    }
                } catch (error: Exception) {
                    activity.runOnUiThread {
                        adsReady = false
                        initialized = false
                        lastStatus = "AdMob initialization failed: ${error.message ?: "unknown error"}"
                    }
                }
            }.start()
        } catch (error: Exception) {
            initialized = false
            adsReady = false
            lastStatus = "AdMob initialization could not start: ${error.message ?: "unknown error"}"
        }
    }

    private fun loadRewardedAd() {
        if (!initialized || rewardedAd != null || activity.isFinishing || activity.isDestroyed) return
        try {
            RewardedAd.load(
                activity,
                REWARDED_AD_UNIT_ID,
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
        } catch (error: Exception) {
            rewardedAd = null
            lastStatus = "Rewarded ad request failed: ${error.message ?: "unknown error"}"
        }
    }

    fun remainingMillis(): Long = LocalStore.remainingAdCooldownMillis(activity)

    fun tryShowRewardedAd(onReward: () -> Unit): String {
        if (activity.isFinishing || activity.isDestroyed) return "Activity is no longer available"
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
        try {
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
                try {
                    onReward()
                    lastStatus = "Reward granted: 2 hours VPN time"
                } catch (error: Exception) {
                    lastStatus = "Reward callback failed: ${error.message ?: "unknown error"}"
                }
            })
            return "Showing rewarded video"
        } catch (error: Exception) {
            lastStatus = "Rewarded ad failed to show: ${error.message ?: "unknown error"}"
            loadRewardedAd()
            return lastStatus
        }
    }

    fun showPrivacyOptions() {
        if (activity.isFinishing || activity.isDestroyed) {
            lastStatus = "Privacy options unavailable while the app is closing"
            return
        }
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                lastStatus = formError?.message ?: "Privacy options closed"
            }
        } catch (error: Exception) {
            lastStatus = "Privacy options unavailable: ${error.message ?: "unknown error"}"
        }
    }

    companion object {
        // Kupal VPN's configured production rewarded ad unit.
        const val REWARDED_AD_UNIT_ID = "ca-app-pub-9471897591250086/2836147281"
    }
}
