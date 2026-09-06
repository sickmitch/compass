#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
artifact_prefix=/tmp/compass-navigation-ui-phase6
live_turn_dump="${artifact_prefix}-live-turn.xml"
gallery_start_dump="${artifact_prefix}-gallery-start.xml"
gallery_junction_dump="${artifact_prefix}-gallery-junction.xml"
gallery_transport_dump="${artifact_prefix}-gallery-transport.xml"
restored_navigation_dump="${artifact_prefix}-restored-navigation.xml"
route_probe="${artifact_prefix}-route.json"
service_dump="${artifact_prefix}-service.txt"
notification_dump="${artifact_prefix}-notification.txt"
active_notification_dump="${artifact_prefix}-notification-active.txt"
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
        /sdcard/compass-navigation-maneuvers.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull \
            /sdcard/compass-navigation-maneuvers.xml "$target" >/dev/null 2>&1 || true
    else
        echo "NOTE: UI XML unavailable while MapLibre/replay is animating; screenshots remain authoritative."
        : >"$target"
    fi
}

capture_ui_events() {
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassNavigationUi:I' '*:S' \
        >"$ui_event_dump" 2>/dev/null || true
}

capture_diagnostics() {
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
        >"$notification_dump" 2>/dev/null || true
    awk -f "$repo_root/scripts/extract-active-android-notifications.awk" \
        "$notification_dump" >"$active_notification_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump" 2>/dev/null || true
    capture_ui_events
}
trap capture_diagnostics EXIT

echo "[1/8] Checking backend route readiness"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 180 \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.5057,"longitude":11.3424},"costing":"auto","language":"it-IT"}' \
    --output "$route_probe" "${api_base_url}api/v1/routes"
grep -q '"provider":"valhalla"' "$route_probe"

echo "[2/8] Preparing Android connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null
fi

echo "[3/8] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/8] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell pm clear "$application_id" >/dev/null
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — LIVE PRIMARY AND FOLLOWING MANEUVERS"
echo
echo "  1. Build the usual 65/30/100/30 CNG itinerary, start navigation and demo replay."
echo "  2. Wait for the early 'Svolta a destra' instruction followed by a left maneuver."
echo "  3. Verify the large icon is a crisp right-angle route arrow, while the small 'Poi' icon"
echo "     independently previews the following left maneuver. Both must have clear theme contrast,"
echo "     and no thin shaft may protrude beyond either triangular arrowhead."
echo "  4. Take screenshot A."
read -r -p "When screenshot A is ready, press ENTER: "
capture_ui "$live_turn_dump"
capture_ui_events
animation_count="$(grep -c 'puck_motion mode=animate .*source=matched' "$ui_event_dump" || true)"
if (( animation_count < 2 )); then
    echo "ERROR: fewer than two matched puck animations were recorded." >&2
    exit 1
fi

echo
echo "DEVICE ACTION B — START, DESTINATION AND TURN CATALOG"
echo
echo "  1. Tap 'Viaggio', 'Dettagli viaggio', 'Strumenti sviluppatore', then"
echo "     'Verifica iconografia manovre'."
echo "  2. At the top of the catalog, inspect types 0–16. Verify start/destination variants and"
echo "     straight, slight, normal, sharp and U-turn arrows are distinct and correctly mirrored."
echo "     Their shafts must terminate cleanly underneath each triangular arrowhead."
echo "  3. Take screenshot B with the catalog title and representative turn rows visible."
read -r -p "When screenshot B is ready, press ENTER: "
capture_ui "$gallery_start_dump"
capture_ui_events
grep -q 'surface=maneuver_gallery visible=true types=37' "$ui_event_dump" || {
    echo "ERROR: the complete maneuver gallery was not opened." >&2
    exit 1
}

echo
echo "DEVICE ACTION C — ROAD-JUNCTION FAMILIES"
echo
echo "  Scroll to types 17–27. Verify ramp, exit and keep arrows expose the selected branch;"
echo "  merge converges into one path; roundabout entry and exit are distinguishable. Take screenshot C."
read -r -p "When screenshot C is ready, press ENTER: "
capture_ui "$gallery_junction_dump"

echo
echo "DEVICE ACTION D — FERRY, TRANSIT AND ACCESSIBILITY LABELS"
echo
echo "  Scroll to the end (types 28–36). Verify ferry and transit/connection families are legible,"
echo "  every row has an Italian label, and type 36 is present. Take screenshot D."
read -r -p "When screenshot D is ready, press ENTER: "
capture_ui "$gallery_transport_dump"

echo
echo "  Tap 'Chiudi' in the catalog, then 'Chiudi' in developer tools. Return to navigation and"
echo "  observe three replay updates; guidance must continue without a restart."
read -r -p "When active navigation is visible again, press ENTER: "
capture_ui "$restored_navigation_dump"

echo "[5/8] Verifying foreground navigation continuity"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service was lost while inspecting maneuver iconography." >&2
    exit 1
}
capture_ui_events
post_gallery_animation_count="$(grep -c 'puck_motion mode=animate .*source=matched' "$ui_event_dump" || true)"
if (( post_gallery_animation_count <= animation_count )); then
    echo "ERROR: no matched puck animation was recorded after closing the gallery." >&2
    exit 1
fi

echo
echo "DEVICE ACTION E — TERMINATION"
echo
echo "  Tap 'Viaggio', open 'Dettagli viaggio' and tap 'Termina navigazione'."
read -r -p "When navigation is terminated, press ENTER: "

echo "[6/8] Verifying foreground service and active-notification teardown"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: foreground navigation service remains active." >&2
    exit 1
fi
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact >"$notification_dump"
awk -f "$repo_root/scripts/extract-active-android-notifications.awk" \
    "$notification_dump" >"$active_notification_dump"
grep -q 'Notification List:' "$active_notification_dump" || {
    echo "ERROR: could not isolate the active Android notification list." >&2
    exit 1
}
if grep -Fq "pkg=$application_id " "$active_notification_dump"; then
    echo "ERROR: foreground navigation notification remains visible." >&2
    exit 1
fi

echo "[7/8] Checking the Compass process for fatal exceptions"
capture_diagnostics
if grep -Eq 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' "$logcat_dump"; then
    echo "ERROR: Compass emitted a fatal exception." >&2
    exit 1
fi

echo "[8/8] Navigation UI Phase 6 automated and operator-assisted checks complete"
trap - EXIT

echo
echo "NAVIGATION UI PHASE 6 DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output and screenshots A-D. Confirm distinct primary/following turn icons,"
echo "all Valhalla types 0-36 in the debug catalog, mirrored turn geometry, road-junction families,"
echo "ferry/transit families, Italian accessibility labels, navigation continuity and clean teardown."
echo "Do not proceed to Navigation UI Phase 7 until this gate is accepted."
echo
echo "If the gate fails, return:"
echo "  $route_probe"
echo "  $live_turn_dump"
echo "  $gallery_start_dump"
echo "  $gallery_junction_dump"
echo "  $gallery_transport_dump"
echo "  $restored_navigation_dump"
echo "  $ui_event_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $active_notification_dump"
echo "  $logcat_dump"
