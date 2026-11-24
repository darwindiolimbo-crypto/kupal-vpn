# Xray native Android engine

The app uses the upstream XTLS/libXray Android binding. The generated AAR is not committed.

## Build

On Linux/macOS with JDK 17, Go, Python 3, Git, Android SDK and NDK:

```bash
./scripts/build-libxray-android.sh
```

The script pins upstream libXray v26.11.9 and runs its Android build script. The native AAR is generated in the project root's app/libs folder.

## Runtime

KupalVpnService creates the Android TUN, passes its FD through Xray config, calls libXray run/stop, and registers socket-protection and DNS callbacks. The generated binding, routing, DNS and disconnect behavior still require a successful Android build and device test.
