#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the reachable Compass API URL.}"

api_base="${COMPASS_API_BASE_URL%/}"
adb="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="$android_root/app/build/outputs/apk/debug/app-debug.apk"
installable="$repo_root/dist/Compass-0.27.0-debug-installabile.apk"
metrics_before=/tmp/compass-0.27.0-along-route-before.json
metrics_after=/tmp/compass-0.27.0-along-route-after.json

auth=()
if [[ -n "${COMPASS_CHECK_USER:-}" || -n "${COMPASS_CHECK_PASSWORD:-}" ]]; then
    : "${COMPASS_CHECK_USER:?Set both Compass credentials.}"
    : "${COMPASS_CHECK_PASSWORD:?Set both Compass credentials.}"
    auth=(--user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD")
fi

echo "[1/6] Checking API and dedicated Search Along Route contract"
curl --fail --silent --show-error "${auth[@]}" "$api_base/health/live"
curl --fail --silent --show-error "${auth[@]}" \
    "$api_base/api/v1/places/search-along-route/metrics" >"$metrics_before"
python3 - "$api_base" "${COMPASS_CHECK_USER:-}" "${COMPASS_CHECK_PASSWORD:-}" <<'PY'
import json, sys, urllib.request
base, username, password = sys.argv[1:]
request = urllib.request.Request(base + "/openapi.json")
if username or password:
    import base64
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    request.add_header("Authorization", "Basic " + token)
with urllib.request.urlopen(request, timeout=30) as response:
    schema = json.load(response)
assert "/api/v1/places/search-along-route" in schema["paths"]
assert "/api/v1/places/search-along-route/resolve" in schema["paths"]
print("search_along_route_openapi=ok")
PY

echo "[2/6] Running local backend and Android tests"
(
    cd "$repo_root"
    API_AUTH_ENABLED=false DESTINATION_SEARCH_PROVIDERS=none GOOGLE_PLACES_ENABLED=false \
    GOOGLE_PLACES_TEXT_SEARCH_ENABLED=false DESTINATION_ALONG_ROUTE_ENABLED=false \
    GOOGLE_PLACES_CONTRACT_REGIME=unverified GEOCODING_PROVIDER=nominatim \
        .venv/bin/pytest -q tests/test_along_route_search.py tests/test_destination_search.py
)
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base/" \
        testDebugUnitTest lintDebug assembleDebug
)

mkdir -p "$repo_root/dist"
cp "$apk" "$installable"

echo "[3/6] Installing Android 0.27.0 without clearing persistent profiles"
"$adb" install -r "$apk"

echo "[4/6] Cold-launching Compass"
"$adb" shell am force-stop org.compass.cng.debug
"$adb" shell am start -W -n org.compass.cng.debug/org.compass.cng.MainActivity

cat <<'CHECKS'

OPERATOR CHECK — SEARCH ALONG THE SELECTED ROUTE

Follow every case in docs/phases/android-0.27.0-search-along-route-acceptance.md. In particular:
create a route, verify ordinary destination search is unchanged, open Aggiungi tappa, search a
common service along the route, select it, inspect the Valhalla insertion preview, cancel once, then
repeat and confirm. The original final destination and all existing stops must remain present.

Press ENTER only after recording pass/fail for all ten cases.
CHECKS
read -r

echo "[5/6] Capturing aggregate metrics"
curl --fail --silent --show-error "${auth[@]}" \
    "$api_base/api/v1/places/search-along-route/metrics" >"$metrics_after"
python3 - "$metrics_before" "$metrics_after" <<'PY'
import json, sys
before, after = (json.load(open(path, encoding="utf-8")) for path in sys.argv[1:])
assert after["provider_calls"] > before["provider_calls"]
assert after["results_returned"] >= before["results_returned"]
assert after["resolutions_succeeded"] > before["resolutions_succeeded"]
print(json.dumps(after, separators=(",", ":")))
PY

echo "[6/6] SEARCH ALONG ROUTE LIVE GATE COMPLETE"
echo "Installable APK: $installable"
echo "Return this output and the ten pass/fail notes."
