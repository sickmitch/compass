#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
service_dump=/tmp/compass-navigation-stage3-service.txt
notification_dump=/tmp/compass-navigation-stage3-notification.txt
logcat_dump=/tmp/compass-navigation-stage3-logcat.txt
navigation_event_dump=/tmp/compass-navigation-stage3-events.txt
launch_output=/tmp/compass-navigation-stage3-launch.txt
resume_output=/tmp/compass-navigation-stage3-resume.txt

: >"$service_dump"
: >"$notification_dump"
: >"$logcat_dump"
: >"$navigation_event_dump"

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

echo "[1/12] Checking backend readiness"
curl --fail --silent --show-error "${api_base_url}health/ready"
echo

adb_args=()
if [[ -n "${COMPASS_ADB_SERIAL:-}" ]]; then
    adb_args=(-s "$COMPASS_ADB_SERIAL")
else
    mapfile -t devices < <("$adb_binary" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [[ "${#devices[@]}" -eq 1 ]] || {
        echo "ERROR: expected one authorized Android device; found ${#devices[@]}." >&2
        echo "Set COMPASS_ADB_SERIAL when multiple devices are connected." >&2
        exit 1
    }
    adb_args=(-s "${devices[0]}")
fi

restore_device_network() {
    if [[ "$use_adb_reverse" == true ]]; then
        "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000 >/dev/null 2>&1 || true
    fi
}
trap restore_device_network EXIT

capture_navigation_events() {
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief \
        -s 'CompassNavigation:I' '*:S' >"$navigation_event_dump"
}

wait_for_navigation_event() {
    local pattern="$1"
    local attempt=0
    while [[ "$attempt" -lt 20 ]]; do
        capture_navigation_events
        if grep -q "$pattern" "$navigation_event_dump"; then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    return 1
}

echo "[2/12] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
    echo "adb reverse active: device tcp:8000 -> build host tcp:8000"
fi

echo "[3/12] Running navigation tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/12] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION A — GUIDANCE, CAMERA AND AUTOMATIC REROUTING"
echo
echo "On the Android device:"
echo "  1. Wait until the Milan-to-Bologna route preview appears."
echo "  2. Tap 'Avvia navigazione', then tap 'Riproduci percorso demo'."
echo "  3. Grant precise location, notification and TextToSpeech-related prompts if shown."
echo "  4. Confirm that Italian voice guidance is audible and that the same phrase is not"
echo "     repeated continuously. Take screenshot A while the follow map, snapped puck,"
echo "     maneuver banner, progress panel and 'Voce:' line are visible."
echo "  5. Tap 'Panoramica'. Confirm that the whole remaining route is framed and take"
echo "     screenshot B. Tap 'Ricentra' and confirm bearing-up follow resumes."
echo "  6. Tap 'Simula deviazione (debug)'. This injects three controlled fixes through"
echo "     the real filter/matcher; it does not bypass the off-route state machine."
echo "  7. Wait until rerouting finishes and navigation remains active on the replacement"
echo "     route. Do not tap 'Termina navigazione'."
echo
read -r -p "When all seven actions are complete, press ENTER here: "

echo "[5/12] Verifying confirmed off-route rerouting through Compass"
wait_for_navigation_event 'route update committed: OFF_ROUTE' || {
    capture_navigation_events
    echo "ERROR: the controlled deviation did not trigger off-route rerouting." >&2
    echo "Compass navigation events:" >&2
    sed -n '1,160p' "$navigation_event_dump" >&2
    exit 1
}
grep -q 'route update started: OFF_ROUTE' "$navigation_event_dump" || {
    echo "ERROR: Compass committed an off-route update without its start event." >&2
    sed -n '1,160p' "$navigation_event_dump" >&2
    exit 1
}

echo "[6/12] Verifying the foreground service before the network-loss check"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: NavigationForegroundService is not active." >&2
    exit 1
}

echo "[7/12] Temporarily making Compass unreachable from the device"
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse --remove tcp:8000
    echo "adb reverse removed; only the device-to-Compass connection is interrupted."
else
    echo "HTTPS is configured. Temporarily disable device connectivity before continuing."
fi

echo
echo "DEVICE ACTION B — DOWNLOADED-ROUTE FALLBACK"
echo
echo "On the Android device:"
echo "  1. Tap 'Ricalcola percorso (debug)'."
echo "  2. Wait for 'Ricalcolo non disponibile: continuo sulla rotta scaricata'."
echo "  3. Verify that the existing route, maneuver, local progress and Termina button"
echo "     remain available. Take screenshot C of this fallback state."
echo
read -r -p "When the fallback state is visible, press ENTER here: "

echo "[8/12] Verifying graceful route-update failure and restoring connectivity"
wait_for_navigation_event 'route update failed: MANUAL_DEBUG' || {
    capture_navigation_events
    echo "ERROR: the expected offline route-update failure was not recorded." >&2
    echo "Compass navigation events:" >&2
    sed -n '1,160p' "$navigation_event_dump" >&2
    exit 1
}
restore_device_network

echo "[9/12] Backgrounding Compass while leaving guidance active"
"$adb_binary" "${adb_args[@]}" shell input keyevent KEYCODE_HOME
sleep 3
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump"
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
    >"$notification_dump"
grep -q "$application_id" "$notification_dump" || {
    echo "ERROR: Compass foreground navigation notification was not found." >&2
    exit 1
}

echo "Take screenshot D of the Compass foreground navigation notification, then press ENTER."
read -r

echo "[10/12] Resuming the Activity without restarting navigation"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$resume_output"
grep -q '^Status: ok$' "$resume_output"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump"

echo "[11/12] Checking the process for fatal navigation exceptions"
sleep 2
compass_pid="$("$adb_binary" "${adb_args[@]}" shell pidof -s "$application_id" | tr -d '\r')"
[[ -n "$compass_pid" ]] || {
    echo "ERROR: the Compass process is no longer running." >&2
    exit 1
}
"$adb_binary" "${adb_args[@]}" logcat -d --pid="$compass_pid" >"$logcat_dump"
if grep -E 'FATAL EXCEPTION|SecurityException' "$logcat_dump" >/dev/null; then
    echo "ERROR: fatal Compass navigation exception detected." >&2
    exit 1
fi

echo "[12/12] Stage 3 automated and operator-assisted checks complete"
echo
echo "AUTOMATED NAVIGATION STAGE 3 DEVICE CHECKS COMPLETED"
echo
echo "MANUAL ACCEPTANCE STILL REQUIRED"
echo "Confirm in your reply that voice guidance, Panoramica/Ricentra, automatic rerouting"
echo "and downloaded-route fallback behaved as described. Then tap 'Termina navigazione'"
echo "and verify that the foreground notification disappears."
echo
echo "Return the COMPLETE script output and four screenshots:"
echo "  A. active follow navigation with voice/maneuver/progress evidence;"
echo "  B. route overview mode;"
echo "  C. failed refresh while the downloaded route remains usable;"
echo "  D. foreground navigation notification while Compass is backgrounded."
echo
echo "If the gate fails, return these bounded diagnostics:"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $logcat_dump"
echo "  $navigation_event_dump"
echo "  $adb_binary ${adb_args[*]} logcat -d -v brief -s 'CompassNavigation:I' '*:S'"
