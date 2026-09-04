#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
map_style_url="${COMPASS_MAP_STYLE_URL:-https://tiles.openfreemap.org/styles/liberty}"
artifact_prefix=/tmp/compass-navigation-ui-phase2
follow_dump="${artifact_prefix}-follow.xml"
sparse_follow_dump="${artifact_prefix}-sparse-follow.xml"
summary_dump="${artifact_prefix}-summary.xml"
free_dump="${artifact_prefix}-manual.xml"
recenter_dump="${artifact_prefix}-recenter.xml"
overview_dump="${artifact_prefix}-overview.xml"
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
[[ "$map_style_url" == https://* ]] || {
    echo "ERROR: COMPASS_MAP_STYLE_URL must use HTTPS for this device gate." >&2
    exit 1
}

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
        /sdcard/compass-navigation-camera.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull /sdcard/compass-navigation-camera.xml "$target" \
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
echo "Road-capable MapLibre style: $map_style_url"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base_url" \
        -PCOMPASS_MAP_STYLE_URL="$map_style_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/8] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell pm clear "$application_id" >/dev/null
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — HEADING-UP FORWARD-LOOKING FOLLOW"
echo
echo "  1. From the default preview open 'Valuta autonomia CNG'. Enter residual 65 km,"
echo "     reserve 30 km, full range 100 km and maximum detour 30 minutes."
echo "  2. Calculate the complete plan and route, then start navigation and demo replay."
echo "  3. Observe at least three successive location updates. Confirm the camera moves smoothly,"
echo "     remains aligned with travel heading, uses a pitched view and does not jump between fixes."
echo "  4. During the first dense urban maneuvers, confirm the arrow-shaped vehicle is exactly vertical,"
echo "     low in the viewport and that the camera moves closer to make nearby turns readable."
echo "     Ordinary streets and Italian place/road labels must render beneath the route."
echo "  5. Confirm the trip panel is initially hidden and the 'Viaggio' toggle is visible."
echo "  6. Confirm transit stops, libraries, shops and generic POIs are absent. Only driving-relevant"
echo "     POIs may remain: fuel/charging, toll booths, border control and traffic lights when supplied."
echo "     Take screenshot A while dense-maneuver follow guidance is progressing."
read -r -p "When dense follow-mode screenshot A is ready, press ENTER: "
capture_ui "$follow_dump"
capture_ui_events
grep -q 'surface=driving visible=true' "$ui_event_dump" || {
    echo "ERROR: the driving surface was not recorded." >&2
    exit 1
}
grep -Fq "map_style_loaded url=$map_style_url locale=it" "$ui_event_dump" || {
    echo "ERROR: the road-capable map style was not loaded with Italian labels." >&2
    exit 1
}
grep -Eq 'map_style_loaded .* locale=it layers=[1-9][0-9]*' "$ui_event_dump" || {
    echo "ERROR: no MapLibre label layer accepted the Italian localization policy." >&2
    exit 1
}
grep -Eq 'map_style_loaded .* poi_layers=[1-9][0-9]*' "$ui_event_dump" || {
    echo "ERROR: no basemap POI layer accepted the navigation filter." >&2
    exit 1
}
grep -q 'map_poi_policy mode=navigation' "$ui_event_dump" || {
    echo "ERROR: the navigation POI whitelist was not applied." >&2
    exit 1
}
grep -q 'map_symbols vehicle=arrow cng=badge' "$ui_event_dump" || {
    echo "ERROR: directional vehicle and CNG marker layers were not installed." >&2
    exit 1
}
grep -q 'vehicle_alignment=viewport' "$ui_event_dump" || {
    echo "ERROR: the follow-mode vehicle was not locked vertically to the viewport." >&2
    exit 1
}
grep -q 'camera_instruction mode=follow' "$ui_event_dump" || {
    echo "ERROR: no forward follow-camera instruction was recorded." >&2
    exit 1
}

echo
echo "DEVICE ACTION B — MANEUVER-DENSITY ZOOM"
echo
echo "  1. Continue demo replay until maneuvers become visibly farther apart (normally an instruction"
echo "     above 1 km). Confirm the camera eases outward instead of retaining the dense-street zoom."
echo "  2. Verify the arrow remains vertical during the zoom transition. Take screenshot B."
read -r -p "When sparse-maneuver screenshot B is ready, press ENTER: "
capture_ui "$sparse_follow_dump"
capture_ui_events
grep -Eq 'camera_instruction mode=follow .*next_maneuver_spacing=[0-9]' "$ui_event_dump" || {
    echo "ERROR: no maneuver-spacing input was recorded for dynamic zoom." >&2
    exit 1
}

echo
echo "DEVICE ACTION C — OPTIONAL TRIP SUMMARY"
echo
echo "  1. Tap 'Viaggio'. Verify the compact panel shows remaining distance/time, ETA and"
echo "     the next stop with a CNG-specific badge rather than a petrol-pump symbol."
echo "  2. Take screenshot C while the panel is visible."
read -r -p "When trip-summary screenshot C is ready, press ENTER: "
capture_ui "$summary_dump"
capture_ui_events
grep -q 'trip_summary visible=true' "$ui_event_dump" || {
    echo "ERROR: the optional trip summary was not opened." >&2
    exit 1
}
echo "  3. Tap 'Nascondi' and verify the map regains the bottom area."
read -r -p "When the trip summary is hidden again, press ENTER: "
capture_ui_events
grep -q 'trip_summary visible=false' "$ui_event_dump" || {
    echo "ERROR: the optional trip summary was not hidden." >&2
    exit 1
}

echo
echo "DEVICE ACTION D — MANUAL CAMERA INTERACTION"
echo
echo "  1. Pan the map, then rotate or pinch it. The automatic camera must stop taking control."
echo "  2. Wait for two replay updates and verify your chosen viewport is retained while trip progress"
echo "     continues. 'Ricentra' must be visible with readable contrasting text. Take screenshot D."
read -r -p "When manual-camera screenshot D is ready, press ENTER: "
capture_ui "$free_dump"
capture_ui_events
grep -q 'camera_mode=free reason=gesture' "$ui_event_dump" || {
    echo "ERROR: a manual MapLibre gesture did not leave follow mode." >&2
    exit 1
}

echo
echo "DEVICE ACTION E — IDLE AUTO-RECENTER AND EXPLICIT RECENTER"
echo
echo "  1. Do not touch the map. The runner now waits 12 seconds."
sleep 12
capture_ui "$recenter_dump"
capture_ui_events
grep -q 'camera_mode=follow reason=idle_timeout' "$ui_event_dump" || {
    echo "ERROR: free camera did not return to follow after the idle timeout." >&2
    exit 1
}
echo "  2. Verify heading-up follow returned, the vehicle is low in the viewport and 'Ricentra'"
echo "     disappeared. Take screenshot E."
read -r -p "When automatic-recenter screenshot E is ready, press ENTER: "
echo "  3. Pan once more, then tap the now-readable 'Ricentra'. Confirm a smooth return to follow."
read -r -p "When explicit recenter has completed, press ENTER: "
capture_ui_events
grep -q 'camera_mode=follow reason=recenter' "$ui_event_dump" || {
    echo "ERROR: explicit recenter was not recorded." >&2
    exit 1
}

echo
echo "DEVICE ACTION F — REMAINING-ROUTE OVERVIEW"
echo
echo "  1. Tap 'Panoramica'. Verify the remaining route is framed north-up and the travelled portion"
echo "     is not used to enlarge the bounds. Take screenshot F."
echo "  2. Tap 'Ricentra' and verify heading-up follow resumes without restarting navigation."
read -r -p "When overview screenshot F is ready and follow has resumed, press ENTER: "
capture_ui "$overview_dump"
capture_ui_events
grep -q 'camera_mode=overview reason=control' "$ui_event_dump" || {
    echo "ERROR: route overview was not recorded." >&2
    exit 1
}
recenter_count="$(grep -c 'camera_mode=follow reason=recenter' "$ui_event_dump" || true)"
if (( recenter_count < 2 )); then
    echo "ERROR: follow did not resume after route overview." >&2
    exit 1
fi

echo "[5/8] Verifying foreground navigation continuity"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service is not active after camera interaction." >&2
    exit 1
}

echo
echo "DEVICE ACTION G — TERMINATION"
echo
echo "  Tap 'Viaggio', open 'Dettagli viaggio' and tap 'Termina navigazione'."
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

echo "[8/8] Navigation UI Phase 2 automated and operator-assisted checks complete"
echo
echo "NAVIGATION UI PHASE 2 DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output and screenshots A-F. Confirm smooth heading-up follow, Italian"
echo "street labels, the vertical arrow vehicle and lower placement, density-aware zoom, optional"
echo "trip/CNG panel, readable recenter, ten-second idle recovery, manual gesture suspension and"
echo "north-up remaining-route overview."
echo "Do not proceed to Navigation UI Phase 3 until this gate is accepted."
echo
echo "If the gate fails, return:"
echo "  $follow_dump"
echo "  $sparse_follow_dump"
echo "  $summary_dump"
echo "  $free_dump"
echo "  $recenter_dump"
echo "  $overview_dump"
echo "  $ui_event_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $logcat_dump"
