#!/usr/bin/env bash
set -euo pipefail
# Build upstream libXray Android AAR from a pinned release.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBXRAY_VERSION="${LIBXRAY_VERSION:-v26.11.9}"
LIBXRAY_ANDROID_API="${LIBXRAY_ANDROID_API:-26}"
TMP_DIR="${TMPDIR:-/tmp}/kupal-libxray-${LIBXRAY_VERSION}"

for tool in git python3 go; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" ]]; then echo "Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2; exit 1; fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -d "$SDK_DIR/ndk/28.2.13676358" ]]; then export ANDROID_NDK_HOME="$SDK_DIR/ndk/28.2.13676358"
  else for candidate in "$SDK_DIR"/ndk/*; do [[ -d "$candidate" ]] && export ANDROID_NDK_HOME="$candidate"; done; fi
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then echo "Install Android NDK and set ANDROID_NDK_HOME." >&2; exit 1; fi

rm -rf "$TMP_DIR"
git clone --depth 1 --branch "$LIBXRAY_VERSION" https://github.com/XTLS/libXray.git "$TMP_DIR"
(cd "$TMP_DIR" && LIBXRAY_ANDROID_API="$LIBXRAY_ANDROID_API" python3 build/main.py android)
AAR="$TMP_DIR/libXray.aar"
[[ -f "$AAR" ]] || { echo "Upstream build did not produce $AAR" >&2; exit 1; }
mkdir -p "$ROOT/app/libs"
cp "$AAR" "$ROOT/app/libs/libXray.aar"
if [[ -f "$TMP_DIR/libXray-sources.jar" ]]; then cp "$TMP_DIR/libXray-sources.jar" "$ROOT/app/libs/libXray-sources.jar"; fi
echo "libXray AAR installed: $ROOT/app/libs/libXray.aar"
echo "Then run: gradle :app:assembleDebug"