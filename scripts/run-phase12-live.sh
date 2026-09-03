#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
validator="$repo_root/scripts/validate-phase12-live.py"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
artifact_prefix=/tmp/compass-phase12
openapi_dump="${artifact_prefix}-openapi.json"
address_dump="${artifact_prefix}-search-address.json"
locality_dump="${artifact_prefix}-search-locality.json"
poi_dump="${artifact_prefix}-search-poi.json"
coordinate_dump="${artifact_prefix}-search-coordinate.json"
base_request="${artifact_prefix}-base-request.json"
base_response="${artifact_prefix}-base-response.json"
one_request="${artifact_prefix}-one-stop-request.json"
one_response="${artifact_prefix}-one-stop-response.json"
one_route_request="${artifact_prefix}-one-stop-route-request.json"
one_route_response="${artifact_prefix}-one-stop-route-response.json"
multi_request="${artifact_prefix}-multi-stop-request.json"
multi_response="${artifact_prefix}-multi-stop-response.json"
multi_route_request="${artifact_prefix}-multi-stop-route-request.json"
multi_route_response="${artifact_prefix}-multi-stop-route-response.json"
client_dump="${artifact_prefix}-client.txt"
event_dump="${artifact_prefix}-events.txt"
ui_dump="${artifact_prefix}-ui.xml"
service_dump="${artifact_prefix}-service.txt"
notification_dump="${artifact_prefix}-notification.txt"
logcat_dump="${artifact_prefix}-logcat.txt"
launch_output="${artifact_prefix}-launch.txt"
client_log_pid=""
event_log_pid=""

[[ "$api_base_url" == */ ]] || api_base_url="${api_base_url}/"
: "${JAVA_HOME:?Set JAVA_HOME to the JDK 17 installation.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"

adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"
service_class=org.compass.cng.navigation.NavigationForegroundService
curl_args=(--fail-with-body --silent --show-error --connect-timeout 5)

[[ -x "$JAVA_HOME/bin/java" ]] || { echo "ERROR: JDK not found in JAVA_HOME." >&2; exit 1; }
[[ -x "$adb_binary" ]] || { echo "ERROR: adb not found in ANDROID_SDK_ROOT." >&2; exit 1; }
[[ -f "$validator" ]] || { echo "ERROR: Phase 12 validator is missing." >&2; exit 1; }

case "$api_base_url" in
    http://127.0.0.1:8000/)
        use_adb_reverse=true
        echo "Using the loopback API at $api_base_url"
        echo "If the backend is remote, keep this SSH tunnel open in a separate terminal:"
        echo "  ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER"
        echo
        ;;
    https://*) use_adb_reverse=false ;;
    *) echo "ERROR: use loopback HTTP with adb reverse, or HTTPS." >&2; exit 1 ;;
esac

adb_args=()
if [[ -n "${COMPASS_ADB_SERIAL:-}" ]]; then
    adb_args=(-s "$COMPASS_ADB_SERIAL")
else
    mapfile -t devices < <("$adb_binary" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [[ "${#devices[@]}" -eq 1 ]] || {
        echo "ERROR: expected one authorized Android device; found ${#devices[@]}." >&2
        echo "Set COMPASS_ADB_SERIAL when multiple devices are connected." >&2
        exit 1
    }
    adb_args=(-s "${devices[0]}")
fi

capture_diagnostics() {
    stop_log_streams
    if [[ ! -s "$client_dump" ]]; then
        "$adb_binary" "${adb_args[@]}" logcat -d -v time \
            -s 'CompassApi:I' '*:S' >"$client_dump" 2>/dev/null || true
    fi
    if [[ ! -s "$event_dump" ]]; then
        "$adb_binary" "${adb_args[@]}" logcat -d -v brief \
            -s 'CompassNavigation:I' '*:S' >"$event_dump" 2>/dev/null || true
    fi
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
        >"$notification_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump" 2>/dev/null || true
}

stop_log_streams() {
    local pid
    for pid in "$client_log_pid" "$event_log_pid"; do
        if [[ -n "$pid" ]]; then
            kill "$pid" >/dev/null 2>&1 || true
            wait "$pid" >/dev/null 2>&1 || true
        fi
    done
    client_log_pid=""
    event_log_pid=""
}

cleanup() {
    capture_diagnostics
    if [[ "$use_adb_reverse" == true ]]; then
        "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

wait_for_event() {
    local pattern="$1"
    local attempt=0
    while [[ "$attempt" -lt 90 ]]; do
        if grep -q "$pattern" "$event_dump"; then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    return 1
}

echo "[1/12] Checking backend readiness and Phase 12 OpenAPI"
curl "${curl_args[@]}" --max-time 30 "${api_base_url}health/ready"
echo
curl "${curl_args[@]}" --max-time 30 \
    "${api_base_url}openapi.json" --output "$openapi_dump"
python3 "$validator" validate-openapi --openapi "$openapi_dump"

echo "[2/12] Checking normalized address, locality, POI and coordinate search"
curl "${curl_args[@]}" --max-time 30 --get \
    --data-urlencode 'q=Via Dante 1, Milano' \
    --data-urlencode 'language=it' \
    "${api_base_url}api/v1/places/search" --output "$address_dump"
sleep 1
curl "${curl_args[@]}" --max-time 30 --get \
    --data-urlencode 'q=Bologna' \
    --data-urlencode 'language=it' \
    "${api_base_url}api/v1/places/search" --output "$locality_dump"
sleep 1
curl "${curl_args[@]}" --max-time 30 --get \
    --data-urlencode 'q=Duomo di Milano' \
    --data-urlencode 'language=it' \
    "${api_base_url}api/v1/places/search" --output "$poi_dump"
sleep 1
curl "${curl_args[@]}" --max-time 30 --get \
    --data-urlencode 'q=45.4642, 9.19' \
    --data-urlencode 'language=it' \
    "${api_base_url}api/v1/places/search" --output "$coordinate_dump"
python3 "$validator" validate-search \
    --address "$address_dump" \
    --locality "$locality_dump" \
    --poi "$poi_dump" \
    --coordinate "$coordinate_dump"

echo "[3/12] Checking the final Compass A-to-B route and Valhalla maneuvers"
python3 "$validator" prepare-base-route --output "$base_request"
curl "${curl_args[@]}" --max-time 240 \
    --header 'Content-Type: application/json' \
    --data-binary "@$base_request" \
    "${api_base_url}api/v1/routes" --output "$base_response"
python3 "$validator" validate-base-route --route "$base_response"

echo "[4/12] Checking one-stop and multi-stop predictive chronology"
python3 "$validator" prepare-predictive --profile one --output "$one_request"
curl "${curl_args[@]}" --max-time 360 \
    --header 'Content-Type: application/json' \
    --data-binary "@$one_request" \
    "${api_base_url}api/v1/cng/predictive-candidates" --output "$one_response"
python3 "$validator" prepare-itinerary-route \
    --profile one --predictive "$one_response" --output "$one_route_request"
curl "${curl_args[@]}" --max-time 240 \
    --header 'Content-Type: application/json' \
    --data-binary "@$one_route_request" \
    "${api_base_url}api/v1/routes/with-cng-itinerary" --output "$one_route_response"
python3 "$validator" prepare-predictive --profile multi --output "$multi_request"
curl "${curl_args[@]}" --max-time 360 \
    --header 'Content-Type: application/json' \
    --data-binary "@$multi_request" \
    "${api_base_url}api/v1/cng/predictive-candidates" --output "$multi_response"
python3 "$validator" prepare-itinerary-route \
    --profile multi --predictive "$multi_response" --output "$multi_route_request"
curl "${curl_args[@]}" --max-time 240 \
    --header 'Content-Type: application/json' \
    --data-binary "@$multi_route_request" \
    "${api_base_url}api/v1/routes/with-cng-itinerary" --output "$multi_route_response"
python3 "$validator" validate-timing \
    --one-predictive "$one_response" \
    --one-route "$one_route_response" \
    --multi-predictive "$multi_response" \
    --multi-route "$multi_route_response"

echo "[5/12] Preparing Android device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
fi

echo "[6/12] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[7/12] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
if [[ "$("$adb_binary" "${adb_args[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]]; then
    "$adb_binary" "${adb_args[@]}" shell pm clear-permission-flags \
        "$application_id" android.permission.POST_NOTIFICATIONS user-fixed user-set \
        >/dev/null 2>&1 || true
fi
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
: >"$client_dump"
: >"$event_dump"
"$adb_binary" "${adb_args[@]}" logcat -v time \
    -s 'CompassApi:I' '*:S' >"$client_dump" 2>&1 &
client_log_pid=$!
"$adb_binary" "${adb_args[@]}" logcat -v brief \
    -s 'CompassNavigation:I' '*:S' >"$event_dump" 2>&1 &
event_log_pid=$!
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — ORIGIN, DESTINATION SEARCH AND GUIDANCE"
echo
echo "On the Android device:"
echo "  1. In the default preview tap 'Modifica percorso', then 'Usa la posizione attuale'."
echo "     Grant precise location if requested. Wait for 'Posizione acquisita', verify that"
echo "     the visible origin coordinates changed, then tap 'Calcola percorso'."
echo "  2. Reopen 'Modifica percorso' and 'Cerca indirizzo o luogo'. Search for"
echo "     'Via Dante 1, Milano'. Take screenshot A of normalized address results, then select one."
echo "  3. Search again for 'Basilica di San Petronio, Bologna'. Take screenshot B showing a 'Luogo' result,"
echo "     select it and take screenshot C of the new A-to-B preview and maneuver list."
echo "     The preview must contain a visible route and report distance and duration above zero."
echo "  4. Tap 'Avvia navigazione' and grant the notification permission if Android asks."
echo "     Tap 'Riproduci percorso demo'. Verify current and next"
echo "     maneuver, distance to maneuver, remaining distance/time and ETA all progress."
echo "     Take screenshot D while guidance is active, then tap 'Termina navigazione'."
echo
read -r -p "When screenshots A-D and direct-route guidance are complete, press ENTER here: "

echo "[8/12] Verifying Android search/route calls"
grep -q 'request completed: method=GET endpoint=/api/v1/places/search status=200' \
    "$client_dump" || {
        echo "ERROR: no successful Android place-search request was recorded." >&2
        exit 1
    }
grep -q 'request completed: method=POST endpoint=/api/v1/routes status=200' \
    "$client_dump" || {
        echo "ERROR: no successful Android route request was recorded." >&2
        exit 1
    }
grep -Eq 'route decoded: distance_meters=[1-9][0-9]* duration_seconds=[1-9][0-9]* maneuvers=[1-9][0-9]*' \
    "$client_dump" || {
        echo "ERROR: Android did not decode a navigable positive-cost route." >&2
        exit 1
    }

echo
echo "DEVICE ACTION B — CNG DWELL, PRESERVED STOP AND INVALID-STOP REPLAN"
echo
echo "On the Android device:"
echo "  1. Force-close Compass from recents and reopen it to restore the default route."
echo "  2. Tap 'Valuta autonomia CNG'. Enter residual 65 km, reserve 30 km, full range"
echo "     100 km and maximum detour 30 minutes. Calculate the complete itinerary and route."
echo "  3. Before starting, verify the navigation preview separately reports driving time,"
echo "     20 minutes per CNG stop, cumulative refuelling time, total duration and traffic"
echo "     delay availability. Take screenshot E."
echo "  4. Start demo guidance. Note the first 'Prossimo rifornimento', then immediately tap"
echo "     'Simula deviazione (debug)' before reaching that stop. Wait for automatic rerouting. The same first stop"
echo "     must remain selected, GPS must return active and demo progress must resume without"
echo "     another tap on 'Riproduci percorso demo'. Take screenshot F."
echo "  5. Tap 'Salta / sostituisci tappa CNG', confirm 'Cerca alternativa' and wait."
echo "     This simulates the selected stop becoming invalid. A different complete safe plan"
echo "     must replace it without ending navigation. Take screenshot G."
echo
read -r -p "When the replacement plan is active and screenshot G is ready, press ENTER here: "

echo "[9/12] Verifying off-route preservation and fuel-plan replacement"
wait_for_event 'route update committed: OFF_ROUTE' || {
    echo "ERROR: no committed automatic off-route reroute was recorded." >&2
    exit 1
}
wait_for_event 'demo replay resumed after route update' || {
    echo "ERROR: demo GPS replay did not resume after automatic rerouting." >&2
    exit 1
}
started_line="$(grep 'route update started: OFF_ROUTE' "$event_dump" | tail -1)"
committed_line="$(grep 'route update committed: OFF_ROUTE' "$event_dump" | tail -1)"
started_stops="${started_line##*stops=}"
committed_stops="${committed_line##*stops=}"
[[ "$started_stops" != direct && "$started_stops" == "$committed_stops" ]] || {
    echo "ERROR: the valid CNG stop sequence was not preserved during rerouting." >&2
    echo "Before: $started_stops" >&2
    echo "After:  $committed_stops" >&2
    exit 1
}
wait_for_event 'fuel stop replacement committed: excluded=' || {
    echo "ERROR: invalid CNG stop did not produce a committed safe replacement." >&2
    exit 1
}

echo "[10/12] Checking foreground lifecycle continuity"
if [[ "$("$adb_binary" "${adb_args[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]]; then
    "$adb_binary" "${adb_args[@]}" shell cmd appops get "$application_id" POST_NOTIFICATION \
        >"${artifact_prefix}-notification-permission.txt" 2>/dev/null || true
    grep -q 'allow' "${artifact_prefix}-notification-permission.txt" || {
        echo "ERROR: Android notification permission is not granted to Compass." >&2
        exit 1
    }
fi
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: NavigationForegroundService is not active after rerouting." >&2
    exit 1
}
echo
echo "Press Home without terminating navigation. Take screenshot H of the Compass"
echo "foreground notification, then press ENTER here."
read -r
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
    >"$notification_dump"
grep -q 'Navigation Compass attiva' "$notification_dump" || {
    echo "ERROR: foreground navigation notification is missing." >&2
    exit 1
}
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component"
echo "Verify the same navigation session, replacement stop and progress are still visible."
echo "Then tap 'Termina navigazione' and press ENTER here."
read -r

echo "[11/12] Verifying navigation teardown and process health"
sleep 2
capture_diagnostics
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: NavigationForegroundService still runs after termination." >&2
    exit 1
fi
if grep -q 'Navigation Compass attiva' "$notification_dump"; then
    echo "ERROR: Compass navigation notification still exists after termination." >&2
    exit 1
fi
if grep -E 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' \
    "$logcat_dump" >/dev/null; then
    echo "ERROR: fatal Compass exception detected." >&2
    exit 1
fi

echo "[12/12] Phase 12 automated and operator-assisted checks complete"
"$adb_binary" "${adb_args[@]}" shell uiautomator dump /sdcard/compass-phase12-ui.xml \
    >/dev/null 2>&1 || true
"$adb_binary" "${adb_args[@]}" exec-out cat /sdcard/compass-phase12-ui.xml \
    >"$ui_dump" 2>/dev/null || true

echo
echo "AUTOMATED PHASE 12 DEVICE CHECKS COMPLETED"
echo
echo "Return the COMPLETE script output and screenshots A-H. Confirm that destination"
echo "address/POI search, current-location origin, maneuver progress, stop-preserving"
echo "rerouting, safe invalid-stop replanning, lifecycle recovery and final notification"
echo "teardown behaved as described. Do not proceed to Phase 13 yet."
echo
echo "If the gate fails, return these bounded diagnostics:"
echo "  ${artifact_prefix}-*.json"
echo "  $client_dump"
echo "  $event_dump"
echo "  $ui_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  ${artifact_prefix}-notification-permission.txt"
echo "  $logcat_dump"
echo "  $adb_binary ${adb_args[*]} logcat -d -v time -s 'CompassApi:I' '*:S'"
echo "  $adb_binary ${adb_args[*]} logcat -d -v brief -s 'CompassNavigation:I' '*:S'"
