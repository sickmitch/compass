#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the reachable Compass API URL.}"

adb="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="$android_root/app/build/outputs/apk/debug/app-debug.apk"
installable="$repo_root/dist/Compass-0.27.1-debug-installabile.apk"
api_base="${COMPASS_API_BASE_URL%/}/"

echo "[1/4] Running Android unit tests, lint and APK build"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base" \
        testDebugUnitTest lintDebug assembleDebug
)

mkdir -p "$repo_root/dist"
cp "$apk" "$installable"

echo "[2/4] Installing Android 0.27.1 without clearing persistent profiles"
"$adb" install -r "$apk"

echo "[3/4] Cold-launching Compass"
"$adb" shell am force-stop org.compass.cng.debug
"$adb" shell am start -W -n org.compass.cng.debug/org.compass.cng.MainActivity

cat <<'CHECKS'

OPERATOR CHECK — FOLLOW, LOCAL SEARCH, OLED AND PERSONALIZATION

Run all six checks in:
  docs/phases/android-0.27.1-follow-search-ui-fixes-acceptance.md

In particular, wait for the initial follow fix, toggle Android location off/on to validate periodic
reacquisition, search a generic place category near the physical device, inspect OLED dark surfaces,
and verify the two-row personalization layout.

Press ENTER only after recording pass/fail for every check.
CHECKS
read -r

echo "[4/4] ANDROID 0.27.1 FOLLOW/SEARCH/UI LIVE GATE COMPLETE"
echo "Installable APK: $installable"
sha256sum "$installable"
echo "Return this output and the six pass/fail notes."
