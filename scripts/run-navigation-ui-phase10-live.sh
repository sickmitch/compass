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
artifact_prefix=/tmp/compass-navigation-ui-phase10
navigation_log="$artifact_prefix-navigation.txt"
ui_log="$artifact_prefix-ui.txt"
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
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassNavigationUi:I' '*:S' \
        >"$ui_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d | \
        grep -E 'FATAL EXCEPTION|AndroidRuntime' >"$fatal_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/7] Checking the reachable Compass API"
curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    "${curl_auth[@]}" "$api_base/health/live"

echo "[2/7] Running Phase 10 navigation tests, lint and APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base/" \
        testDebugUnitTest lintDebug assembleDebug
)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }

echo "[3/7] Installing without clearing server or vehicle profiles"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[4/7] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR VISUAL CHECK — NAVIGATION UI PHASE 10

1. Create a route, start demo navigation and open Dettagli > Strumenti sviluppatore.
2. Tap "Simula deviazione (debug)" once. Confirm that a compact spinner with
   "Ricalcolo rotta" appears beside it without hiding the maneuver card or puck.
3. Confirm that the spinner disappears after commit, guidance continues on the replacement route,
   and the ten-second Prima/Ora/Differenza notice appears.
4. Repeat on a predictive CNG route. Confirm that the remaining planned stop sequence is retained,
   or safely replanned if the server rejects a now-invalid stop; destination and range/detour
   constraints must not silently become a direct unconstrained route.
5. With a downloaded route still active, make the API temporarily unreachable and simulate one
   more deviation. Confirm that the spinner ends, the existing route and maneuver guidance remain
   available, and Dettagli reports that rerouting is unavailable. Restore connectivity afterward.

No UIAutomator or screenshot inspection is performed. Screenshots are optional; your visual and
behavioral confirmation is the acceptance evidence. Press ENTER after all checks are complete.
CHECKS
read -r

echo "[5/7] Capturing bounded navigation diagnostics"
capture_diagnostics

echo "[6/7] Verifying state transitions and recalculation feedback"
grep -q 'off-route state: .*to=SUSPECTED' "$navigation_log" || {
    echo "ERROR: no suspected-deviation transition was recorded." >&2
    exit 1
}
grep -q 'off-route state: .*to=OFF_ROUTE' "$navigation_log" || {
    echo "ERROR: no confirmed off-route transition was recorded." >&2
    exit 1
}
grep -q 'route update started: OFF_ROUTE' "$navigation_log" || {
    echo "ERROR: no automatic off-route recalculation started." >&2
    exit 1
}
grep -q 'route update committed: OFF_ROUTE' "$navigation_log" || {
    echo "ERROR: no automatic off-route replacement committed." >&2
    exit 1
}
grep -q 'route_recalculation_indicator visible=true reason=OFF_ROUTE' "$ui_log" || {
    echo "ERROR: navigation state never exposed the Phase 10 recalculation indicator." >&2
    exit 1
}
grep -q 'route_recalculation_indicator visible=false' "$ui_log" || {
    echo "ERROR: recalculation indicator did not return to hidden state." >&2
    exit 1
}

echo "[7/7] Checking fatal diagnostics"
if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi

echo
echo "NAVIGATION UI PHASE 10 AUTOMATED AND OPERATOR-ASSISTED GATE COMPLETE"
echo "Return this output plus pass/fail notes for the five operator checks."
echo "Artifacts: $navigation_log $ui_log $fatal_log $service_dump"
echo "Do not proceed to Navigation UI Phase 11 until this gate is accepted."
