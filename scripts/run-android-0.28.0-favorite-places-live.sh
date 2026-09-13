#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the deployed Compass API URL.}"

adb="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="$android_root/app/build/outputs/apk/debug/app-debug.apk"
installable="$repo_root/dist/Compass-0.28.0-debug-installabile.apk"
api_base="${COMPASS_API_BASE_URL%/}"
curl_auth=()
if [[ -n "${COMPASS_CHECK_USER:-}" || -n "${COMPASS_CHECK_PASSWORD:-}" ]]; then
    : "${COMPASS_CHECK_USER:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    : "${COMPASS_CHECK_PASSWORD:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    curl_auth=(--user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD")
fi

echo "[1/5] Checking the reachable Compass API"
curl --fail --silent --show-error "${curl_auth[@]}" "$api_base/health/live"

echo "[2/5] Running Android unit tests, lint and build"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="${api_base}/" \
        testDebugUnitTest lintDebug assembleDebug
)

mkdir -p "$repo_root/dist"
cp "$apk" "$installable"

echo "[3/5] Installing Android 0.28.0 without clearing private app data"
"$adb" install -r "$apk"

echo "[4/5] Cold-launching Compass"
"$adb" shell am force-stop org.compass.cng.debug
"$adb" shell am start -W org.compass.cng.debug/org.compass.cng.MainActivity

cat <<'CHECKS'

OPERATOR CHECK — FAVORITE PLACES

Run all eight cases in:
  docs/phases/android-0.28.0-favorite-places-acceptance.md

The key invariants are that endpoint selection is local, only the user-authored private label and
coordinate persist, and an ordinary-stop favorite still uses preview plus explicit confirmation.

Press ENTER after completing the checks.
CHECKS
read -r

echo "[5/5] Capturing bounded Android diagnostics"
"$adb" logcat -d -t 800 | grep -E 'Compass(Api|Navigation)|AndroidRuntime' | tail -n 140 || true

echo "ANDROID 0.28.0 FAVORITE PLACES LIVE GATE COMPLETE"
echo "Installable APK: $installable"
sha256sum "$installable"
echo "Return this complete output and pass/fail notes for all eight checks."
