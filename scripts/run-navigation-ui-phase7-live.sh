#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
artifact_prefix=/tmp/compass-navigation-ui-phase7
route_probe="${artifact_prefix}-route.json"
guidance_summary="${artifact_prefix}-guidance-summary.json"
live_navigation_dump="${artifact_prefix}-live-navigation.xml"
gallery_dump="${artifact_prefix}-gallery.xml"
restored_navigation_dump="${artifact_prefix}-restored-navigation.xml"
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
        /sdcard/compass-navigation-junctions.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull \
            /sdcard/compass-navigation-junctions.xml "$target" >/dev/null 2>&1 || true
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

echo "[1/8] Checking backend route readiness and structured junction guidance"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 180 \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.5057,"longitude":11.3424},"costing":"auto","language":"it-IT"}' \
    --output "$route_probe" "${api_base_url}api/v1/routes"
python3 - "$route_probe" "$guidance_summary" <<'PY'
import json
import sys

route_path, summary_path = sys.argv[1:]
with open(route_path, encoding="utf-8") as source:
    route = json.load(source)
maneuvers = route.get("maneuvers", [])
if not maneuvers:
    raise SystemExit("ERROR: route has no maneuvers")
required = {"sign", "roundabout_exit_count"}
missing = [index for index, maneuver in enumerate(maneuvers) if not required <= maneuver.keys()]
if missing:
    raise SystemExit(f"ERROR: maneuvers missing Phase 7 fields: {missing[:8]}")
signed = [
    {"index": index, "type": maneuver["type"], "sign": maneuver["sign"]}
    for index, maneuver in enumerate(maneuvers)
    if maneuver["sign"] is not None
]
roundabouts = [
    {
        "index": index,
        "type": maneuver["type"],
        "roundabout_exit_count": maneuver["roundabout_exit_count"],
    }
    for index, maneuver in enumerate(maneuvers)
    if maneuver["roundabout_exit_count"] is not None
]
if not signed and not roundabouts:
    raise SystemExit("ERROR: live route contains no provider-backed sign or roundabout exit count")
summary = {
    "maneuver_count": len(maneuvers),
    "signed_maneuver_count": len(signed),
    "roundabout_exit_count_maneuver_count": len(roundabouts),
    "signed_examples": signed[:4],
    "roundabout_examples": roundabouts[:4],
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
echo "DEVICE ACTION A — ABSENT DATA STAYS ABSENT"
echo
echo "  1. Tap 'Crea viaggio'. Enter 65 in residual range and press the IME Next key: focus must"
echo "     move to reserve without closing the keyboard. Repeat 30 -> full range and 100 -> maximum"
echo "     detour. Enter 30; if gasoline is enabled, Next must move to that optional field."
echo "  2. Build the CNG itinerary, start navigation and demo replay."
echo "  3. On the first ordinary urban maneuvers, verify the Phase 6 card remains compact: no empty"
echo "     sign panel and no invented roundabout number may appear. Puck motion must remain smooth."
echo "  4. Wait for a slight-left/right maneuver (for example 'Mantieni la sinistra su Largo della"
echo "     Crocetta'). Its icon must enter on a centred vertical trunk, bend gently to the requested"
echo "     side and meet the triangular head without a gap or kink. The following compact icon must"
echo "     use the same geometry. Take screenshot A."
read -r -p "When screenshot A is ready, press ENTER: "
capture_ui "$live_navigation_dump"
capture_ui_events
animation_count="$(grep -c 'puck_motion mode=animate .*source=matched' "$ui_event_dump" || true)"
if (( animation_count < 2 )); then
    echo "ERROR: fewer than two matched puck animations were recorded." >&2
    exit 1
fi

echo
echo "DEVICE ACTION B — STRUCTURED SIGN AND ROUNDABOUT RENDERING"
echo
echo "  1. Tap 'Viaggio', 'Dettagli viaggio', 'Strumenti sviluppatore', then"
echo "     'Verifica iconografia manovre'. Compare types 9 and 16: they must have one centred"
echo "     vertical trunk, mirrored gentle bends and no gap or kink before either arrowhead."
echo "     Take an additional close screenshot of both types, then close the catalog."
echo "  2. Open 'Verifica segnaletica e uscite'."
echo "  3. Verify the autostrada sample shows 'Uscita 1 · A14' and Bologna destinations."
echo "  4. Verify the roundabout sample has a clear badge '2' inside the icon tile."
echo "  5. In the exit and branch icons, verify the selected right-hand trunk begins on the right,"
echo "     paths separate at one junction without crossing, and the line reaches the arrowhead base."
echo "  6. Scroll to the mirrored left branch: it must preserve the same geometry on the left."
echo "     Verify both signs show 'A1 / E 35' without clipping."
echo "     Scroll if necessary and take screenshot B."
read -r -p "When screenshot B is ready, press ENTER: "
capture_ui "$gallery_dump"
capture_ui_events
grep -q 'surface=junction_sign_gallery visible=true samples=4' "$ui_event_dump" || {
    echo "ERROR: the junction-sign debug gallery was not opened." >&2
    exit 1
}

echo
echo "DEVICE ACTION C — SESSION CONTINUITY"
echo
echo "  Close the gallery and developer tools, return to navigation and observe three replay updates."
echo "  Guidance, camera and matched puck motion must continue without restarting. Take screenshot C."
read -r -p "When screenshot C is ready, press ENTER: "
capture_ui "$restored_navigation_dump"

echo "[5/8] Verifying foreground navigation continuity"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service was lost while inspecting structured guidance." >&2
    exit 1
}
capture_ui_events
post_gallery_animation_count="$(grep -c 'puck_motion mode=animate .*source=matched' "$ui_event_dump" || true)"
if (( post_gallery_animation_count <= animation_count )); then
    echo "ERROR: no matched puck animation was recorded after closing the gallery." >&2
    exit 1
fi

echo
echo "DEVICE ACTION D — TERMINATION"
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
if grep -q "$application_id" "$active_notification_dump"; then
    echo "ERROR: active Compass notification remains after termination." >&2
    exit 1
fi

echo "[7/8] Checking the Compass process for fatal exceptions"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
if grep -E 'FATAL EXCEPTION|AndroidRuntime.*Process: org\.compass\.cng\.debug' "$logcat_dump"; then
    echo "ERROR: fatal exception found in logcat." >&2
    exit 1
fi

echo "[8/8] Navigation UI Phase 7 automated and operator-assisted checks complete"
trap - EXIT
echo
echo "NAVIGATION UI PHASE 7 DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output, guidance summary and screenshots A-C. Confirm absent-data"
echo "behavior, structured sign rendering, roundabout exit badge, session continuity and clean teardown."
echo "Do not proceed to Navigation UI Phase 8 until this gate is accepted."
echo
echo "If the gate fails, return:"
printf '  %s\n' \
    "$route_probe" \
    "$guidance_summary" \
    "$live_navigation_dump" \
    "$gallery_dump" \
    "$restored_navigation_dump" \
    "$ui_event_dump" \
    "$service_dump" \
    "$active_notification_dump" \
    "$logcat_dump"
