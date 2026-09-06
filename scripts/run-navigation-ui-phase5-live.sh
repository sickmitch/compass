#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
day_style_url="${COMPASS_MAP_DAY_STYLE_URL:-asset://compass-day.json}"
night_style_url="${COMPASS_MAP_NIGHT_STYLE_URL:-asset://compass-night.json}"
artifact_prefix=/tmp/compass-navigation-ui-phase5
day_preview_dump="${artifact_prefix}-day-preview.xml"
day_navigation_dump="${artifact_prefix}-day-navigation.xml"
night_navigation_dump="${artifact_prefix}-night-navigation.xml"
restored_day_dump="${artifact_prefix}-restored-day.xml"
route_probe="${artifact_prefix}-route.json"
service_dump="${artifact_prefix}-service.txt"
notification_dump="${artifact_prefix}-notification.txt"
active_notification_dump="${artifact_prefix}-notification-active.txt"
logcat_dump="${artifact_prefix}-logcat.txt"
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
command -v unzip >/dev/null || { echo "ERROR: unzip is required to inspect the APK." >&2; exit 1; }
[[ "$day_style_url" == "asset://compass-day.json" ]] || {
    echo "ERROR: this acceptance gate validates the bundled Compass day style; unset its override." >&2
    exit 1
}
[[ "$night_style_url" == "asset://compass-night.json" ]] || {
    echo "ERROR: this acceptance gate validates the bundled Compass night style; unset its override." >&2
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
        /sdcard/compass-navigation-theme.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull \
            /sdcard/compass-navigation-theme.xml "$target" >/dev/null 2>&1 || true
    else
        : >"$target"
    fi
}

capture_diagnostics() {
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
        >"$notification_dump" 2>/dev/null || true
    awk -f "$repo_root/scripts/extract-active-android-notifications.awk" \
        "$notification_dump" >"$active_notification_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump" 2>/dev/null || true
}

original_night_mode=""
restore_device_theme() {
    case "${original_night_mode,,}" in
        *custom_schedule*) "$adb_binary" "${adb_args[@]}" shell cmd uimode night custom_schedule >/dev/null 2>&1 || true ;;
        *yes*) "$adb_binary" "${adb_args[@]}" shell cmd uimode night yes >/dev/null 2>&1 || true ;;
        *no*) "$adb_binary" "${adb_args[@]}" shell cmd uimode night no >/dev/null 2>&1 || true ;;
        *auto*) "$adb_binary" "${adb_args[@]}" shell cmd uimode night auto >/dev/null 2>&1 || true ;;
    esac
}

cleanup() {
    local status=$?
    set +e
    capture_diagnostics
    restore_device_theme
    return "$status"
}

echo "[1/9] Validating bundled Compass day/night style documents"
python3 - "$repo_root" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1]) / "android/app/src/main/assets"
styles = [json.loads((root / f"compass-{theme}.json").read_text()) for theme in ("day", "night")]
assert all(style["version"] == 8 for style in styles)
assert [style["metadata"]["compass:theme"] for style in styles] == ["day", "night"]
assert all(not any(layer["type"] == "fill-extrusion" for layer in style["layers"]) for style in styles)
assert all(any(layer["id"] == "road-label" for layer in style["layers"]) for style in styles)
print("Bundled low-noise styles: valid")
PY

echo "[2/9] Checking backend route readiness"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 180 \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.5057,"longitude":11.3424},"costing":"auto","language":"it-IT"}' \
    --output "$route_probe" "${api_base_url}api/v1/routes"
grep -q '"provider":"valhalla"' "$route_probe"

echo "[3/9] Preparing Android connectivity and preserving device theme"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
original_night_mode="$("$adb_binary" "${adb_args[@]}" shell cmd uimode night 2>/dev/null | tr -d '\r')"
case "${original_night_mode,,}" in
    *yes* | *no* | *auto* | *custom_schedule*) ;;
    *) echo "ERROR: cannot preserve the device's current night-mode setting." >&2; exit 1 ;;
esac
trap cleanup EXIT
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null
fi

echo "[4/9] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        -PCOMPASS_MAP_DAY_STYLE_URL="$day_style_url" \
        -PCOMPASS_MAP_NIGHT_STYLE_URL="$night_style_url" \
        testDebugUnitTest lintDebug assembleDebug
)
unzip -t "$apk_path" assets/compass-day.json >/dev/null
unzip -t "$apk_path" assets/compass-night.json >/dev/null

echo "[5/9] Installing Compass and opening the day style"
"$adb_binary" "${adb_args[@]}" shell cmd uimode night no >/dev/null
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell pm clear "$application_id" >/dev/null
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — DAY ROUTE PREVIEW"
echo
echo "  1. Wait for the Milan-Bologna Centrale preview and take screenshot A."
echo "  2. Verify the map is light, roads have a restrained hierarchy, the route and endpoints"
echo "     remain prominent, labels are legible/Italian and no 3D building blocks cover the route."
read -r -p "When screenshot A is ready, press ENTER: "
capture_ui "$day_preview_dump"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
grep -q 'map_style_loaded surface=preview theme=day source=bundled' "$logcat_dump" || {
    echo "ERROR: bundled day preview style did not finish loading." >&2
    exit 1
}

echo
echo "DEVICE ACTION B — DAY ACTIVE NAVIGATION"
echo
echo "  1. Build the usual 65/30/100/30 CNG itinerary, start navigation and demo replay."
echo "  2. Observe at least three updates and take screenshot B near an urban maneuver."
echo "  3. Verify the bright route, vehicle, CNG markers and road labels are distinct without"
echo "     changing the accepted Phase 3 camera placement or motion."
read -r -p "When screenshot B is ready, press ENTER: "
capture_ui "$day_navigation_dump"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
grep -q 'map_style_loaded surface=navigation theme=day source=bundled' "$logcat_dump" || {
    echo "ERROR: bundled day navigation style did not finish loading." >&2
    exit 1
}

echo "[6/9] Switching the running device to night mode"
"$adb_binary" "${adb_args[@]}" shell cmd uimode night yes >/dev/null
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" >/dev/null

echo
echo "DEVICE ACTION C — LIVE NIGHT TRANSITION"
echo
echo "  1. Wait for the same active navigation to reappear; do not restart it."
echo "  2. Observe at least three replay updates and take screenshot C."
echo "  3. Verify the basemap, labels, route, travelled line, vehicle and CNG markers all have"
echo "     night contrast, with no bright day-map flash left on screen."
read -r -p "When screenshot C is ready, press ENTER: "
capture_ui "$night_navigation_dump"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
grep -q 'map_style_loaded surface=navigation theme=night source=bundled' "$logcat_dump" || {
    echo "ERROR: bundled night navigation style did not finish loading." >&2
    exit 1
}
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service was lost during the day/night transition." >&2
    exit 1
}

echo "[7/9] Switching back to day mode while navigation remains active"
"$adb_binary" "${adb_args[@]}" shell cmd uimode night no >/dev/null
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" >/dev/null

echo
echo "DEVICE ACTION D — DAY RECOVERY"
echo
echo "  Confirm the same navigation progress and CNG plan remain active, watch three updates and"
echo "  take screenshot D after the bundled day style returns."
read -r -p "When screenshot D is ready, press ENTER: "
capture_ui "$restored_day_dump"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
day_load_count="$(grep -c 'map_style_loaded surface=navigation theme=day source=bundled' "$logcat_dump")"
[[ "$day_load_count" -ge 2 ]] || {
    echo "ERROR: active navigation did not restore the bundled day style." >&2
    exit 1
}

echo
echo "DEVICE ACTION E — TERMINATION"
echo
echo "  Tap 'Viaggio', open 'Dettagli viaggio' and tap 'Termina navigazione'."
read -r -p "When navigation is terminated, press ENTER: "

echo "[8/9] Verifying clean service and notification teardown"
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

echo "[9/9] Checking for fatal exceptions and restoring the operator's theme"
capture_diagnostics
if grep -Eq 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' "$logcat_dump"; then
    echo "ERROR: Compass emitted a fatal exception." >&2
    exit 1
fi
restore_device_theme
trap - EXIT

echo
echo "NAVIGATION UI PHASE 5 DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output and screenshots A-D. Confirm low-noise day and night cartography,"
echo "Italian/legible road context, route/vehicle/CNG contrast, live theme transitions, preserved"
echo "navigation progress and clean notification teardown."
echo
echo "If the gate fails, return:"
echo "  $route_probe"
echo "  $day_preview_dump"
echo "  $day_navigation_dump"
echo "  $night_navigation_dump"
echo "  $restored_day_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $active_notification_dump"
echo "  $logcat_dump"
