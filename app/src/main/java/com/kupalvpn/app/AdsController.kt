package com.kupalvpn.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Opt-in rewarded ads. VPN time is credited only after AdMob confirms the reward. */
class AdsController(private val activity: Activity) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var rewardedAd: RewardedAd? = null
    @Volatile private var initialized = false
    @Volatile private var initializeRequested = false
    @Volatile private var rewardedLoading = false
    @Volatile private var rewardAdShowing = false

    var lastStatus: String by mutableStateOf("Waiting for privacy consent")
        private set
    var adsReady: Boolean by mutableStateOf(false)
        private set
    var rewardedReady: Boolean by mutableStateOf(false)
        private set

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post {
                if (!activity.isFinishing && !activity.isDestroyed) block()
            }
        }
    }

    fun requestConsentAndInitialize() {
        if (activity.isFinishing || activity.isDestroyed) return
        onMain {
            try {
                val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
                val parameters = ConsentRequestParameters.Builder().build()
                consentInformation.requestConsentInfoUpdate(
                    activity,
                    parameters,
                    {
                        onMain {
                            try {
                                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                                    onMain {
                                        if (formError != null) {
                                            lastStatus = "Consent form: ${formError.message}"
                                        }
                                        if (consentInformation.canRequestAds()) initializeAds()
                                        else lastStatus = "Ads unavailable: consent does not permit requests"
                                    }
                                }
                            } catch (error: Throwable) {
                                lastStatus = "Consent form unavailable: ${error.message ?: error.javaClass.simpleName}"
                                if (consentInformation.canRequestAds()) initializeAds()
                            }
                        }
                    },
                    { requestError ->
                        onMain {
                            lastStatus = "Consent update: ${requestError.message}"
                            if (consentInformation.canRequestAds()) initializeAds()
                        }
                    }
                )
            } catch (error: Throwable) {
                lastStatus = "Privacy consent unavailable: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun initializeAds() {
        if (initializeRequested || activity.isFinishing || activity.isDestroyed) return
        initializeRequested = true
        lastStatus = "Initializing AdMob…"
        try {
            Thread({
                try {
                    // Google recommends initializing the legacy Mobile Ads SDK off the UI thread.
                    MobileAds.initialize(activity.applicationContext) {
                        onMain {
                            initialized = true
                            adsReady = true
                            lastStatus = "AdMob initialized; loading rewarded video…"
                            loadRewardedAd()
                        }
                    }
                } catch (error: Throwable) {
                    onMain {
                        initialized = false
                        initializeRequested = false
                        adsReady = false
                        lastStatus = "AdMob initialization failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }, "Kupal-AdMob-Init").start()
        } catch (error: Throwable) {
            initialized = false
            initializeRequested = false
            adsReady = false
            lastStatus = "AdMob initialization could not start: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun loadRewardedAd() {
        if (!initialized || rewardedAd != null || rewardedLoading || activity.isFinishing || activity.isDestroyed) return
        onMain {
            if (!initialized || rewardedAd != null || rewardedLoading || activity.isFinishing || activity.isDestroyed) return@onMain
            rewardedLoading = true
            lastStatus = "Loading rewarded video…"
            try {
                RewardedAd.load(
                    activity,
                    REWARDED_AD_UNIT_ID,
                    AdRequest.Builder().build(),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            onMain {
                                rewardedLoading = false
                                rewardedAd = ad
                                rewardedReady = true
                                lastStatus = "Rewarded ad ready"
                            }
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            onMain {
                                rewardedLoading = false
                                rewardedAd = null
                                rewardedReady = false
                                lastStatus = "Rewarded ad unavailable: ${error.message} (code ${error.code})"
                            }
                        }
                    }
                )
            } catch (error: Throwable) {
                rewardedLoading = false
                rewardedAd = null
                rewardedReady = false
                lastStatus = "Rewarded ad request failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    fun remainingMillis(): Long =
        runCatching { LocalStore.remainingAdCooldownMillis(activity) }.getOrDefault(0L)

    fun tryShowRewardedAd(onReward: () -> Unit): String {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            onMain { tryShowRewardedAd(onReward) }
            return "Preparing rewarded video…"
        }
        return try {
            if (activity.isFinishing || activity.isDestroyed) return "Activity is no longer available"
            if (rewardAdShowing) return "A rewarded video is already open"
            if (!LocalStore.adsEnabled(activity)) return "Rewarded ads are paused in Settings"
            val cooldown = remainingMillis()
            if (cooldown > 0L) return "Wait ${(cooldown + 999L) / 1000L} seconds before the next ad"
            if (!initialized) {
                if (!initializeRequested) requestConsentAndInitialize()
                return "AdMob is initializing. Please retry shortly."
            }
            val ad = rewardedAd
            if (ad == null) {
                loadRewardedAd()
                return "Rewarded video is loading. Please retry shortly."
            }

            rewardedAd = null
            rewardedReady = false
            rewardAdShowing = true
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    onMain {
                        runCatching { LocalStore.markAdShown(activity) }
                        rewardedReady = false
                        lastStatus = "Rewarded ad shown"
                    }
                }

                override fun onAdDismissedFullScreenContent() {
                    onMain {
                        rewardAdShowing = false
                        lastStatus = if (earned) "Reward granted: 2 hours VPN time" else "Ad closed; no reward earned"
                        loadRewardedAd()
                    }
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    onMain {
                        rewardAdShowing = false
                        lastStatus = "Ad failed to show: ${error.message}"
                        loadRewardedAd()
                    }
                }
            }

            ad.show(activity, OnUserEarnedRewardListener {
                onMain {
                    try {
                        onReward()
                        earned = true
                        lastStatus = "Reward granted: 2 hours VPN time"
                    } catch (error: Throwable) {
                        lastStatus = "Reward could not be saved: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            })
            "Showing rewarded video"
        } catch (error: Throwable) {
            rewardAdShowing = false
            rewardedAd = null
            rewardedLoading = false
            rewardedReady = false
            lastStatus = "Rewarded ad failed: ${error.message ?: error.javaClass.simpleName}"
            loadRewardedAd()
            lastStatus
        }
    }

    fun showPrivacyOptions() {
        if (activity.isFinishing || activity.isDestroyed) {
            lastStatus = "Privacy options unavailable while the app is closing"
            return
        }
        onMain {
            try {
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                    onMain { lastStatus = formError?.message ?: "Privacy options closed" }
                }
            } catch (error: Throwable) {
                lastStatus = "Privacy options unavailable: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    companion object {
        // Do not replace the configured production rewarded ad unit.
        const val REWARDED_AD_UNIT_ID = "ca-app-pub-9471897591250086/2836147281"
    }
}
