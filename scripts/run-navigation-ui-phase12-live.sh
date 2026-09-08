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
artifact_prefix=/tmp/compass-navigation-ui-phase12
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

echo "[2/7] Running Phase 12 navigation tests, lint and APK assembly"
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

OPERATOR VISUAL CHECK — NAVIGATION UI PHASE 12

1. Create a predictive CNG journey and start “Riproduci percorso demo”. Approaching the first stop,
   confirm the compact card changes to “In avvicinamento” and the optional voice announces it once.
2. At the station, confirm replay and route progress pause. The top card must identify the current
   intermediate destination as a CNG refuelling stop; the CNG card must show “Rifornimento”, its
   remaining planned time and a visible “Completato” action. It must not jump to the next maneuver.
3. Leave the stop active for at least 30 seconds. In Viaggio/Dettagli confirm remaining duration
   decreases while final ETA remains stable during the planned dwell. GPS loss must not dismiss the
   refuelling state. No route recalculation or stop replacement can start during this state.
4. Put Compass in background and inspect its navigation notification. It must identify CNG
   refuelling, show remaining planned time and expose “Rifornimento completato”. Return to Compass.
5. Confirm completion before the full dwell expires. Guidance and replay must resume, the stop must
   become “Completata”, unused dwell must disappear from remaining trip duration, and voice may say
   “Rifornimento completato. Riprendi il percorso.” exactly once.
6. If the route has another CNG stop, confirm it becomes the next planned stop. Otherwise confirm
   ordinary guidance continues toward the final destination. Existing route/CNG constraints,
   traffic state, map camera and puck behavior must remain unchanged.

The automated suite covers a visit that exceeds its planned duration: Compass waits for explicit
confirmation and pushes final ETA by the excess time. The live gate does not require waiting twenty
minutes. No UIAutomator or screenshot inspection is performed. Press ENTER after all checks.
CHECKS
read -r

echo "[5/7] Capturing bounded navigation diagnostics"
capture_diagnostics

echo "[6/7] Verifying stop/refuelling transitions"
grep -q 'cng_guidance station=.*lifecycle=refueling' "$ui_log" || {
    echo "ERROR: no first-class refuelling state was recorded." >&2
    exit 1
}
grep -q 'demo replay paused for CNG refuelling' "$navigation_log" || {
    echo "ERROR: demo navigation did not pause at the CNG stop." >&2
    exit 1
}
grep -q 'fuel stop completed by operator; navigation resumed' "$navigation_log" || {
    echo "ERROR: explicit completion/resume was not recorded." >&2
    exit 1
}

echo "[7/7] Checking fatal diagnostics"
if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi

echo
echo "NAVIGATION UI PHASE 12 AUTOMATED AND OPERATOR-ASSISTED GATE COMPLETE"
echo "Return this output plus pass/fail notes for the six operator checks."
echo "Artifacts: $navigation_log $ui_log $fatal_log $service_dump"
echo "Do not proceed to Navigation UI Phase 13 until this gate is accepted."
