#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the deployed Compass API URL.}"

adb="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="$android_root/app/build/outputs/apk/debug/app-debug.apk"
installable="$repo_root/dist/Compass-0.28.3-debug-installabile.apk"
api_base="${COMPASS_API_BASE_URL%/}"
curl_auth=()
if [[ -n "${COMPASS_CHECK_USER:-}" || -n "${COMPASS_CHECK_PASSWORD:-}" ]]; then
    : "${COMPASS_CHECK_USER:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    : "${COMPASS_CHECK_PASSWORD:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    curl_auth=(--user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD")
fi

echo "[1/6] Checking API and new search contract"
curl --fail --silent --show-error "${curl_auth[@]}" "$api_base/health/live"
curl --fail --silent --show-error "${curl_auth[@]}" "$api_base/openapi.json" |
    python3 -c 'import json,sys; d=json.load(sys.stdin); assert "/api/v1/places/search-along-route" in d["paths"]'

echo "[2/6] Running local backend search tests"
(
    cd "$repo_root"
    .venv/bin/pytest -q tests/test_destination_search.py tests/test_along_route_search.py
)

echo "[3/6] Running Android tests, lint and build"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="${api_base}/" \
        testDebugUnitTest lintDebug assembleDebug
)

mkdir -p "$repo_root/dist"
cp "$apk" "$installable"

echo "[4/6] Installing Android 0.28.3 without clearing private app data"
"$adb" install -r "$apk"

echo "[5/6] Cold-launching Compass"
"$adb" shell am force-stop org.compass.cng.debug
"$adb" shell am start -W org.compass.cng.debug/org.compass.cng.MainActivity

cat <<'CHECKS'

OPERATOR CHECK — SEARCH/TIME ORCHESTRATION

Run all eight checks in:
  docs/phases/android-0.28.3-search-time-orchestration-acceptance.md

Do not paste secrets. Press ENTER after completing the checks.
CHECKS
read -r

echo "[6/6] Capturing aggregate search metrics and bounded Android diagnostics"
curl --fail --silent --show-error "${curl_auth[@]}" \
    "$api_base/api/v1/destinations/metrics"
curl --fail --silent --show-error "${curl_auth[@]}" \
    "$api_base/api/v1/places/search-along-route/metrics"
"$adb" logcat -d -t 1200 | grep -E 'Compass(Api|Navigation)|AndroidRuntime' | tail -n 180 || true

echo "ANDROID 0.28.3 SEARCH/TIME LIVE GATE COMPLETE"
echo "Installable APK: $installable"
sha256sum "$installable"
echo "Return this output and pass/fail notes for all eight checks."
