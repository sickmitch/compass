#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
map_style_url="${COMPASS_MAP_STYLE_URL:-https://tiles.openfreemap.org/styles/liberty}"
artifact_prefix=/tmp/compass-navigation-ui-phase3
follow_dump="${artifact_prefix}-follow.xml"
free_dump="${artifact_prefix}-free.xml"
diagnostics_dump="${artifact_prefix}-diagnostics.xml"
restored_follow_dump="${artifact_prefix}-restored-follow.xml"
service_dump="${artifact_prefix}-service.txt"
notification_dump="${artifact_prefix}-notification.txt"
logcat_dump="${artifact_prefix}-logcat.txt"
ui_event_dump="${artifact_prefix}-ui-events.txt"
launch_output="${artifact_prefix}-launch.txt"
route_probe="${artifact_prefix}-route-probe.json"

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
        /sdcard/compass-navigation-puck.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull /sdcard/compass-navigation-puck.xml "$target" \
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
if ! curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 90 \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.4949,"longitude":11.3426},"costing":"auto","language":"it-IT"}' \
    --output "$route_probe" "${api_base_url}api/v1/routes"; then
    echo "ERROR: default Milan-Bologna route preflight failed:" >&2
    cat "$route_probe" >&2 || true
    exit 1
fi
grep -q '"provider":"valhalla"' "$route_probe" || {
    echo "ERROR: route preflight did not return a Valhalla route." >&2
    exit 1
}
echo "Default Milan-Bologna route preflight: ready"

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
echo "DEVICE ACTION A — INTERPOLATED MATCHED VEHICLE"
echo
echo "  1. From the default preview open 'Valuta autonomia CNG'. Enter residual 65 km,"
echo "     reserve 30 km, full range 100 km and maximum detour 30 minutes."
echo "  2. Calculate the complete plan and route, start navigation and demo replay."
echo "  3. Watch at least five replay updates. The arrow must travel continuously between fixes,"
echo "     remain aligned to the route and rotate progressively through bends without teleporting."
echo "  4. During nearby urban turns, verify the camera moves close enough to read the junction and"
echo "     the arrow remains horizontally centered, low in the viewport. Take screenshot A."
read -r -p "When screenshot A is ready, press ENTER: "
capture_ui "$follow_dump"
capture_ui_events
animation_count="$(grep -c 'puck_motion mode=animate .*source=matched' "$ui_event_dump" || true)"
if (( animation_count < 2 )); then
    echo "ERROR: fewer than two matched puck animations were recorded." >&2
    exit 1
fi
grep -q 'vehicle_alignment=viewport' "$ui_event_dump" || {
    echo "ERROR: the follow vehicle was not kept vertical to the viewport." >&2
    exit 1
}
grep -q 'camera_instruction mode=follow .*target_alignment=centerline' "$ui_event_dump" || {
    echo "ERROR: the corrected centreline camera target was not recorded." >&2
    exit 1
}

echo
echo "DEVICE ACTION B — PUCK MOTION WITH CAMERA RELEASED"
echo
echo "  1. Pan and rotate the map, then leave it untouched for no more than eight seconds."
echo "  2. During at least three replay updates verify that the camera stays fixed while the arrow"
echo "     keeps moving smoothly on the route and rotates smoothly relative to the map."
echo "  3. Take screenshot B with 'Ricentra' visible."
read -r -p "When screenshot B is ready, press ENTER: "
capture_ui "$free_dump"
capture_ui_events
grep -q 'camera_mode=free reason=gesture' "$ui_event_dump" || {
    echo "ERROR: manual camera mode was not recorded." >&2
    exit 1
}
grep -q 'vehicle_alignment=map' "$ui_event_dump" || {
    echo "ERROR: free-mode vehicle rotation was not bound to the map." >&2
    exit 1
}

echo
echo "DEVICE ACTION C — PIPELINE DIAGNOSTICS"
echo
echo "  1. Tap 'Viaggio', then 'Dettagli viaggio' and 'Strumenti sviluppatore'."
echo "  2. Verify the dedicated screen reports 'Posizione guida: AGGANCIATA AL PERCORSO',"
echo "     fix accuracy, filtered speed, stabilized heading and rejected-fix count."
echo "  3. Take screenshot C and keep the developer screen open."
read -r -p "When screenshot C is ready, press ENTER: "
capture_ui "$diagnostics_dump"
capture_ui_events
grep -q 'surface=developer_tools visible=true' "$ui_event_dump" || {
    echo "ERROR: the navigation-pipeline diagnostics screen was not recorded." >&2
    exit 1
}
echo "  4. Tap 'Chiudi' to return to navigation."
read -r -p "When navigation is visible again, press ENTER: "

echo
echo "DEVICE ACTION D — FOLLOW RECOVERY"
echo
echo "  1. Tap 'Ricentra' if it is visible. Observe another three updates."
echo "  2. Verify smooth matched motion resumes without restarting navigation. The arrow must be"
echo "     horizontally centered and the zoom must remain appropriate for the next maneuver."
echo "     Take screenshot D."
read -r -p "When screenshot D is ready, press ENTER: "
capture_ui "$restored_follow_dump"
capture_ui_events
grep -q 'camera_instruction mode=follow' "$ui_event_dump" || {
    echo "ERROR: follow guidance did not resume." >&2
    exit 1
}

echo "[5/8] Verifying foreground navigation continuity"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service is not active after puck checks." >&2
    exit 1
}

echo
echo "DEVICE ACTION E — TERMINATION"
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

echo "[8/8] Navigation UI Phase 3 automated and operator-assisted checks complete"
echo
echo "NAVIGATION UI PHASE 3 DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output and screenshots A-D. Confirm continuous matched-position motion,"
echo "smooth rotation, stable route alignment, free-camera puck movement, pipeline diagnostics,"
echo "follow recovery and clean notification teardown."
echo "Do not proceed to Navigation UI Phase 4 until this gate is accepted."
echo
echo "If the gate fails, return:"
echo "  $follow_dump"
echo "  $free_dump"
echo "  $diagnostics_dump"
echo "  $restored_follow_dump"
echo "  $ui_event_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $logcat_dump"
echo "  $route_probe"
