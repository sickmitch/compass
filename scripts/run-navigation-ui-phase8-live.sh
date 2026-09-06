#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
artifact_prefix=/tmp/compass-navigation-ui-phase8
route_probe="${artifact_prefix}-route.json"
context_summary="${artifact_prefix}-road-context.json"
day_dump="${artifact_prefix}-day.xml"
night_dump="${artifact_prefix}-night.xml"
summary_dump="${artifact_prefix}-trip-summary.xml"
details_dump="${artifact_prefix}-trip-details.xml"
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
        echo "If the backend is remote, keep this tunnel open separately:"
        echo "  ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER"
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
        /sdcard/compass-navigation-speed-limit.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull \
            /sdcard/compass-navigation-speed-limit.xml "$target" >/dev/null 2>&1 || true
    else
        echo "NOTE: UI XML unavailable while MapLibre/replay is animating; screenshots remain authoritative."
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
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief \
        -s 'CompassNavigationUi:I' 'CompassNavigationMap:I' '*:S' \
        >"$ui_event_dump" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/8] Checking backend route readiness and graph-backed speed limits"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 180 \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.5057,"longitude":11.3424},"costing":"auto","language":"it-IT"}' \
    --output "$route_probe" "${api_base_url}api/v1/routes"
python3 - "$route_probe" "$context_summary" <<'PY'
import json
import sys

route_path, summary_path = sys.argv[1:]
with open(route_path, encoding="utf-8") as source:
    route = json.load(source)
if route.get("speed_limit_source") != "valhalla_graph":
    raise SystemExit("ERROR: live route has no graph-backed speed-limit source")
profile = route.get("speed_limits")
if not isinstance(profile, list) or not profile:
    raise SystemExit("ERROR: live route has no numeric speed-limit ranges")
maximum_shape_index = max(
    (item["end_shape_index"] for item in route.get("maneuvers", [])),
    default=0,
)
previous_end = 0
for index, item in enumerate(profile):
    begin = item.get("begin_shape_index")
    end = item.get("end_shape_index")
    limit = item.get("speed_limit_kph")
    if not all(isinstance(value, int) and not isinstance(value, bool) for value in (begin, end, limit)):
        raise SystemExit(f"ERROR: malformed speed-limit range {index}")
    if (
        not 0 <= begin < end <= maximum_shape_index
        or begin < previous_end
        or not 1 <= limit <= 250
    ):
        raise SystemExit(f"ERROR: invalid speed-limit range {index}")
    previous_end = end
summary = {
    "speed_limit_source": route["speed_limit_source"],
    "range_count": len(profile),
    "distinct_limits_kph": sorted({item["speed_limit_kph"] for item in profile}),
    "first_ranges": profile[:10],
}
with open(summary_path, "w", encoding="utf-8") as target:
    json.dump(summary, target, indent=2, ensure_ascii=False)
    target.write("\n")
print(json.dumps(summary, indent=2, ensure_ascii=False))
PY

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
echo "DEVICE ACTION A — LIVE MATCHED SPEED LIMIT"
echo
echo "  Start the default route and tap 'Riproduci percorso demo'. Wait until the puck is matched"
echo "  and the circular speed-limit badge appears at lower left. Confirm that it has a white face,"
echo "  red border and black number, remains above the MapLibre attribution, and does not cover the"
echo "  trip controls. Perform an aggressive pinch zoom-in without panning: the puck must stay on the"
echo "  horizontal line one quarter of the viewport height above the bottom during and after zoom, and"
echo "  the camera must remain in follow mode. Let replay advance until a limit is logged. Take A."
read -r -p "When screenshot A is ready, press ENTER: "
capture_ui "$day_dump"
capture_diagnostics

python3 - "$route_probe" "$ui_event_dump" <<'PY'
import json
import re
import sys

route_path, log_path = sys.argv[1:]
with open(route_path, encoding="utf-8") as source:
    profile = json.load(source)["speed_limits"]
with open(log_path, encoding="utf-8", errors="replace") as source:
    log = source.read()
matches = [
    (int(segment), int(limit))
    for segment, limit in re.findall(
        r"road_context segment=(\d+) speed_limit_kph=(\d+)", log
    )
]
if not matches:
    raise SystemExit("ERROR: no matched numeric speed limit was logged")
for segment, limit in matches:
    if not any(
        item["begin_shape_index"] <= segment < item["end_shape_index"]
        and item["speed_limit_kph"] == limit
        for item in profile
    ):
        raise SystemExit(
            f"ERROR: displayed limit {limit} does not cover matched segment {segment}"
        )
print(f"validated_displayed_speed_limits={len(matches)}")
PY

echo
echo "DEVICE ACTION B — NIGHT THEME AND SESSION CONTINUITY"
echo
echo "  Switch Android to the dark system theme while navigation remains active. Confirm the badge"
echo "  keeps its regulatory white/red/black colors, MapLibre changes to night, guidance and puck"
echo "  motion continue, and the badge still does not cover attribution. Take screenshot B."
read -r -p "When screenshot B is ready, press ENTER: "
capture_ui "$night_dump"

echo "[5/8] Verifying foreground navigation continuity"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service was lost during the theme transition." >&2
    exit 1
}
capture_diagnostics
grep -Eq 'road_context segment=[0-9]+ speed_limit_kph=[0-9]+' "$ui_event_dump" || {
    echo "ERROR: numeric graph-backed road context was not observed." >&2
    exit 1
}
grep -q 'camera_interaction=zoom mode=follow' "$ui_event_dump" || {
    echo "ERROR: the follow-mode pinch zoom was not observed." >&2
    exit 1
}
grep -q 'camera_interaction=zoom mode=follow .*retained=true' "$ui_event_dump" || {
    echo "ERROR: the manual follow zoom was not retained after the pinch." >&2
    exit 1
}
if grep -q 'camera_mode=free reason=gesture' "$ui_event_dump"; then
    echo "ERROR: pinch zoom incorrectly released the follow camera." >&2
    exit 1
fi
grep -q 'puck_motion mode=animate .*source=matched' "$ui_event_dump" || {
    echo "ERROR: matched puck animation was not observed." >&2
    exit 1
}
grep -q 'puck_motion mode=animate .*camera_sync=puck_pose' "$ui_event_dump" || {
    echo "ERROR: camera movement was not synchronized to the rendered puck pose." >&2
    exit 1
}

echo
echo "DEVICE ACTION C — COMPACT TRIP CONTROLS AND CONTENT-SIZED DETAILS"
echo
echo "  Tap 'Viaggio'. Confirm 'Nascondi ﹀' is inside the lower panel on the left and 'Dettagli ︿'"
echo "  is on the right. Both chevrons must be vertically centered with their labels. There must be"
echo "  no floating Nascondi button near the puck, and the puck must move upward to remain at 75% of"
echo "  the unobscured map area above the panel. Take screenshot C."
read -r -p "When screenshot C is ready, press ENTER: "
capture_ui "$summary_dump"
capture_diagnostics
grep -q 'trip_summary visible=true' "$ui_event_dump" || {
    echo "ERROR: the compact trip summary was not opened." >&2
    exit 1
}
grep -Eq 'trip_summary_layout bottom_obstruction_px=[1-9][0-9]*' "$ui_event_dump" || {
    echo "ERROR: the trip panel height was not applied to the follow-camera viewport." >&2
    exit 1
}
echo "  Tap 'Dettagli'. Confirm the sheet rises only enough to display its content instead of taking"
echo "  90% of the viewport. It must remain scrollable when CNG stops make the content taller. Take D."
read -r -p "When screenshot D is ready, press ENTER: "
capture_ui "$details_dump"
capture_diagnostics
grep -q 'surface=trip_details visible=true' "$ui_event_dump" || {
    echo "ERROR: the trip details sheet was not opened." >&2
    exit 1
}

echo
echo "DEVICE ACTION D — TERMINATION"
echo
echo "  With Dettagli still open, tap 'Termina navigazione'."
read -r -p "When navigation is terminated, press ENTER: "

echo "[6/8] Verifying service and notification teardown"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: foreground navigation service remains active." >&2
    exit 1
fi
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact >"$notification_dump"
awk -f "$repo_root/scripts/extract-active-android-notifications.awk" \
    "$notification_dump" >"$active_notification_dump"
if grep -q "$application_id" "$active_notification_dump"; then
    echo "ERROR: Compass still owns an active notification." >&2
    exit 1
fi

echo "[7/8] Checking fatal diagnostics"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
if grep -Eq "FATAL EXCEPTION|AndroidRuntime.*$application_id" "$logcat_dump"; then
    echo "ERROR: fatal Android exception detected." >&2
    exit 1
fi

echo "[8/8] Navigation UI Phase 8 automated and operator-assisted checks complete"
echo
echo "Artifacts:"
printf '  %s\n' "$route_probe" "$context_summary" "$day_dump" "$night_dump" \
    "$summary_dump" "$details_dump" "$service_dump" "$active_notification_dump" \
    "$ui_event_dump" "$logcat_dump"
echo
echo "Return the complete output, the road-context summary and screenshots A-D."
echo "Do not proceed to Navigation UI Phase 9 until this gate is accepted."
