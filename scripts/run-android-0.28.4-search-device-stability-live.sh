#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the deployed Compass API URL.}"

adb="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="$android_root/app/build/outputs/apk/debug/app-debug.apk"
installable="$repo_root/dist/Compass-0.28.4-debug-installabile.apk"
api_base="${COMPASS_API_BASE_URL%/}"
curl_auth=()
if [[ -n "${COMPASS_CHECK_USER:-}" || -n "${COMPASS_CHECK_PASSWORD:-}" ]]; then
    : "${COMPASS_CHECK_USER:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    : "${COMPASS_CHECK_PASSWORD:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    curl_auth=(--user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD")
fi

echo "[1/6] Checking API and along-route contract"
curl --fail --silent --show-error "${curl_auth[@]}" "$api_base/health/live"
curl --fail --silent --show-error "${curl_auth[@]}" "$api_base/openapi.json" |
    python3 -c 'import json,sys; d=json.load(sys.stdin); assert "/api/v1/places/search-along-route" in d["paths"]'

echo "[2/6] Running focused backend tests"
(
    cd "$repo_root"
    env API_AUTH_ENABLED=false PYTHONPATH=src .venv/bin/pytest -q \
        tests/test_api.py tests/test_destination_search.py tests/test_along_route_search.py
)

echo "[3/6] Running Android tests, lint and build"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="${api_base}/" \
        testDebugUnitTest lintDebug assembleDebug
)
mkdir -p "$repo_root/dist"
cp "$apk" "$installable"

echo "[4/6] Installing Android 0.28.4 without clearing app data"
"$adb" install -r "$apk"
"$adb" logcat -c

echo "[5/6] Cold-launching Compass"
"$adb" shell am force-stop org.compass.cng.debug
"$adb" shell am start -W org.compass.cng.debug/org.compass.cng.MainActivity

cat <<'CHECKS'

OPERATOR CHECK — SEARCH DEVICE STABILITY

Run all four checks in:
  docs/phases/android-0.28.4-search-device-stability-acceptance.md

Leave Compass open after the final along-route attempt and press ENTER.
CHECKS
read -r

echo "[6/6] Capturing bounded diagnostics"
curl --fail --silent --show-error "${curl_auth[@]}" \
    "$api_base/api/v1/places/search-along-route/metrics"
"$adb" logcat -d -v time -s 'CompassApi:I' 'CompassPlanner:I' 'AndroidRuntime:E' '*:S' |
    tail -n 240 || true

echo "ANDROID 0.28.4 SEARCH DEVICE-STABILITY LIVE GATE COMPLETE"
echo "Installable APK: $installable"
sha256sum "$installable"
echo "Return this output, the API log lines around any 422, and pass/fail notes for all four checks."
