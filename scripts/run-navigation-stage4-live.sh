#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
event_dump=/tmp/compass-navigation-stage4-events.txt
logcat_dump=/tmp/compass-navigation-stage4-logcat.txt
service_dump=/tmp/compass-navigation-stage4-service.txt
notification_dump=/tmp/compass-navigation-stage4-notification.txt
client_dump=/tmp/compass-navigation-stage4-client.txt
launch_output=/tmp/compass-navigation-stage4-launch.txt
openapi_dump=/tmp/compass-navigation-stage4-openapi.json

: >"$event_dump"
: >"$logcat_dump"
: >"$service_dump"
: >"$notification_dump"
: >"$client_dump"

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
        echo "Set COMPASS_ADB_SERIAL when multiple devices are connected." >&2
        exit 1
    }
    adb_args=(-s "${devices[0]}")
fi

restore_reverse() {
    if [[ "$use_adb_reverse" == true ]]; then
        "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null 2>&1 || true
    fi
}

capture_client_logs() {
    "$adb_binary" "${adb_args[@]}" logcat -d -v time \
        -s 'CompassApi:I' '*:S' >"$client_dump" 2>/dev/null || true
}

cleanup() {
    capture_client_logs
    restore_reverse
}
trap cleanup EXIT

capture_events() {
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief \
        -s 'CompassNavigation:I' '*:S' >"$event_dump"
}

wait_for_event() {
    local pattern="$1"
    local attempt=0
    while [[ "$attempt" -lt 60 ]]; do
        capture_events
        if grep -q "$pattern" "$event_dump"; then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    return 1
}

echo "[1/9] Checking backend readiness and Stage 4 API contract"
curl --fail --silent --show-error "${api_base_url}health/ready"
echo
curl --fail --silent --show-error "${api_base_url}openapi.json" --output "$openapi_dump"
grep -q 'excluded_mimit_station_ids' "$openapi_dump" || {
        echo "ERROR: deployed OpenAPI does not expose Stage 4 station exclusion." >&2
        exit 1
    }

echo "[2/9] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
fi

echo "[3/9] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/9] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — SAFE PREDICTIVE STOP REPLACEMENT"
echo
echo "On the Android device:"
echo "  1. From the Milan-to-Bologna preview tap 'Valuta autonomia CNG'."
echo "  2. Enter residual 65 km, reserve 30 km, full effective range 100 km and"
echo "     maximum detour 30 minutes; tap 'Valuta e suggerisci una stazione'."
echo "  3. The live snapshot must return a complete plan. Tap 'Calcola percorso con ... soste',"
echo "     then 'Avvia navigazione' and 'Riproduci percorso demo'."
echo "  4. Note the first 'Prossimo rifornimento', then tap"
echo "     'Salta / sostituisci tappa CNG'. Take screenshot A of the confirmation."
echo "  5. Tap 'Cerca alternativa'. Wait for navigation to resume on the replacement route."
echo "     The first stop must be different. Take screenshot B with new stop/progress visible."
echo
read -r -p "When the replacement route is active, press ENTER here: "

echo "[5/9] Verifying exclusion and committed in-session replacement"
wait_for_event 'fuel stop replacement committed: excluded=' || {
    capture_events
    echo "ERROR: no successful Stage 4 replacement was recorded." >&2
    sed -n '1,200p' "$event_dump" >&2
    exit 1
}
grep -q 'fuel stop replacement started: station=' "$event_dump" || {
    echo "ERROR: replacement commit has no matching start event." >&2
    exit 1
}

echo "[6/9] Checking the same foreground navigation service remains active"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: NavigationForegroundService is not active after replacement." >&2
    exit 1
}

echo
echo "DEVICE ACTION B — MANUAL ROUTE RANGE-SAFETY GUARD"
echo
echo "On the Android device:"
echo "  1. Tap 'Termina navigazione'. Force-close Compass from recents, then reopen it."
echo "  2. From the default preview tap 'Aggiungi tappa', keep effective range 300 km"
echo "     and deviation 10 minutes, search, and select a station."
echo "  3. Start navigation and the demo replay. Tap 'Salta / sostituisci tappa CNG',"
echo "     then 'Cerca alternativa'."
echo "  4. Verify 'Per sostituire questa tappa serve un piano autonomia predittivo.'"
echo "     and that route/stop/progress remain present. Take screenshot C."
echo "  5. Tap 'Termina navigazione'."
echo
read -r -p "When navigation is terminated, press ENTER here: "

echo "[7/9] Verifying the range-plan guard was recorded"
wait_for_event 'fuel stop replacement unavailable: range plan required' || {
    capture_events
    echo "ERROR: the manual-route safety guard was not recorded." >&2
    sed -n '1,220p' "$event_dump" >&2
    exit 1
}

echo "[8/9] Verifying service and notification teardown"
sleep 2
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: NavigationForegroundService still runs after termination." >&2
    exit 1
fi
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
    >"$notification_dump"
if grep -q 'Navigation Compass attiva' "$notification_dump"; then
    echo "ERROR: Compass navigation notification still exists after termination." >&2
    exit 1
fi

echo "[9/9] Checking the Compass process for fatal exceptions"
capture_client_logs
compass_pid="$("$adb_binary" "${adb_args[@]}" shell pidof -s "$application_id" | tr -d '\r')"
if [[ -n "$compass_pid" ]]; then
    "$adb_binary" "${adb_args[@]}" logcat -d --pid="$compass_pid" >"$logcat_dump"
    if grep -E 'FATAL EXCEPTION|SecurityException' "$logcat_dump" >/dev/null; then
        echo "ERROR: fatal Compass exception detected." >&2
        exit 1
    fi
fi

echo
echo "AUTOMATED NAVIGATION STAGE 4 DEVICE CHECKS COMPLETED"
echo
echo "Return the COMPLETE script output and screenshots A, B and C. Confirm that the"
echo "original station did not return, navigation continued in the same session, and the"
echo "foreground notification disappeared after 'Termina navigazione'."
echo
echo "If the gate fails, return these bounded diagnostics:"
echo "  $event_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $client_dump"
echo "  $logcat_dump"
echo "  $adb_binary ${adb_args[*]} logcat -d -v time -s 'CompassApi:I' '*:S'"
echo "  $adb_binary ${adb_args[*]} logcat -d -v brief -s 'CompassNavigation:I' '*:S'"
