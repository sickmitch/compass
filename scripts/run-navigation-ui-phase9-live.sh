#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
artifact_prefix=/tmp/compass-navigation-ui-phase9
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

echo "[1/7] Checking the accepted Phase 8 speed-limit route contract"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 180 \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.5057,"longitude":11.3424},"costing":"auto","language":"it-IT"}' \
    --output "$route_probe" "${api_base_url}api/v1/routes"
python3 - "$route_probe" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    route = json.load(source)
if route.get("speed_limit_source") != "valhalla_graph" or not route.get("speed_limits"):
    raise SystemExit("ERROR: Phase 8 graph-backed speed-limit profile is unavailable")
print(
    json.dumps(
        {
            "speed_limit_source": route["speed_limit_source"],
            "range_count": len(route["speed_limits"]),
            "distinct_limits_kph": sorted(
                {item["speed_limit_kph"] for item in route["speed_limits"]}
            ),
        },
        indent=2,
    )
)
PY

echo "[2/7] Preparing Android connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null
fi

echo "[3/7] Running Android tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/7] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell pm clear "$application_id" >/dev/null
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "OPERATOR VISUAL CHECK — SOURCE-BACKED SPEED WARNING"
echo
echo "  Start the default route and tap 'Riproduci percorso demo'. The deterministic replay runs at"
echo "  about 79 km/h. Wait on a 30 or 50 km/h covered segment. Confirm that the regulatory sign"
echo "  keeps its white face and red border, gains a second bright-red outer ring, and changes its"
echo "  number from black to red. Guidance, puck anchoring and trip controls must remain unchanged."
echo "  Switch Android to dark theme and confirm the same warning remains legible. Screenshots are"
echo "  optional. Then terminate navigation from Dettagli. No UIAutomator or screenshot inspection"
echo "  is performed: your visual confirmation is the acceptance evidence."
read -r -p "When your visual checks are complete and navigation is terminated, press ENTER: "

capture_diagnostics

python3 - "$ui_event_dump" <<'PY'
import re
import sys

with open(sys.argv[1], encoding="utf-8", errors="replace") as source:
    log = source.read()
matches = [
    (int(speed), int(limit))
    for speed, limit in re.findall(
        r"speed_compliance .*to=over_limit speed_kph=(\d+) limit_kph=(\d+)", log
    )
]
if not matches:
    raise SystemExit("ERROR: no source-backed over-limit transition was logged")
if not any(speed >= limit + 5 for speed, limit in matches):
    raise SystemExit("ERROR: warning did not respect the configured entry buffer")
print(f"validated_over_limit_transitions={len(matches)}")
PY

echo "[5/7] Verifying foreground-service and notification teardown"
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

echo "[6/7] Checking fatal diagnostics"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
if grep -Eq "FATAL EXCEPTION|AndroidRuntime.*$application_id" "$logcat_dump"; then
    echo "ERROR: fatal Android exception detected." >&2
    exit 1
fi

echo "[7/7] Navigation UI Phase 9 automated and operator-assisted checks complete"
echo
echo "Artifacts:"
printf '  %s\n' "$route_probe" "$service_dump" "$active_notification_dump" \
    "$ui_event_dump" "$logcat_dump"
echo
echo "Return the complete output and your visual confirmation; screenshots are optional."
echo "Do not proceed to Navigation UI Phase 10 until this gate is accepted."
