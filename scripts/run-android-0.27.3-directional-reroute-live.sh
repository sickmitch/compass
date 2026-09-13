#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the deployed Compass API URL.}"

adb="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="$android_root/app/build/outputs/apk/debug/app-debug.apk"
installable="$repo_root/dist/Compass-0.27.3-debug-installabile.apk"
api_base="${COMPASS_API_BASE_URL%/}"
curl_auth=()
if [[ -n "${COMPASS_CHECK_USER:-}" || -n "${COMPASS_CHECK_PASSWORD:-}" ]]; then
    : "${COMPASS_CHECK_USER:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    : "${COMPASS_CHECK_PASSWORD:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    curl_auth=(--user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD")
fi

echo "[1/6] Checking the deployed directional route contract"
openapi_file="$(mktemp)"
trap 'rm -f "$openapi_file"' EXIT
curl --fail --silent --show-error "${curl_auth[@]}" "$api_base/openapi.json" -o "$openapi_file"
python3 - "$openapi_file" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
schema = document["components"]["schemas"]["BaseRouteRequest"]
properties = schema["properties"]
required = {"origin_heading_degrees", "origin_heading_tolerance_degrees"}
missing = required.difference(properties)
if missing:
    raise SystemExit(f"deployed API is missing fields: {sorted(missing)}")
print("directional_route_contract=ok")
PY

echo "[2/6] Running backend routing/API tests"
(
    cd "$repo_root"
    API_AUTH_ENABLED=false PYTHONPATH=src \
        .venv/bin/pytest tests/test_valhalla.py tests/test_api.py -q
)

echo "[3/6] Running Android tests, lint and build"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="${api_base}/" \
        testDebugUnitTest lintDebug assembleDebug
)

mkdir -p "$repo_root/dist"
cp "$apk" "$installable"

echo "[4/6] Installing Android 0.27.3 without clearing persistent profiles"
"$adb" install -r "$apk"

echo "[5/6] Cold-launching Compass"
"$adb" shell am force-stop org.compass.cng.debug
"$adb" shell am start -W org.compass.cng.debug/org.compass.cng.MainActivity

cat <<'CHECKS'

OPERATOR CHECK — DIRECTIONAL OFF-ROUTE RECALCULATION

Run all seven cases in:
  docs/phases/android-0.27.3-directional-reroute-acceptance.md

The key invariant is that a moving off-route recalculation starts in the observed direction of
travel, including with ordinary and CNG stops, while low-speed and non-deviation refreshes remain
able to route normally.

Press ENTER after completing the checks.
CHECKS
read -r

echo "[6/6] Capturing bounded Android diagnostics"
"$adb" logcat -d -t 1200 | grep -E 'Compass(Api|Navigation)|REROUT|OFF_ROUTE' | tail -n 160 || true

echo "ANDROID 0.27.3 DIRECTIONAL REROUTE LIVE GATE COMPLETE"
echo "Installable APK: $installable"
sha256sum "$installable"
echo "Return this complete output and pass/fail notes for all seven checks."
