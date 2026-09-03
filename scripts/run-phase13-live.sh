#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
artifact_prefix=/tmp/compass-phase13
client_dump="${artifact_prefix}-client.txt"
event_dump="${artifact_prefix}-events.txt"
map_cache_dump="${artifact_prefix}-map-cache.txt"
ui_dump="${artifact_prefix}-ui.xml"
service_dump="${artifact_prefix}-service.txt"
notification_dump="${artifact_prefix}-notification.txt"
logcat_dump="${artifact_prefix}-logcat.txt"
launch_output="${artifact_prefix}-launch.txt"
client_log_pid=""
event_log_pid=""
map_log_pid=""

[[ "$api_base_url" == */ ]] || api_base_url="${api_base_url}/"
: "${JAVA_HOME:?Set JAVA_HOME to the JDK 17 installation.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"

adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"
service_class=org.compass.cng.navigation.NavigationForegroundService

[[ -x "$JAVA_HOME/bin/java" ]] || { echo "ERROR: JDK not found in JAVA_HOME." >&2; exit 1; }
[[ -x "$adb_binary" ]] || { echo "ERROR: adb not found in ANDROID_SDK_ROOT." >&2; exit 1; }

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
        exit 1
    }
    adb_args=(-s "${devices[0]}")
fi

set_connected() {
    if [[ "$use_adb_reverse" == true ]]; then
        "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null
    fi
}

set_disconnected() {
    if [[ "$use_adb_reverse" != true ]]; then
        echo "ERROR: this gate needs loopback plus adb reverse to isolate Compass only." >&2
        exit 1
    fi
    "$adb_binary" "${adb_args[@]}" reverse --remove tcp:8000 >/dev/null
}

stop_log_streams() {
    local pid
    for pid in "$client_log_pid" "$event_log_pid" "$map_log_pid"; do
        if [[ -n "$pid" ]]; then
            kill "$pid" >/dev/null 2>&1 || true
            wait "$pid" >/dev/null 2>&1 || true
        fi
    done
    client_log_pid=""
    event_log_pid=""
    map_log_pid=""
}

capture_diagnostics() {
    "$adb_binary" "${adb_args[@]}" shell uiautomator dump /sdcard/compass-phase13-ui.xml \
        >/dev/null 2>&1 || true
    "$adb_binary" "${adb_args[@]}" pull /sdcard/compass-phase13-ui.xml "$ui_dump" \
        >/dev/null 2>&1 || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
        >"$notification_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump" 2>/dev/null || true
}

cleanup() {
    capture_diagnostics
    stop_log_streams
    set_connected >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[1/9] Checking backend readiness and explicit no-traffic state"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"
grep -q '"traffic":"unavailable"' <<<"$readiness" || {
    echo "ERROR: this deterministic degraded-traffic gate expects traffic=unavailable." >&2
    exit 1
}

echo "[2/9] Preparing Android device, clean app data and connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
set_connected

echo "[3/9] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/9] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell pm clear "$application_id" >/dev/null
set_connected
"$adb_binary" "${adb_args[@]}" logcat -c
: >"$client_dump"
: >"$event_dump"
: >"$map_cache_dump"
"$adb_binary" "${adb_args[@]}" logcat -v time -s 'CompassApi:I' '*:S' \
    >"$client_dump" 2>&1 &
client_log_pid=$!
"$adb_binary" "${adb_args[@]}" logcat -v brief -s 'CompassNavigation:I' '*:S' \
    >"$event_dump" 2>&1 &
event_log_pid=$!
"$adb_binary" "${adb_args[@]}" logcat -v brief -s 'CompassMapCache:I' '*:S' \
    >"$map_cache_dump" 2>&1 &
map_log_pid=$!
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — RECENT SEARCH CACHE"
echo
echo "  1. Open 'Modifica percorso' → 'Cerca indirizzo o luogo'."
echo "  2. Search exactly 'Duomo di Milano' while Compass is reachable; keep the results open."
echo "  3. Press ENTER here. The runner will interrupt only the device-to-Compass connection."
read -r -p "When the live results are visible, press ENTER: "
grep -q 'request completed: method=GET endpoint=/api/v1/places/search status=200' "$client_dump" || {
    echo "ERROR: the live search was not recorded." >&2
    exit 1
}
set_disconnected
echo "Compass is now unreachable from the device; Wi-Fi/mobile data remain untouched."
echo "  4. Tap 'Cerca' again without changing the query. Results must remain available and show"
echo "     'Risultati salvati sul dispositivo: ricerca live non disponibile'. Take screenshot A."
read -r -p "When cached search result A is visible, press ENTER: "
grep -q 'place search cache fallback: failure=NETWORK' "$client_dump" || {
    echo "ERROR: no client search-cache fallback was recorded." >&2
    exit 1
}
set_connected

echo
echo "DEVICE ACTION B — DOWNLOADED ROUTE, STALE DATA AND OUTAGE"
echo
echo "  1. Return to the default route. Open 'Valuta autonomia CNG': residual 65 km, reserve"
echo "     30 km, effective full range 100 km, maximum detour 30 minutes. Calculate the plan."
echo "  2. Verify station cards explicitly show unknown/missing opening enrichment and stale price"
echo "     timestamps where returned by the test data. Take screenshot B."
echo "  3. Calculate the route, start navigation and demo replay. Wait for GPS attivo."
read -r -p "When active CNG navigation is progressing, press ENTER: "
set_disconnected
echo "Compass is unreachable from the device."
echo "  4. Tap 'Ricalcola percorso (debug)'. The downloaded route, maneuver, local progress and"
echo "     CNG waypoint must remain. Verify the separate navigation-local/rerouting-unavailable and"
echo "     traffic-unavailable warnings. Take screenshot C."
echo "  5. Press Home and take screenshot D of the foreground navigation notification."
read -r -p "When screenshots C and D are ready, press ENTER: "
grep -q 'navigation degraded: cached_route_active=true rerouting_available=false' "$event_dump" || {
    echo "ERROR: degraded navigation was not recorded." >&2
    exit 1
}
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service did not survive the outage/background step." >&2
    exit 1
}

echo "[5/9] Simulating process death while Compass remains unavailable"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION C — DURABLE SESSION RECOVERY AND AMBIENT MAP CACHE"
echo
echo "  1. Verify a navigation preview is restored without a backend request. It must show the"
echo "     saved route, maneuvers and CNG waypoints plus the cache/stale-data warning. Take screenshot E."
echo "  2. Temporarily disable Wi-Fi/mobile data (or enable airplane mode), then start demo navigation"
echo "     from the recovered route. Verify local progress works and the previously visited map area"
echo "     remains rendered from ambient resources. Take screenshot F with the explicit cached-route,"
echo "     traffic-unavailable and cached-CNG labels. Restore normal device connectivity afterwards;"
echo "     Compass remains isolated until this runner restores adb reverse."
read -r -p "When recovered-cache screenshots E and F are ready, press ENTER: "
grep -q 'navigation route restored from cache: active=true' "$event_dump" || {
    echo "ERROR: durable active-route restoration was not recorded." >&2
    exit 1
}
grep -q 'ambient_cache_ready' "$map_cache_dump" || {
    echo "ERROR: MapLibre ambient cache was not configured." >&2
    exit 1
}

echo "[6/9] Restoring Compass connectivity"
set_connected
echo
echo "DEVICE ACTION D — RECOVERY TO NORMAL OPERATION"
echo
echo "  1. While recovered demo guidance is active, tap 'Ricalcola percorso (debug)'."
echo "  2. Wait for the successful replacement. Navigation must remain active; cached/degraded warnings"
echo "     must disappear and GPS progress must continue. Take screenshot G."
echo "  3. Tap 'Termina navigazione'."
read -r -p "When screenshot G is ready and navigation is terminated, press ENTER: "

echo "[7/9] Verifying recovery and explicit cache teardown"
grep -q 'navigation route cache replaced from live route' "$event_dump" || {
    echo "ERROR: connectivity restoration did not commit a live route." >&2
    exit 1
}
grep -q 'navigation route cache cleared: reason=operator_stop' "$event_dump" || {
    echo "ERROR: operator stop did not clear the active-route cache." >&2
    exit 1
}

echo "[8/9] Verifying service and notification teardown"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: foreground navigation service remains active." >&2
    exit 1
fi
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
    >"$notification_dump"
if grep -A12 -B4 "$application_id" "$notification_dump" | grep -q 'compass_navigation'; then
    echo "ERROR: foreground navigation notification remains visible." >&2
    exit 1
fi

echo "[9/9] Checking fatal exceptions and preserving bounded diagnostics"
capture_diagnostics
if grep -Eq 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' "$logcat_dump"; then
    echo "ERROR: Compass emitted a fatal exception." >&2
    exit 1
fi

echo
echo "PHASE 13 AUTOMATED AND OPERATOR-ASSISTED DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output and screenshots A-G. Confirm cached search, continued downloaded-route"
echo "navigation, explicit degraded/stale states, process-death recovery, ambient map reuse where observed,"
echo "normal recovery after reconnect and notification teardown."
echo
echo "If the gate fails, return:"
echo "  $client_dump"
echo "  $event_dump"
echo "  $map_cache_dump"
echo "  $ui_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $logcat_dump"
