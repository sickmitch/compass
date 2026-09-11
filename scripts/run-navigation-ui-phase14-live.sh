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
artifact_prefix=/tmp/compass-navigation-ui-phase14
ui_log="$artifact_prefix-ui.txt"
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
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassNavigationUi:I' '*:S' \
        >"$ui_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassNavigation:I' '*:S' \
        >"$navigation_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d | \
        grep -E 'FATAL EXCEPTION|AndroidRuntime' >"$fatal_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/7] Checking the reachable Compass API"
curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    "${curl_auth[@]}" "$api_base/health/live"

echo "[2/7] Running Phase 14 tests, lint and Android 0.24.0 assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base/" \
        testDebugUnitTest lintDebug assembleDebug
)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }

echo "[3/7] Installing without clearing persistent server or vehicle profiles"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[4/7] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR CHECK A — ORIENTATION, OVERVIEW AND RECENTER

Create a route and start “Riproduci percorso demo”. In the default mode confirm that the puck stays
on the lower driving anchor and the map follows the direction of travel. Tap the orientation arrow:
it becomes “N”, the map turns north-up with zero tilt, the puck rotates with the route and progress
continues. Tap “N” again and confirm heading-up tracking returns.

Pinch to change zoom while tracking: the puck must continue moving and remain anchored. Pan the map,
then press “Ricentra” and confirm immediate heading-up follow. Open route overview and confirm the
whole remaining route—not the travelled portion—is framed north-up. Press “Ricentra” again.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK B — COMPACT DRIVING CONTROLS

Confirm that Viaggio, orientation, overview, voice and stop controls remain legible without covering
the puck or maneuver card. Toggle Voce OFF and ON; speech must stop and resume while visual guidance
continues. Open and close Viaggio once and confirm the camera still keeps the puck above the panel.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK C — GUARDED TERMINATION

Tap the red stop control. Confirm that the dialog explains that navigation will end. Choose
“Continua” once: route, replay and foreground service must remain active. Open it again and choose
“Termina”: Compass must return to route-free GPS follow, remove the active route and stop the
navigation foreground service. Then press ENTER.

CHECKS
read -r

echo "[5/7] Capturing bounded diagnostics"
capture_diagnostics

echo "[6/7] Verifying interaction transitions"
grep -q 'camera_mode=north_up reason=orientation_control' "$ui_log" || {
    echo "ERROR: north-up tracking was not recorded." >&2
    exit 1
}
grep -q 'camera_mode=follow reason=orientation_control' "$ui_log" || {
    echo "ERROR: heading-up tracking was not restored by the orientation control." >&2
    exit 1
}
grep -q 'camera_mode=overview reason=control' "$ui_log" || {
    echo "ERROR: remaining-route overview was not opened." >&2
    exit 1
}
grep -q 'camera_mode=follow reason=recenter' "$ui_log" || {
    echo "ERROR: recenter was not used to restore heading-up tracking." >&2
    exit 1
}
grep -q 'navigation_stop confirmation_visible=true source=map_control' "$ui_log" || {
    echo "ERROR: guarded map termination was not opened." >&2
    exit 1
}
grep -q 'navigation_stop confirmed=false navigation_active=true' "$ui_log" || {
    echo "ERROR: cancellation of guarded termination was not recorded." >&2
    exit 1
}
grep -q 'navigation_stop confirmed=true source=map_control' "$ui_log" || {
    echo "ERROR: navigation termination was not confirmed." >&2
    exit 1
}
grep -q 'voice guidance enabled=false' "$navigation_log" || {
    echo "ERROR: voice OFF transition was not recorded." >&2
    exit 1
}
grep -q 'voice guidance enabled=true' "$navigation_log" || {
    echo "ERROR: voice ON transition was not recorded." >&2
    exit 1
}
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: navigation foreground service remained active after confirmed termination." >&2
    exit 1
fi

echo "[7/7] Checking fatal diagnostics"
if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi

echo
echo "NAVIGATION UI PHASE 14 AUTOMATED AND OPERATOR-ASSISTED GATE COMPLETE"
echo "Return this complete output and pass/fail notes for operator checks A-C."
echo "Artifacts: $ui_log $navigation_log $fatal_log $service_dump"
echo "Do not proceed to Navigation UI Phase 15 until this gate is accepted."
