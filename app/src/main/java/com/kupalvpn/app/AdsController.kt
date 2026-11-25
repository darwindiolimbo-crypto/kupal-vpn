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
    @Volatile private var consentRequestInFlight = false
    @Volatile private var rewardedLoading = false
    @Volatile private var rewardAdShowing = false
    private var rewardedRetryDelayMs = 15_000L
    private var consentRetryDelayMs = 30_000L
    private val rewardedRetry = Runnable { loadRewardedAd() }
    private val consentRetry = Runnable { requestConsentAndInitialize() }

    var lastStatus: String by mutableStateOf("Checking privacy consent…")
        private set
    var bannerStatus: String by mutableStateOf("Banner not requested")
        private set
    var adsReady: Boolean by mutableStateOf(false)
        private set
    var rewardedReady: Boolean by mutableStateOf(false)
        private set

    private fun onMain(block: () -> Unit) {
        val action = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            try {
                block()
            } catch (error: Throwable) {
                lastStatus = "Ad operation failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run()
        else mainHandler.post(action)
    }

    private fun scheduleConsentRetry() {
        if (activity.isFinishing || activity.isDestroyed) return
        mainHandler.removeCallbacks(consentRetry)
        mainHandler.postDelayed(consentRetry, consentRetryDelayMs)
        consentRetryDelayMs = (consentRetryDelayMs * 2L).coerceAtMost(5L * 60L * 1000L)
    }

    private fun scheduleRewardedRetry() {
        if (activity.isFinishing || activity.isDestroyed) return
        mainHandler.removeCallbacks(rewardedRetry)
        mainHandler.postDelayed(rewardedRetry, rewardedRetryDelayMs)
        rewardedRetryDelayMs = (rewardedRetryDelayMs * 2L).coerceAtMost(5L * 60L * 1000L)
    }

    fun reportBannerLoaded() {
        onMain { bannerStatus = "Banner ad loaded" }
    }

    fun reportBannerFailed(error: LoadAdError) {
        onMain { bannerStatus = "Banner unavailable: ${error.message} (code ${error.code})" }
    }

    fun requestConsentAndInitialize() {
        if (activity.isFinishing || activity.isDestroyed) return
        onMain {
            if (consentRequestInFlight || initializeRequested || initialized) return@onMain
            consentRequestInFlight = true
            try {
                val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
                val parameters = ConsentRequestParameters.Builder().build()
                consentInformation.requestConsentInfoUpdate(
                    activity,
                    parameters,
                    {
                        onMain {
                            consentRequestInFlight = false
                            // A previous valid consent choice can already permit ads.
                            // Start the SDK now; a newly required form is still shown below.
                            if (consentInformation.canRequestAds()) initializeAds()
                            try {
                                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                                    onMain {
                                        if (formError != null) {
                                            lastStatus = "Consent form: ${formError.message}"
                                        }
                                        if (consentInformation.canRequestAds()) {
                                            mainHandler.removeCallbacks(consentRetry)
                                            consentRetryDelayMs = 30_000L
                                            if (!initialized) initializeAds()
                                            else if (formError == null) lastStatus = "Privacy checked; ads are permitted"
                                        } else {
                                            lastStatus = "Consent is required before ads can be requested"
                                            scheduleConsentRetry()
                                        }
                                    }
                                }
                            } catch (error: Throwable) {
                                lastStatus = "Consent form unavailable: ${error.message ?: error.javaClass.simpleName}"
                                if (consentInformation.canRequestAds()) initializeAds()
                                else scheduleConsentRetry()
                            }
                        }
                    },
                    { requestError ->
                        onMain {
                            consentRequestInFlight = false
                            lastStatus = "Consent update failed: ${requestError.message}"
                            if (consentInformation.canRequestAds()) initializeAds()
                            else scheduleConsentRetry()
                        }
                    }
                )
            } catch (error: Throwable) {
                consentRequestInFlight = false
                lastStatus = "Privacy consent unavailable: ${error.message ?: error.javaClass.simpleName}"
                scheduleConsentRetry()
            }
        }
    }

    private fun initializeAds() {
        if (initializeRequested || activity.isFinishing || activity.isDestroyed) return
        initializeRequested = true
        mainHandler.removeCallbacks(consentRetry)
        lastStatus = "Consent checked; initializing AdMob…"
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
            mainHandler.removeCallbacks(rewardedRetry)
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
                                rewardedRetryDelayMs = 15_000L
                                mainHandler.removeCallbacks(rewardedRetry)
                                lastStatus = "Rewarded ad ready"
                            }
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            onMain {
                                rewardedLoading = false
                                rewardedAd = null
                                rewardedReady = false
                                lastStatus = "Rewarded ad unavailable: ${error.message} (code ${error.code}); retry scheduled"
                                scheduleRewardedRetry()
                            }
                        }
                    }
                )
            } catch (error: Throwable) {
                rewardedLoading = false
                rewardedAd = null
                rewardedReady = false
                lastStatus = "Rewarded ad request failed: ${error.message ?: error.javaClass.simpleName}; retry scheduled"
                scheduleRewardedRetry()
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
