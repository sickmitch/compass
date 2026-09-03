#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
openapi_dump=/tmp/compass-navigation-stage5-openapi.json
client_dump=/tmp/compass-navigation-stage5-client.txt
ui_dump=/tmp/compass-navigation-stage5-ui.xml
service_dump=/tmp/compass-navigation-stage5-service.txt
notification_dump=/tmp/compass-navigation-stage5-notification.txt
logcat_dump=/tmp/compass-navigation-stage5-logcat.txt
curl_args=(--fail --silent --show-error --connect-timeout 5 --max-time 30)

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

capture_diagnostics() {
    "$adb_binary" "${adb_args[@]}" logcat -d -v time \
        -s 'CompassApi:I' '*:S' >"$client_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
        >"$notification_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump" 2>/dev/null || true
}

cleanup() {
    capture_diagnostics
    if [[ "$use_adb_reverse" == true ]]; then
        "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

echo "[1/7] Checking backend readiness and Stage 5 API contract"
curl "${curl_args[@]}" "${api_base_url}health/ready"
echo
curl "${curl_args[@]}" "${api_base_url}openapi.json" --output "$openapi_dump"
grep -q 'estimated_remaining_gasoline_range_km' "$openapi_dump" || {
    echo "ERROR: the deployed API does not expose the Stage 5 gasoline request fields." >&2
    echo "Synchronize, rebuild and restart the Compass API, then rerun this script." >&2
    exit 1
}
grep -q 'gasoline_fallback' "$openapi_dump" || {
    echo "ERROR: the deployed API does not expose the Stage 5 fallback response." >&2
    echo "Synchronize, rebuild and restart the Compass API, then rerun this script." >&2
    exit 1
}

echo "[2/7] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
fi

echo "[3/7] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/7] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component"

echo
echo "DEVICE ACTION — VEHICLE PROFILE AND GASOLINE FALLBACK"
echo
echo "On the Android device:"
echo "  1. From the route preview open 'Configura profili mezzi'. Create and select"
echo "     'Test dual fuel' with CNG full 40 km, CNG reserve 30 km, gasoline full"
echo "     300 km and gasoline reserve 30 km. Take screenshot A of the selected profile."
echo "  2. Force-close Compass from recents and reopen it. Confirm 'Test dual fuel' remains"
echo "     selected and its CNG values are prefilled in 'Valuta autonomia CNG'."
echo "  3. Enter CNG remaining 35 km, gasoline remaining 300 km and maximum detour 10"
echo "     minutes. Tap 'Valuta e suggerisci una stazione'."
echo "  4. Confirm 'Fallback benzina disponibile', required gasoline and reserve margin."
echo "     Take screenshot B. No CNG itinerary may be presented for these test limits."
echo "  5. Tap 'Continua con fallback benzina', then 'Riproduci percorso demo'. Confirm"
echo "     active navigation still shows 'Fallback benzina attivo'. Take screenshot C."
echo
read -r -p "When active fallback navigation and screenshot C are ready, press ENTER here: "

echo "[5/7] Verifying fallback evidence and foreground service"
"$adb_binary" "${adb_args[@]}" shell uiautomator dump /sdcard/compass-stage5-ui.xml \
    >/dev/null
"$adb_binary" "${adb_args[@]}" exec-out cat /sdcard/compass-stage5-ui.xml >"$ui_dump"
grep -q 'Fallback benzina attivo' "$ui_dump" || {
    echo "ERROR: active UI does not expose the gasoline fallback label." >&2
    exit 1
}
capture_diagnostics
grep -q 'request completed: method=POST endpoint=/api/v1/cng/predictive-candidates status=200' \
    "$client_dump" || {
    echo "ERROR: no successful predictive API client event was recorded." >&2
    exit 1
}
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: NavigationForegroundService is not active." >&2
    exit 1
}

echo
read -r -p "Tap 'Termina navigazione'; when the notification is gone, press ENTER here: "

echo "[6/7] Verifying foreground service and notification teardown"
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

echo "[7/7] Checking the Compass process for fatal exceptions"
if grep -E 'FATAL EXCEPTION.*org.compass.cng|Process: org.compass.cng.debug' "$logcat_dump" >/dev/null; then
    echo "ERROR: fatal Compass exception detected." >&2
    exit 1
fi

echo
echo "AUTOMATED NAVIGATION STAGE 5 DEVICE CHECKS COMPLETED"
echo
echo "Return the COMPLETE output and screenshots A, B and C. Confirm that the profile"
echo "survived cold launch, no CNG itinerary was offered, the fallback stayed visible"
echo "during navigation, and the foreground notification disappeared after termination."
echo
echo "If the gate fails, return:"
echo "  $openapi_dump"
echo "  $client_dump"
echo "  $ui_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $logcat_dump"
