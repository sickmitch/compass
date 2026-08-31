#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
validator="$repo_root/scripts/validate-phase11-live.py"
custom_route_request="/tmp/compass-phase11-custom-route-request.json"
custom_route_response="/tmp/compass-phase11-custom-route-response.json"
launch_output="/tmp/compass-phase11-android-launch.txt"
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
    echo "ERROR: Phase 11 validator is missing at $validator" >&2
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

echo "[1/8] Checking backend readiness at ${api_base_url}health/ready"
if ! curl --fail --silent --show-error "${api_base_url}health/ready"; then
    echo >&2
    echo "ERROR: Compass is not reachable at $api_base_url" >&2
    if [[ "$use_adb_reverse" == true ]]; then
        echo "If the backend is remote, open the SSH tunnel shown above and rerun the script." >&2
    fi
    exit 1
fi
echo

echo "[2/8] Checking a non-default live route contract"
python3 "$validator" prepare-route --output "$custom_route_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$custom_route_request" \
    --output "$custom_route_response" \
    "${api_base_url}api/v1/routes"
python3 "$validator" validate-route --response "$custom_route_response"

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

echo "[3/8] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
    echo "adb reverse active: device tcp:8000 -> build host tcp:8000"
fi

echo "[4/8] Running unit tests, lint and debug APK assembly"
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

echo "[5/8] Installing $apk_path"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[6/8] Launching Compass"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo "[7/8] Checking the launched process for an immediate fatal exception"
sleep 2
if "$adb_binary" "${adb_args[@]}" logcat -d -t 300 \
    | grep -E 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' >/dev/null; then
    echo "ERROR: a fatal Compass exception was found after launch." >&2
    "$adb_binary" "${adb_args[@]}" logcat -d -t 300 >&2
    exit 1
fi

echo "[8/8] Automated checks complete"
echo
echo "AUTOMATED PHASE 11 DEVICE CHECKS COMPLETED"
echo
echo "MANUAL DEVICE ACCEPTANCE — endpoint editing"
echo
echo "Scenario A — default route still works"
echo "  Expected after launch: the route preview renders the default Milan -> Bologna route,"
echo "  with non-zero distance/duration, provider Valhalla and visible maneuvers."
echo
echo "Scenario B — edit route endpoints"
echo "  Tap 'Modifica percorso'. Enter exactly these coordinates:"
echo "    Partenza latitudine: 41.9028"
echo "    Partenza longitudine: 12.4964"
echo "    Destinazione latitudine: 43.7696"
echo "    Destinazione longitudine: 11.2558"
echo "  Tap 'Calcola percorso'."
echo "  Expected: the preview recalculates to Rome -> Florence, not Milan -> Bologna."
echo "  The summary must show a non-zero Valhalla route in the Rome/Florence distance band,"
echo "  and the map line/endpoints must visibly change."
echo
echo "Scenario C — edited route drives manual CNG search and selected stop"
echo "  From the edited Rome -> Florence preview tap 'Aggiungi tappa'."
echo "  Use deviation 10 and effective range 300, then search."
echo "  Expected: station markers/cards are returned for the edited corridor. The screen"
echo "  must not jump back to the Milan -> Bologna route."
echo "  Select one station."
echo "  Expected: the selected-stop route keeps the edited Rome -> Florence endpoints,"
echo "  shows the CNG waypoint, and labels the second maneuver section generically as"
echo "  destination, not 'Bologna'."
echo
echo "Scenario D — edited route drives predictive CNG search"
echo "  Return to preview, keep Rome -> Florence, tap 'Valuta autonomia CNG'."
echo "  Use residual 120, reserve 30, full range 300, deviation 10, then evaluate."
echo "  Expected: Compass returns either a complete refuelling plan or an explicit"
echo "  no-suggestion state for Rome -> Florence. It must not display Milan/Bologna data."
echo "  Destination labels must be generic, for example 'destinazione', not 'Bologna'."
echo
echo "Scenario E — invalid coordinate guard"
echo "  Tap 'Modifica percorso', set destination longitude to 200, then calculate."
echo "  Expected: the app stays on the coordinate form and shows a validation message."
echo "  It must not submit a route request or crash."
echo
echo "Lifecycle check"
echo "  Rotate once after Scenario B, background/resume the app, then reopen the CNG flow."
echo "  Expected: the edited route remains active and no fatal exception occurs."
echo
echo "Return the COMPLETE output of this script and exactly these screenshots:"
echo "  1. Scenario B, edited Rome -> Florence preview with summary and map visible."
echo "  2. Scenario C, manual CNG candidates for the edited route."
echo "  3. Scenario C, selected-stop route for the edited route."
echo "  4. Scenario D, predictive result for the edited route."
echo "  5. Scenario E, invalid-coordinate validation message."
