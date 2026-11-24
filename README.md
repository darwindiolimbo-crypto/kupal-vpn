# Kupal VPN — Android

Kupal VPN is a native Kotlin/Jetpack Compose Android app with profile management, Xray JSON editing, HTTP payload-template preview, free-server source links, UMP consent, and AdMob timer controls.

## Features in source

- Compose dark UI with Home, Profiles, Config, Payload, Free Servers and Ads screens.
- Multiple saved profiles with protocol/server metadata and profile-specific config drafts.
- Share-link importer for **VLESS, VMess (Base64 JSON), Trojan, and SIP002/legacy Shadowsocks**. Common TCP, WebSocket, gRPC, HTTP/2, XHTTP, KCP, QUIC, TLS and REALITY settings are mapped into an Xray outbound draft. Unsupported transports are rejected; Shadowsocks plugin parameters are warned about and not imported.
- Xray JSON config editor with syntax validation.
- HTTP payload/setup editor with `[host]`, `[port]`, `[host_port]`, `[crlf]` placeholders and preview/copy. It is not a raw Xray transport injection feature.
- Xray integration path in `KupalVpnService`: Android VPN permission/service lifecycle, TUN creation, FD handoff, libXray run/stop, DNS and socket-protection callbacks. HTTP mode has a separate outbound/routing setup; SSH mode is saved but the SSH engine is not implemented.
- Free Test Servers source link: Free Nodes Radar V2Ray subscription `https://radarhub.top/v2ray.txt`. Subscription fetching/parsing/import remains pending. Public nodes are operated by unknown parties, may be malicious or offline, and are not privacy-safe. They have **not** been verified from your region. Do not use for banking, email, or sensitive traffic.
- UMP consent information is refreshed at launch, consent forms are shown when required, ad requests are gated by `canRequestAds()`, and a privacy-options entry point is provided.
- Timed interstitial controls (15–120 min, default 30) only unlock a manual show action; interstitials are never auto-shown and are blocked while VPN is connected.
- Rewarded video remains a **Google test unit** and grants temporary VPN time in its earned-reward callback. A production rewarded unit ID was not provided and is not invented.

## AdMob configuration

Release configuration is wired with the provided IDs:

- App ID: `ca-app-pub-9471897591250086~3475722796`
- Interstitial unit: `ca-app-pub-9471897591250086/2836147281`

Debug builds override these with Google test IDs (test App ID and interstitial unit) to avoid serving live ads during development. The rewarded video still uses Google's official rewarded test unit. These are AdMob App/Ad unit IDs, not the AdSense publisher ID. Set up UMP consent messages in AdMob before publishing and never click production ads during tests.

## Build

The GitHub Actions workflow builds the pinned upstream XTLS/libXray Android AAR, then assembles the debug APK. It also runs parser JVM tests.

Local build requires JDK 17, Go, Python 3, Git, Android SDK Platform 35, Android NDK and Gradle 8.13. The native AAR is not committed.

```bash
./scripts/build-libxray-android.sh
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

The native build script pins libXray `v26.11.9`. Native AAR class/API binding, TUN behavior, DNS/socket protection, routing, and disconnect lifecycle still require a successful Android build and device test. Do not treat the VPN as production-ready until those tests pass.

## Links

- [Android VPN Service docs](https://developer.android.com/develop/connectivity/vpn)
- [Xray TUN inbound](https://xtls.github.io/en/config/inbounds/tun.html)
- [libXray v26.11.9](https://github.com/XTLS/libXray/releases/tag/v26.11.9)
- [Google Mobile Ads Android](https://developers.google.com/admob/android/quick-start)
- [Google UMP](https://developers.google.com/admob/android/privacy)
- [Free Nodes Radar subscription](https://radarhub.top/v2ray.txt)
- [NodesRadar](https://nodesradar.com/)
