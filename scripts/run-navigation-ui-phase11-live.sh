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
artifact_prefix=/tmp/compass-navigation-ui-phase11
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

echo "[2/7] Running Phase 11 navigation tests, lint and APK assembly"
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

OPERATOR VISUAL CHECK — NAVIGATION UI PHASE 11

1. Create a predictive CNG journey with a saved vehicle profile and start navigation or route replay.
   Confirm that the next CNG stop has a compact card directly under the maneuver card, without
   opening Viaggio, and that it shows station, road distance, ETA and lifecycle. The ETA and the
   traffic-update time in Dettagli must use the phone's local timezone, not the backend UTC clock.
2. Tap the CNG card. Confirm that Dettagli explains why the stop is planned and shows the configured
   dwell (normally 20 min). If valid opening evidence and a fresh price were present at selection,
   confirm they remain clearly visible. Missing, stale or cache-only evidence must not appear live.
3. Continue the replay through the stop. Confirm the label progresses from Pianificata to
   In avvicinamento and Sei arrivato; after passing the waypoint it becomes Completata and the next
   planned CNG stop becomes the compact card, if one exists.
4. From Dettagli choose "Salta o sostituisci la prossima tappa CNG". Confirm that the current stop
   remains visible while Compass searches; the "Ricalcolo rotta" pill must appear below the CNG
   card. The stop is replaced or removed only after a safe CNG-aware route commits. If no safe plan
   exists, the downloaded route and current stop remain unchanged.
5. Reopen an active cached route while the API is unavailable. Confirm route, CNG waypoint, ETA,
   lifecycle and dwell remain available, while opening status and price are not presented as live.

No UIAutomator or screenshot inspection is performed. Screenshots are optional; your visual and
behavioral confirmation is the acceptance evidence. Press ENTER after all checks are complete.
CHECKS
read -r

echo "[5/7] Capturing bounded navigation diagnostics"
capture_diagnostics

echo "[6/7] Verifying CNG guidance state"
grep -q 'cng_guidance station=.*lifecycle=' "$ui_log" || {
    echo "ERROR: no first-class CNG guidance state was recorded." >&2
    exit 1
}
grep -q 'dwell=20 min' "$ui_log" || {
    echo "ERROR: CNG guidance did not retain the configured 20-minute dwell." >&2
    exit 1
}

echo "[7/7] Checking fatal diagnostics"
if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi

echo
echo "NAVIGATION UI PHASE 11 AUTOMATED AND OPERATOR-ASSISTED GATE COMPLETE"
echo "Return this output plus pass/fail notes for the five operator checks."
echo "Artifacts: $navigation_log $ui_log $fatal_log $service_dump"
echo "Do not proceed to Navigation UI Phase 12 until this gate is accepted."
