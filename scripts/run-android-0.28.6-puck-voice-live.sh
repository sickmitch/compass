#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the deployed Compass API URL.}"

adb="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="$android_root/app/build/outputs/apk/debug/app-debug.apk"
installable="$repo_root/dist/Compass-0.28.6-debug-installabile.apk"
api_base="${COMPASS_API_BASE_URL%/}"

echo "[1/4] Running focused navigation tests"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="${api_base}/" \
        testDebugUnitTest \
        --tests org.compass.cng.navigation.NavigationStage3Test \
        --tests org.compass.cng.ui.route.NavigationDrivingUiModelTest
)

echo "[2/4] Running lint and building Android 0.28.6"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="${api_base}/" lintDebug assembleDebug
)
mkdir -p "$repo_root/dist"
cp "$apk" "$installable"

echo "[3/4] Installing without clearing app data"
"$adb" install -r "$apk"
"$adb" logcat -c
"$adb" shell am force-stop org.compass.cng.debug
"$adb" shell am start -W org.compass.cng.debug/org.compass.cng.MainActivity

cat <<'CHECKS'

OPERATOR CHECK — PUCK AND VOICE

Run the three checks in:
  docs/phases/android-0.28.6-puck-voice-refinement-acceptance.md

Leave navigation active and press ENTER.
CHECKS
read -r

echo "[4/4] Capturing bounded voice-stage diagnostics"
"$adb" logcat -d -v time -s 'CompassNavigation:I' 'AndroidRuntime:E' '*:S' |
    tail -n 200 || true

echo "ANDROID 0.28.6 PUCK/VOICE LIVE GATE COMPLETE"
echo "Installable APK: $installable"
sha256sum "$installable"
echo "Return this output and pass/fail notes for all three checks."

