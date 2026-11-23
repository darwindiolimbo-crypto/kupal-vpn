package com.kupalvpn.app

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Timer-gated AdMob controller.
 * Interstitials are only shown after the configured interval and only when the VPN is disconnected.
 * Google test ad IDs are used until the app owner supplies real AdMob IDs.
 */
class AdsController(private val activity: Activity) {
    private var interstitialAd: InterstitialAd? = null
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
                    if (formError != null) {
                        lastStatus = "Consent form: ${formError.message}"
                    }
                    if (consentInformation.canRequestAds()) {
                        initializeAds()
                    } else {
                        lastStatus = "Ads are unavailable until consent permits requests"
                    }
                }
            },
            { requestError ->
                lastStatus = "Consent update: ${requestError.message}"
                // UMP may still have a valid consent choice from an earlier session.
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
                    lastStatus = "AdMob initialized (test ads)"
                    loadInterstitial()
                }
            }
        }.start()
    }

    private fun loadInterstitial() {
        if (!initialized || interstitialAd != null) return
        InterstitialAd.load(
            activity,
            TEST_INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    lastStatus = "Test interstitial ready"
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    lastStatus = "Ad not available: ${error.message}"
                }
            }
        )
    }

    fun remainingMillis(intervalMinutes: Int): Long {
        val dueAt = LocalStore.adCycleStart(activity) + intervalMinutes.coerceIn(15, 120) * 60_000L
        return (dueAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun isAdDue(intervalMinutes: Int): Boolean = remainingMillis(intervalMinutes) == 0L

    /** Returns a user-readable result. This method never shows an ad over an active VPN. */
    fun tryShowDueInterstitial(vpnConnected: Boolean, intervalMinutes: Int): String {
        if (!LocalStore.adsEnabled(activity)) return "Ad timer is turned off"
        if (vpnConnected) return "Disconnect the VPN before showing an interstitial"
        if (!isAdDue(intervalMinutes)) return "The ad timer has not finished yet"
        if (!initialized) return "AdMob is still initializing or consent is unavailable"

        val ad = interstitialAd
        if (ad == null) {
            loadInterstitial()
            return "No ad is cached yet; try again when an ad is ready"
        }

        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                LocalStore.resetAdCycle(activity)
                lastStatus = "Test ad shown"
            }

            override fun onAdDismissedFullScreenContent() {
                lastStatus = "Ad closed"
                loadInterstitial()
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                lastStatus = "Ad failed to show: ${error.message}"
                loadInterstitial()
            }
        }
        ad.show(activity)
        return "Showing Google test ad"
    }

    fun showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            lastStatus = formError?.message ?: "Privacy options closed"
        }
    }

    companion object {
        // Google's official Android interstitial test unit. Replace before release.
        const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    }
}
