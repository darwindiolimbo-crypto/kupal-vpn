# Kupal VPN — Android starter project

This is the first Android Studio source package for the Kupal VPN app concept. It includes the payload/configuration editing screens and the timed Google AdMob integration requested for the next iteration.

## Included now

- Jetpack Compose dark UI with Home, Config, Payload, and Ads tabs.
- Local Xray JSON draft editor with JSON syntax validation.
- HTTP payload template editor with `[host]`, `[port]`, `[host_port]`, and `[crlf]` placeholders and a rendered preview.
- Google Mobile Ads SDK (25.5.0) integration using Google's official test App ID and test interstitial unit.
- Google UMP consent request flow and privacy-options entry point.
- Configurable 15–120 minute ad timer. The timer makes an ad eligible; the sample only shows an interstitial when the user presses the test button, the timer has elapsed, and the VPN preview state is disconnected.
- Local persistence of the JSON draft, payload, and ad timer settings.
- Free Servers tab with a copyable public V2Ray subscription URL for use in external compatible clients (not yet imported into this app).

## Important: current build status

This package is a **UI and feature-integration starter**, not yet a working VPN client. The Xray-core native library, Android `VpnService` packet loop, TUN file-descriptor handoff, traffic routing, and live connection statistics have not yet been integrated. The Home screen deliberately labels its Connect button as a preview so it does not claim to establish a real tunnel.

The payload editor stores and previews an HTTP request template. It does not inject arbitrary payload text into VLESS, VMess, Trojan, or Xray transports. Xray transport settings must be represented in the Xray JSON profile or a protocol-specific adapter.

## Open in Android Studio

1. Extract this ZIP.
2. Open the `KupalVPN-Android` folder in Android Studio.
3. Use JDK 17 and install Android SDK Platform 35.
4. Let Gradle sync download the Android, Compose, Google Mobile Ads, and UMP dependencies.
5. Run the `app` configuration on an Android device or emulator (Android 8.0/API 26 or newer).

The project uses Android Gradle Plugin 8.7.3 and Kotlin 2.0.21. A Gradle wrapper is not included in this archive. For a Termux-only build, install/configure JDK 17, Gradle, Android SDK Platform 35 and matching Build Tools, then set `ANDROID_HOME`/`ANDROID_SDK_ROOT` before running `gradle :app:assembleDebug`. These SDK tools are not bundled here, and this Termux build path has not yet been tested in this environment. Android Studio is the easiest alternative when available.

## AdMob setup before publishing

Development currently uses Google's sample IDs:

- Sample AdMob App ID: `ca-app-pub-3940256099942544~3347511713`
- Sample Android interstitial ad unit: `ca-app-pub-3940256099942544/1033173712`

Before release:

1. Register Kupal VPN in your AdMob account.
2. Replace the sample App ID in `app/src/main/AndroidManifest.xml`.
3. Replace `TEST_INTERSTITIAL_AD_UNIT_ID` in `AdsController.kt` with your own interstitial ad unit ID.
4. Configure the required privacy/consent messages in AdMob's Privacy & messaging area and verify the UMP flow in all target regions.
5. Test with Google's test IDs until the app is ready for production. Never click live ads during testing.

The sample timer defaults to 30 minutes, with a user-configurable interval from 15 to 120 minutes. Interstitials should be shown at natural app transitions and not used to block VPN connection controls. This implementation refuses to show while the VPN preview reports connected.

## Next engineering stage

To make it a real VPN app, the next implementation stage is to integrate Xray-core as an Android native library, implement `VpnService` lifecycle and TUN FD handoff, add config import (VLESS/VMess/Trojan/SS links and QR), add actual tunnel status/traffic counters, then build and test the APK on the target phones.

## Official references

- Google Mobile Ads Android setup: https://developers.google.com/admob/android/quick-start
- Google test ads: https://developers.google.com/admob/android/test-ads
- Google UMP consent SDK: https://developers.google.com/admob/android/privacy
- Android VPN guide: https://developer.android.com/develop/connectivity/vpn
- Xray-core TUN Android notes: https://github.com/XTLS/Xray-core/tree/main/proxy/tun


## Build on GitHub Actions

A GitHub Actions workflow is configured at `.github/workflows/android-build.yml`. It runs on pushes to `main`, pull requests targeting `main`, or manual dispatch from the Actions tab. It builds a debug APK with JDK 17 and Gradle 8.9, then uploads the APK as the `KupalVPN-debug` artifact (available for 14 days).


