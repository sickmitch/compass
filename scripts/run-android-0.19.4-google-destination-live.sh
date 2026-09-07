#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"

: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the public Compass API base URL.}"

api_base="${COMPASS_API_BASE_URL%/}"
if [[ "$api_base" != https://* && "${COMPASS_ALLOW_HTTP_LIVE_GATE:-false}" != "true" ]]; then
    echo "ERROR: HTTPS is required. Set COMPASS_ALLOW_HTTP_LIVE_GATE=true only for an explicit HTTP fallback." >&2
    exit 1
fi

adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"

[[ -x "$JAVA_HOME/bin/java" ]] || { echo "ERROR: JDK 17 not found." >&2; exit 1; }
[[ -x "$adb_binary" ]] || { echo "ERROR: adb not found." >&2; exit 1; }

adb_args=()
if [[ -n "${COMPASS_ADB_SERIAL:-}" ]]; then
    adb_args=(-s "$COMPASS_ADB_SERIAL")
else
    mapfile -t devices < <("$adb_binary" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ "${#devices[@]}" -ne 1 ]]; then
        echo "ERROR: expected exactly one authorized Android device; found ${#devices[@]}." >&2
        "$adb_binary" devices -l >&2
        exit 1
    fi
    adb_args=(-s "${devices[0]}")
fi

curl_auth=()
if [[ -n "${COMPASS_CHECK_USER:-}" || -n "${COMPASS_CHECK_PASSWORD:-}" ]]; then
    : "${COMPASS_CHECK_USER:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    : "${COMPASS_CHECK_PASSWORD:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    curl_auth=(--user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD")
fi

before_metrics=/tmp/compass-0.19.4-destination-metrics-before.json
after_metrics=/tmp/compass-0.19.4-destination-metrics-after.json

echo "[1/6] Checking API and Google-only destination registry"
curl --fail --silent --show-error "${curl_auth[@]}" "$api_base/health/live" >/dev/null
curl --fail --silent --show-error "${curl_auth[@]}" \
    "$api_base/api/v1/destinations/metrics" | tee "$before_metrics"
python3 - "$before_metrics" <<'PY'
import json
import sys

metrics = json.load(open(sys.argv[1], encoding="utf-8"))
if metrics["tomtom_destination_calls"] != 0:
    raise SystemExit("ERROR: TomTom destination calls were observed before the gate")
PY

echo "[2/6] Running local destination-search unit tests"
(cd "$android_root" && ./gradlew --no-daemon \
    -PCOMPASS_API_BASE_URL="$api_base/" \
    testDebugUnitTest \
    --tests org.compass.cng.data.api.CompassApiClientTest \
    --tests org.compass.cng.ui.route.RoutePlannerViewModelTest)

echo "[3/6] Building Android 0.19.4"
(cd "$android_root" && ./gradlew --no-daemon \
    -PCOMPASS_API_BASE_URL="$api_base/" assembleDebug)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }

echo "[4/6] Installing without clearing persistent server/vehicle profiles"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[5/6] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR VISUAL CHECK — GOOGLE PLACES NEW DESTINATIONS

Run the nine manual cases in:
  docs/phases/android-0.19.4-google-destination-search-acceptance.md

In particular, confirm that search results and Google addresses are shown only on mapless screens;
after route preview opens, MapLibre uses “Destinazione selezionata”. Confirm no business marker or
Google text is placed on the map. Screenshots are optional. No UIAutomator or screenshot inspection
is performed; your visual report is the acceptance evidence.

When the cases are complete, press ENTER to capture final aggregate metrics.
CHECKS
read -r

echo "[6/6] Capturing final metrics"
curl --fail --silent --show-error "${curl_auth[@]}" \
    "$api_base/api/v1/destinations/metrics" | tee "$after_metrics"
python3 - "$before_metrics" "$after_metrics" <<'PY'
import json
import sys

before = json.load(open(sys.argv[1], encoding="utf-8"))
after = json.load(open(sys.argv[2], encoding="utf-8"))
if after["tomtom_destination_calls"] != 0:
    raise SystemExit("ERROR: TomTom destination search was called")
if after["suggest_requests"] <= before["suggest_requests"]:
    raise SystemExit("ERROR: no Google destination suggestion request was recorded")
if after["resolutions_succeeded"] <= before["resolutions_succeeded"]:
    raise SystemExit("ERROR: no destination resolution succeeded")
print("google_destination_gate_metrics=ok")
PY

echo
echo "ANDROID 0.19.4 GOOGLE DESTINATION LIVE GATE COMPLETE"
echo "Return this complete output and pass/fail notes for all manual cases."
echo "Artifacts: $before_metrics $after_metrics"
