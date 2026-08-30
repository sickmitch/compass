#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
validator="$repo_root/scripts/validate-phase10-live.py"
standard_request="/tmp/compass-phase10-standard-request.json"
standard_response="/tmp/compass-phase10-standard-response.json"
not_needed_request="/tmp/compass-phase10-not-needed-request.json"
not_needed_response="/tmp/compass-phase10-not-needed-response.json"
unreachable_request="/tmp/compass-phase10-unreachable-request.json"
unreachable_response="/tmp/compass-phase10-unreachable-response.json"
multi_stop_request="/tmp/compass-phase10-multi-stop-request.json"
multi_stop_response="/tmp/compass-phase10-multi-stop-response.json"
selected_request="/tmp/compass-phase10-itinerary-route-request.json"
selected_response="/tmp/compass-phase10-itinerary-route-response.json"
openapi_response="/tmp/compass-phase10-openapi.json"
launch_output="/tmp/compass-phase10-android-launch.txt"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"

if [[ "$api_base_url" != */ ]]; then
    api_base_url="${api_base_url}/"
fi

: "${JAVA_HOME:?Set JAVA_HOME to the JDK 17 installation before running this script.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK containing platform 37.}"

java_binary="$JAVA_HOME/bin/java"
adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id="org.compass.cng.debug"
activity_component="$application_id/org.compass.cng.MainActivity"

if [[ ! -x "$java_binary" ]]; then
    echo "ERROR: JDK executable not found at $java_binary" >&2
    exit 1
fi
if [[ ! -x "$adb_binary" ]]; then
    echo "ERROR: adb executable not found at $adb_binary" >&2
    exit 1
fi
if [[ ! -x "$android_root/gradlew" ]]; then
    echo "ERROR: Gradle wrapper is missing or not executable." >&2
    exit 1
fi
if [[ ! -f "$validator" ]]; then
    echo "ERROR: Phase 10 validator is missing at $validator" >&2
    exit 1
fi

case "$api_base_url" in
    http://127.0.0.1:8000/)
        use_adb_reverse=true
        echo "Using the loopback API at $api_base_url"
        echo "If the backend is on another server, keep this SSH tunnel open in a separate terminal:"
        echo "  ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER"
        echo
        ;;
    https://*)
        use_adb_reverse=false
        ;;
    *)
        echo "ERROR: use http://127.0.0.1:8000/ with adb reverse, or an HTTPS API URL." >&2
        exit 1
        ;;
esac

echo "[1/12] Checking backend readiness at ${api_base_url}health/ready"
if ! curl --fail --silent --show-error "${api_base_url}health/ready"; then
    echo >&2
    echo "ERROR: Compass is not reachable at $api_base_url" >&2
    if [[ "$use_adb_reverse" == true ]]; then
        echo "If the backend is remote, open the SSH tunnel shown above and rerun the script." >&2
    fi
    exit 1
fi
echo

echo "[2/12] Checking that the live API exposes the corrected Phase 10 contract"
curl --fail-with-body --silent --show-error \
    --output "$openapi_response" \
    "${api_base_url}openapi.json"
python3 "$validator" validate-openapi --openapi "$openapi_response"

echo "[3/12] Checking the standard 120/30/300 predictive itinerary"
python3 "$validator" prepare --profile standard --output "$standard_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$standard_request" \
    --output "$standard_response" \
    "${api_base_url}api/v1/cng/predictive-candidates"
echo "Standard itinerary response saved to $standard_response"

echo "[4/12] Checking that a reachable destination suppresses refuelling"
python3 "$validator" prepare --profile not-needed --output "$not_needed_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$not_needed_request" \
    --output "$not_needed_response" \
    "${api_base_url}api/v1/cng/predictive-candidates"
echo "Not-needed response saved to $not_needed_response"

echo "[5/12] Checking the explicit no-reachable-station safety state"
python3 "$validator" prepare --profile unreachable --output "$unreachable_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$unreachable_request" \
    --output "$unreachable_response" \
    "${api_base_url}api/v1/cng/predictive-candidates"
echo "Unreachable response saved to $unreachable_response"

echo "[6/12] Checking the reported 65/30/100 multi-refuelling edge case"
python3 "$validator" prepare --profile multi-stop --output "$multi_stop_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$multi_stop_request" \
    --output "$multi_stop_response" \
    "${api_base_url}api/v1/cng/predictive-candidates"
echo "Multi-stop itinerary response saved to $multi_stop_response"

echo "[7/12] Recalculating and validating the entire multi-stop route"
python3 "$validator" prepare-itinerary-route \
    --predictive "$multi_stop_response" \
    --output "$selected_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$selected_request" \
    --output "$selected_response" \
    "${api_base_url}api/v1/routes/with-cng-itinerary"
python3 "$validator" validate \
    --standard "$standard_response" \
    --not-needed "$not_needed_response" \
    --unreachable "$unreachable_response" \
    --multi-stop "$multi_stop_response" \
    --selected-route "$selected_response"

adb_args=()
if [[ -n "${COMPASS_ADB_SERIAL:-}" ]]; then
    adb_args=(-s "$COMPASS_ADB_SERIAL")
else
    mapfile -t connected_devices < <(
        "$adb_binary" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
    )
    if [[ "${#connected_devices[@]}" -ne 1 ]]; then
        echo "ERROR: expected exactly one authorized Android device; found ${#connected_devices[@]}." >&2
        echo "Set COMPASS_ADB_SERIAL when more than one device is connected." >&2
        "$adb_binary" devices -l >&2
        exit 1
    fi
    adb_args=(-s "${connected_devices[0]}")
fi

echo "[8/12] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
    echo "adb reverse active: device tcp:8000 -> build host tcp:8000"
fi

echo "[9/12] Running unit tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest \
        lintDebug \
        assembleDebug
)

if [[ ! -f "$apk_path" ]]; then
    echo "ERROR: expected APK was not generated at $apk_path" >&2
    exit 1
fi

echo "[10/12] Installing $apk_path"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[11/12] Launching Compass"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo "[12/12] Checking the launched process for an immediate fatal exception"
sleep 2
if "$adb_binary" "${adb_args[@]}" logcat -d -t 300 \
    | grep -E 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' >/dev/null; then
    echo "ERROR: a fatal Compass exception was found after launch." >&2
    "$adb_binary" "${adb_args[@]}" logcat -d -t 300 >&2
    exit 1
fi

echo
echo "AUTOMATED PHASE 10 DEVICE CHECKS COMPLETED"
echo
echo "MANUAL DEVICE ACCEPTANCE — follow these scenarios in this order"
echo
echo "Scenario A — ordinary predictive plan"
echo "  From the route preview tap 'Valuta autonomia CNG'."
echo "  Enter exactly: residual range 120 km, reserve 30 km, full range 300 km,"
echo "  maximum detour 10 minutes. Then tap the evaluation button."
echo "  Expected: a screen titled 'Piano rifornimenti CNG'. It must show one or more"
echo "  ordered refuelling stops, each previous-leg ROAD distance, arrival time,"
echo "  remaining range and a non-negative margin over the 30 km reserve."
echo
echo "Scenario B — destination already reachable"
echo "  Go back to the range form. Enter residual 300, reserve 30, full range 300,"
echo "  detour 10. Evaluate again."
echo "  Expected: 'Rifornimento non necessario', remaining route about 210.9 km and"
echo "  usable range 270 km. No station list or refuelling plan may be shown."
echo
echo "Scenario C — no first station is safely reachable"
echo "  Go back. Enter residual 31, reserve 30, full range 300, detour 10. Evaluate."
echo "  Expected: 'Nessuna stazione raggiungibile' and usable range 1 km. The screen"
echo "  must explicitly warn not to rely on the itinerary."
echo
echo "Scenario D — mandatory multi-refuelling regression (the reported defect)"
echo "  Go back. Enter residual 65, reserve 30, full range 100, detour 10. Evaluate."
echo "  Expected: a COMPLETE plan, not a list of independent first-stop options."
echo "  On Milan–Bologna it must contain at least 3 ordered CNG stops. The first leg"
echo "  may use at most 35 km before reserve; every later leg may use at most 70 km."
echo "  Every displayed reserve margin, including Bologna, must be zero or positive."
echo "  The screen must say that a full refill to 100 km is assumed after each stop."
echo
echo "Scenario E — route through every planned stop"
echo "  In Scenario D tap 'Calcola percorso con N soste'."
echo "  Expected: every planned stop appears on the map and in the ordered stop card."
echo "  The maneuver list is divided into origin→stop 1, stop→stop sections and the"
echo "  final stop→Bologna section. It must say every leg preserves the reserve."
echo
echo "Lifecycle check"
echo "  Rotate once on the multi-stop plan, send Compass to the background, resume it,"
echo "  and scroll the selected itinerary. There must be no crash or lost stop chain."
echo
echo "Return the COMPLETE output of this script and exactly these four screenshots:"
echo "  1. Scenario D, with the ordered multi-stop plan and 65/30/100 values visible."
echo "  2. Scenario E, with multiple stop markers and the verified route sections."
echo "  3. Scenario B, the explicit 'Rifornimento non necessario' state."
echo "  4. Scenario C, the explicit 'Nessuna stazione raggiungibile' warning."
