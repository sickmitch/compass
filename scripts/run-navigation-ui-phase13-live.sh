#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"

: "${JAVA_HOME:?Set JAVA_HOME to JDK 17.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"
: "${COMPASS_API_BASE_URL:?Set COMPASS_API_BASE_URL to the reachable Compass API base URL.}"

api_base="${COMPASS_API_BASE_URL%/}"
if [[ "$api_base" != https://* && "${COMPASS_ALLOW_HTTP_LIVE_GATE:-false}" != "true" ]]; then
    echo "ERROR: HTTPS is required. Set COMPASS_ALLOW_HTTP_LIVE_GATE=true only for an explicit HTTP fallback." >&2
    exit 1
fi

adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"
service_class=org.compass.cng.navigation.NavigationForegroundService
artifact_prefix=/tmp/compass-navigation-ui-phase13
navigation_log="$artifact_prefix-navigation.txt"
fatal_log="$artifact_prefix-fatal.txt"
service_dump="$artifact_prefix-service.txt"

[[ -x "$JAVA_HOME/bin/java" ]] || { echo "ERROR: JDK 17 not found." >&2; exit 1; }
[[ -x "$adb_binary" ]] || { echo "ERROR: adb not found." >&2; exit 1; }

adb_args=()
if [[ -n "${COMPASS_ADB_SERIAL:-}" ]]; then
    adb_args=(-s "$COMPASS_ADB_SERIAL")
else
    mapfile -t devices < <("$adb_binary" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ "${#devices[@]}" -ne 1 ]]; then
        echo "ERROR: expected exactly one authorized Android device; found ${#devices[@]}." >&2
        "$adb_binary" devices -l >&2
        exit 1
    fi
    adb_args=(-s "${devices[0]}")
fi

curl_auth=()
if [[ -n "${COMPASS_CHECK_USER:-}" || -n "${COMPASS_CHECK_PASSWORD:-}" ]]; then
    : "${COMPASS_CHECK_USER:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    : "${COMPASS_CHECK_PASSWORD:?Set both COMPASS_CHECK_USER and COMPASS_CHECK_PASSWORD.}"
    curl_auth=(--user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD")
fi

capture_diagnostics() {
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassNavigation:I' '*:S' \
        >"$navigation_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d | \
        grep -E 'FATAL EXCEPTION|AndroidRuntime' >"$fatal_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/8] Checking the reachable Compass API"
curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    "${curl_auth[@]}" "$api_base/health/live"

echo "[2/8] Running Phase 13 tests, lint and Android 0.23.1 assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base/" \
        testDebugUnitTest lintDebug assembleDebug
)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }

echo "[3/8] Installing without clearing persistent server or vehicle profiles"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[4/8] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR CHECK A — LOCAL GUIDANCE DURING A REAL OUTAGE

Create a CNG-aware trip and start “Riproduci percorso demo”. Let it advance past at least one
maneuver and note the current maneuver, progress and next CNG stop. Disable both Wi-Fi and mobile
data (or enable airplane mode) without closing Compass. Confirm that:

- the route, puck, maneuver countdown, voice guidance and CNG waypoint guidance continue locally;
- the UI says that the network is absent and that local guidance remains active;
- traffic is explicitly not refreshable and live CNG opening/price claims disappear;
- no repeated reroute loop starts.

Screenshots are optional. Leave the device offline and press ENTER.
CHECKS
read -r

echo "[5/8] Recreating the Compass process while the device remains offline"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR CHECK B — DURABLE ACTIVE-SESSION RESUME

Compass must reopen directly on active guidance and restart its foreground service without asking
you to recreate the route. Confirm that route geometry, last checkpointed puck/progress, current
maneuver, remaining values, voice setting, CNG stop lifecycle and destination are preserved. A CNG
refuelling visit, if active, must still require explicit completion and its wall-clock countdown
must remain correct. Previously visited map resources may remain visible from MapLibre's ambient
cache; arbitrary uncached regions are not promised offline.

Keep Compass open and press ENTER while it is still offline.
CHECKS
read -r

"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: foreground navigation service was not resumed after process recreation." >&2
    exit 1
}

cat <<'CHECKS'

OPERATOR CHECK C — SAFE NETWORK RECOVERY

Restore Wi-Fi or mobile data. Compass should show the normal “Ricalcolo rotta” feedback,
refresh through the existing CNG-aware route path, and remain in navigation. It must preserve the
destination, remaining CNG constraints and unfinished stops; it must not jump back to route origin.
After success, the offline/recovery warnings disappear and live traffic/CNG evidence may return only
from the refreshed route. If the server is not yet reachable at the first validated-network event,
Compass retries with bounded 5/15/60-second backoff rather than a tight loop. Then terminate
navigation from Dettagli and press ENTER.
CHECKS
read -r

echo "[6/8] Capturing bounded diagnostics"
capture_diagnostics

echo "[7/8] Verifying offline, restore and recovery transitions"
grep -q 'navigation connectivity: offline; downloaded_route_retained=true' "$navigation_log" || {
    echo "ERROR: Android network loss was not recorded." >&2
    exit 1
}
grep -q 'navigation route restored from cache: active=true progress=true' "$navigation_log" || {
    echo "ERROR: active navigation progress was not restored." >&2
    exit 1
}
grep -q 'navigation location mode=demo_replay' "$navigation_log" || {
    echo "ERROR: the restored debug replay position source was not retained." >&2
    exit 1
}
grep -q 'route update started: CONNECTIVITY_RECOVERY' "$navigation_log" || {
    echo "ERROR: safe connectivity-recovery refresh did not start." >&2
    exit 1
}
grep -q 'route update committed: CONNECTIVITY_RECOVERY' "$navigation_log" || {
    echo "ERROR: connectivity-recovery refresh did not complete." >&2
    exit 1
}

echo "[8/8] Checking fatal diagnostics"
if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi

echo
echo "NAVIGATION UI PHASE 13 AUTOMATED AND OPERATOR-ASSISTED GATE COMPLETE"
echo "Return this complete output and pass/fail notes for operator checks A-C."
echo "Artifacts: $navigation_log $fatal_log $service_dump"
echo "Do not proceed to Navigation UI Phase 14 until this gate is accepted."
