#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
artifact_prefix=/tmp/compass-navigation-ui-phase1
ui_main_dump="${artifact_prefix}-main.xml"
ui_details_dump="${artifact_prefix}-details-top.xml"
ui_actions_dump="${artifact_prefix}-details-actions.xml"
ui_developer_dump="${artifact_prefix}-developer.xml"
service_dump="${artifact_prefix}-service.txt"
notification_dump="${artifact_prefix}-notification.txt"
logcat_dump="${artifact_prefix}-logcat.txt"
ui_event_dump="${artifact_prefix}-ui-events.txt"
launch_output="${artifact_prefix}-launch.txt"

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

capture_ui() {
    local target="$1"
    if "$adb_binary" "${adb_args[@]}" shell uiautomator dump --compressed \
        /sdcard/compass-navigation-ui.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull /sdcard/compass-navigation-ui.xml "$target" \
            >/dev/null 2>&1 || true
    else
        echo "NOTE: UI XML unavailable while MapLibre/replay is animating; using UI events."
        : >"$target"
    fi
}

capture_ui_events() {
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassNavigationUi:I' '*:S' \
        >"$ui_event_dump" 2>/dev/null
}

capture_diagnostics() {
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
        >"$notification_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump" 2>/dev/null || true
    capture_ui_events || true
}
trap capture_diagnostics EXIT

echo "[1/8] Checking backend readiness"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"

echo "[2/8] Preparing Android connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null
fi

echo "[3/8] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/8] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell pm clear "$application_id" >/dev/null
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — AUTOMOTIVE DRIVING SURFACE"
echo
echo "  1. From the default preview open 'Valuta autonomia CNG'. Enter residual 65 km,"
echo "     reserve 30 km, full range 100 km and maximum detour 30 minutes."
echo "  2. Calculate the complete plan and route, then start navigation and demo replay."
echo "  3. Wait for progressing guidance. Verify that the map fills the navigation screen and that"
echo "     the compact maneuver card shows icon, distance, instruction, road and following maneuver."
echo "  4. Verify the bottom overlay shows remaining distance, duration, ETA and next CNG stop."
echo "     Take screenshot A. The main surface must not show debug controls or raw diagnostics."
read -r -p "When screenshot A is ready, press ENTER: "
capture_ui "$ui_main_dump"
capture_ui_events
grep -q 'surface=driving visible=true' "$ui_event_dump" || {
    echo "ERROR: the automotive driving surface was not recorded." >&2
    exit 1
}

echo
echo "DEVICE ACTION B — EXPANDABLE TRIP DETAILS"
echo
echo "  1. Tap 'Dettagli viaggio'. Verify the sheet contains trip status, traffic/connectivity state,"
echo "     and planned CNG stops. Take screenshot B and keep the top of the sheet visible."
read -r -p "When screenshot B is ready, press ENTER: "
capture_ui "$ui_details_dump"
capture_ui_events
grep -q 'surface=trip_details visible=true' "$ui_event_dump" || {
    echo "ERROR: trip details sheet was not found." >&2
    exit 1
}
echo "  2. Scroll through the sheet. Verify all planned CNG stops are readable and that"
echo "     'Ricalcola percorso', CNG replacement and 'Termina navigazione' are available."
echo "     Take screenshot C with the lower actions visible."
read -r -p "When screenshot C is ready with the lower actions visible, press ENTER: "
capture_ui "$ui_actions_dump"
capture_ui_events

echo
echo "DEVICE ACTION C — DEDICATED DEVELOPER SCREEN"
echo
echo "  1. Tap 'Strumenti sviluppatore'. Verify the dedicated screen is clearly marked"
echo "     'Non usare durante la guida' and contains diagnostics plus the two debug actions."
echo "  2. Take screenshot D and keep the developer screen open."
read -r -p "When screenshot D is ready and the developer screen is still visible, press ENTER: "
capture_ui "$ui_developer_dump"
capture_ui_events
grep -q 'surface=developer_tools visible=true' "$ui_event_dump" || {
    echo "ERROR: dedicated developer controls were not found." >&2
    exit 1
}
read -r -p "Now tap 'Chiudi'; when the driving surface is visible again, press ENTER: "

echo "[5/8] Verifying foreground navigation continuity"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service is not active after UI interaction." >&2
    exit 1
}

echo
echo "DEVICE ACTION D — CLEAN RETURN AND TERMINATION"
echo
echo "  1. Verify the driving surface returns unchanged after closing developer tools."
echo "  2. Open 'Dettagli viaggio' and tap 'Termina navigazione'."
read -r -p "When navigation is terminated, press ENTER: "

echo "[6/8] Verifying foreground service and notification teardown"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: foreground navigation service remains active." >&2
    exit 1
fi
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact >"$notification_dump"
if grep -A12 -B4 "$application_id" "$notification_dump" | grep -q 'compass_navigation'; then
    echo "ERROR: foreground navigation notification remains visible." >&2
    exit 1
fi

echo "[7/8] Checking the Compass process for fatal exceptions"
capture_diagnostics
if grep -Eq 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' "$logcat_dump"; then
    echo "ERROR: Compass emitted a fatal exception." >&2
    exit 1
fi

echo "[8/8] Navigation UI Phase 1 automated and operator-assisted checks complete"
echo
echo "NAVIGATION UI PHASE 1 DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output and screenshots A-D. Confirm that the fullscreen map, glanceable"
echo "maneuver/trip/CNG overlays, expandable details and dedicated developer screen behaved as"
echo "described. Do not proceed to Navigation UI Phase 2 until this gate is accepted."
echo
echo "If the gate fails, return:"
echo "  $ui_main_dump"
echo "  $ui_details_dump"
echo "  $ui_actions_dump"
echo "  $ui_developer_dump"
echo "  $ui_event_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $logcat_dump"
