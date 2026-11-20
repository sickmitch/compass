#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
map_style_url="${COMPASS_MAP_STYLE_URL:-https://tiles.openfreemap.org/styles/liberty}"
artifact_prefix=/tmp/compass-navigation-ui-phase4
preview_dump="${artifact_prefix}-traffic-preview.xml"
navigation_dump="${artifact_prefix}-traffic-navigation.xml"
refresh_dump="${artifact_prefix}-traffic-refresh.xml"
route_probe="${artifact_prefix}-route.json"
traffic_health="${artifact_prefix}-traffic-health.json"
service_dump="${artifact_prefix}-service.txt"
notification_dump="${artifact_prefix}-notification.txt"
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
        /sdcard/compass-navigation-traffic.xml >/dev/null 2>&1; then
        "$adb_binary" "${adb_args[@]}" pull \
            /sdcard/compass-navigation-traffic.xml "$target" >/dev/null 2>&1 || true
    else
        : >"$target"
    fi
}

capture_diagnostics() {
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
        >"$notification_dump" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump" 2>/dev/null || true
    curl --silent --show-error --connect-timeout 5 --max-time 30 \
        "${api_base_url}api/v1/traffic/health" >"$traffic_health" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/8] Requiring fresh traffic-aware Milan-Bologna Centrale routing"
readiness="$(curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    "${api_base_url}health/ready")"
echo "$readiness"
grep -q '"routing":"ready"' <<<"$readiness"
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 180 \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.5057,"longitude":11.3424},"costing":"auto","language":"it-IT"}' \
    --output "$route_probe" "${api_base_url}api/v1/routes"
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    --output "$traffic_health" "${api_base_url}api/v1/traffic/health"
python3 - "$route_probe" "$traffic_health" <<'PY'
import json
import sys

route = json.load(open(sys.argv[1], encoding="utf-8"))
health = json.load(open(sys.argv[2], encoding="utf-8"))
navigation = route.get("navigation", {})
assert route.get("provider") == "valhalla", "route provider is not Valhalla"
assert health.get("enabled") is True, "TRAFFIC_ENABLED is false"
assert health.get("provider") == "tomtom", "live provider is not TomTom"
assert health.get("provider_status") == "fresh", "traffic feed is not fresh"
assert health.get("traffic_aware_routing") is True, "Valhalla overlay routing is disabled"
assert health.get("managed_edge_count", 0) > 0, "traffic overlay has no managed edges"
assert navigation.get("traffic_state") == "fresh", "route does not report fresh traffic"
assert navigation.get("traffic_aware") is True, "route fell back to graph speeds"
assert navigation.get("traffic_delay_state") == "estimated", "delay baseline is unavailable"
delay = navigation.get("traffic_delay_seconds")
assert isinstance(delay, (int, float)) and not isinstance(delay, bool) and delay >= 0
assert navigation.get("traffic_observed_at"), "traffic observation time is missing"
print(f"Traffic-aware route ready: delay_seconds={delay:.1f}")
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
echo "DEVICE ACTION A — TRAFFIC-AWARE PREVIEW"
echo
echo "  1. Wait for the default Milan-Bologna Centrale preview. Open its route information if needed."
echo "  2. Confirm it says 'Traffico live incluso', shows a non-negative traffic delay and an"
echo "     update time. It must not say that live traffic is unavailable. Take screenshot A."
read -r -p "When screenshot A is ready, press ENTER: "
capture_ui "$preview_dump"

echo
echo "DEVICE ACTION B — TRAFFIC-AWARE CNG NAVIGATION"
echo
echo "  1. Open 'Crea viaggio': residual 65 km, reserve 30 km, full range 100 km,"
echo "     maximum detour 30 minutes. Calculate and accept the complete itinerary."
echo "  2. Start navigation and demo replay, tap 'Viaggio', then open 'Dettagli viaggio'."
echo "  3. Confirm the active route still says 'Traffico live incluso' and shows its delay/update"
echo "     time together with the remaining duration and CNG stops. Take screenshot B."
read -r -p "When screenshot B is ready, press ENTER: "
capture_ui "$navigation_dump"

echo
echo "DEVICE ACTION C — ACTIVE ROUTE TRAFFIC REFRESH"
echo
echo "  1. Open 'Strumenti sviluppatore' and tap 'Ricalcola percorso (debug)'."
echo "  2. Return to navigation after the update completes. Confirm progress was retained and the"
echo "     traffic-aware status remains visible. Take screenshot C."
read -r -p "When screenshot C is ready, press ENTER: "
capture_ui "$refresh_dump"
"$adb_binary" "${adb_args[@]}" logcat -d >"$logcat_dump"
grep -q 'route update committed: MANUAL_DEBUG .*traffic_state=fresh traffic_aware=true' \
    "$logcat_dump" || {
        echo "ERROR: no committed traffic-aware active-route refresh was recorded." >&2
        exit 1
    }

echo "[5/8] Verifying navigation continuity and fresh server traffic"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: navigation service is not active after traffic refresh." >&2
    exit 1
}
curl --fail-with-body --silent --show-error --connect-timeout 5 --max-time 30 \
    --output "$traffic_health" "${api_base_url}api/v1/traffic/health"
grep -q '"provider_status":"fresh"' "$traffic_health" || {
    echo "ERROR: traffic health is no longer fresh after the active-route update." >&2
    exit 1
}

echo
echo "DEVICE ACTION D — TERMINATION"
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

echo "[8/8] Navigation UI Phase 4 checks complete"
trap - EXIT
echo
echo "NAVIGATION UI PHASE 4 DEVICE CHECKS COMPLETED"
echo
echo "Return the complete output and screenshots A-C. Confirm numeric live-traffic timing in the"
echo "preview and CNG navigation, a committed traffic-aware active-route refresh, preserved progress"
echo "and clean service/notification teardown. Do not proceed to Navigation UI Phase 5 until accepted."
echo
echo "If the gate fails, return:"
echo "  $route_probe"
echo "  $traffic_health"
echo "  $preview_dump"
echo "  $navigation_dump"
echo "  $refresh_dump"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $logcat_dump"
