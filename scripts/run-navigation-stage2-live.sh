#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
service_dump=/tmp/compass-navigation-stage2-service.txt
notification_dump=/tmp/compass-navigation-stage2-notification.txt
launch_output=/tmp/compass-navigation-stage2-launch.txt
resume_output=/tmp/compass-navigation-stage2-resume.txt

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

echo "[1/9] Checking backend readiness"
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

echo "[2/9] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
    echo "adb reverse active: device tcp:8000 -> build host tcp:8000"
fi

echo "[3/9] Running navigation replay tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[4/9] Installing and cold-launching Compass"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "DEVICE ACTION REQUIRED"
echo "  1. Wait for the Milan-to-Bologna preview."
echo "  2. Tap 'Avvia navigazione'."
echo "  3. Take screenshot A of the navigation-ready page with both start controls."
echo "  4. Tap 'Riproduci percorso demo' (this control exists only in the debug APK)."
echo "  5. Grant precise location and notification permission when Android asks."
echo "  6. During replay, take screenshot B while the puck is moving and the route is split"
echo "     into grey travelled and green remaining portions."
echo
read -r -p "When those six steps are complete, press ENTER here to continue: "

echo "[5/9] Verifying the foreground location service"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump" || {
    echo "ERROR: NavigationForegroundService is not active." >&2
    cat "$service_dump" >&2
    exit 1
}
"$adb_binary" "${adb_args[@]}" shell dumpsys notification --noredact \
    >"$notification_dump"
grep -q "$application_id" "$notification_dump" || {
    echo "ERROR: Compass foreground notification was not found." >&2
    exit 1
}

echo "[6/9] Backgrounding Compass while leaving navigation active"
"$adb_binary" "${adb_args[@]}" shell input keyevent KEYCODE_HOME
sleep 3
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump"

echo "[7/9] Resuming the Activity without restarting navigation"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$resume_output"
grep -q '^Status: ok$' "$resume_output"
"$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
    >"$service_dump"
grep -q "$service_class" "$service_dump"

echo "[8/9] Checking the process for a fatal exception"
sleep 2
if "$adb_binary" "${adb_args[@]}" logcat -d -t 500 \
    | grep -E 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' >/dev/null; then
    echo "ERROR: fatal Compass exception detected." >&2
    "$adb_binary" "${adb_args[@]}" logcat -d -t 500 >&2
    exit 1
fi

echo "[9/9] Foreground service and lifecycle checks complete"
echo
echo "AUTOMATED NAVIGATION STAGE 2 DEVICE CHECKS COMPLETED"
echo
echo "MANUAL ACCEPTANCE"
echo "  Verify that the active screen shows a maneuver banner, remaining distance/time,"
echo "  ETA, progress and a pitched follow map. The teal puck must remain on the route line."
echo "  The debug replay uses only the route already downloaded; it sends no location fixes"
echo "  to Compass and performs no routing request for each replay point."
echo "  Rotate once and verify that navigation and the foreground notification remain active."
echo "  Finally tap 'Termina navigazione' and verify that the notification disappears."
echo
echo "Return the COMPLETE output and three screenshots:"
echo "  A. navigation-ready page with both start controls;"
echo "  B. active replay with puck, split route, maneuver banner and bottom progress panel;"
echo "  C. Android foreground navigation notification after background/resume."
echo
echo "If the gate fails, also return these saved/bounded diagnostics:"
echo "  $service_dump"
echo "  $notification_dump"
echo "  $adb_binary ${adb_args[*]} logcat -d -t 500 | grep -E 'NavigationForegroundService|FATAL EXCEPTION|SecurityException'"
