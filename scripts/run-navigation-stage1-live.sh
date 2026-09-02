#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
validator="$repo_root/scripts/validate-navigation-stage1-live.py"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"
base_request=/tmp/compass-navigation-stage1-base-request.json
base_response=/tmp/compass-navigation-stage1-base-response.json
ranked_request=/tmp/compass-navigation-stage1-ranked-request.json
ranked_response=/tmp/compass-navigation-stage1-ranked-response.json
selected_request=/tmp/compass-navigation-stage1-selected-request.json
selected_response=/tmp/compass-navigation-stage1-selected-response.json
openapi_response=/tmp/compass-navigation-stage1-openapi.json
launch_output=/tmp/compass-navigation-stage1-android-launch.txt

[[ "$api_base_url" == */ ]] || api_base_url="${api_base_url}/"
: "${JAVA_HOME:?Set JAVA_HOME to the JDK 17 installation.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"

adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"

[[ -x "$JAVA_HOME/bin/java" ]] || { echo "ERROR: JDK not found in JAVA_HOME." >&2; exit 1; }
[[ -x "$adb_binary" ]] || { echo "ERROR: adb not found in ANDROID_SDK_ROOT." >&2; exit 1; }
[[ -x "$android_root/gradlew" ]] || { echo "ERROR: Gradle wrapper is unavailable." >&2; exit 1; }

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

echo "[1/10] Checking backend readiness"
curl --fail --silent --show-error "${api_base_url}health/ready"
echo

echo "[2/10] Preparing deterministic navigation requests"
python3 "$validator" prepare --base "$base_request" --ranked "$ranked_request"

echo "[3/10] Checking the base NavigationRoute contract"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$base_request" \
    --output "$base_response" \
    "${api_base_url}api/v1/routes"

echo "[4/10] Selecting one live CNG station and recalculating the route"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$ranked_request" \
    --output "$ranked_response" \
    "${api_base_url}api/v1/cng/ranked-candidates"
python3 "$validator" prepare-selected --ranked "$ranked_response" --output "$selected_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$selected_request" \
    --output "$selected_response" \
    "${api_base_url}api/v1/routes/with-cng-stop"

echo "[5/10] Validating route identity, maneuver indexes and 20-minute dwell"
curl --fail --silent --show-error --output "$openapi_response" "${api_base_url}openapi.json"
python3 "$validator" validate \
    --base "$base_response" \
    --selected "$selected_response" \
    --openapi "$openapi_response"

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

echo "[6/10] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
    echo "adb reverse active: device tcp:8000 -> build host tcp:8000"
fi

echo "[7/10] Running Android tests, lint and APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest lintDebug assembleDebug
)

echo "[8/10] Installing the debug APK"
[[ -f "$apk_path" ]] || { echo "ERROR: APK not found at $apk_path" >&2; exit 1; }
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[9/10] Launching Compass"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo "[10/10] Checking for an immediate fatal exception"
sleep 2
if "$adb_binary" "${adb_args[@]}" logcat -d -t 300 \
    | grep -E 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' >/dev/null; then
    echo "ERROR: fatal Compass exception detected." >&2
    "$adb_binary" "${adb_args[@]}" logcat -d -t 300 >&2
    exit 1
fi

echo
echo "AUTOMATED NAVIGATION STAGE 1 CHECKS COMPLETED"
echo
echo "MANUAL DEVICE ACCEPTANCE"
echo
echo "Scenario A — route without a CNG stop"
echo "  On the initial route preview, verify that 'Avvia navigazione' is visible."
echo "  Tap it. The next screen must say 'Navigazione pronta', retain the complete route"
echo "  on the map, show driving time and total time as equal, and show the first maneuver."
echo
echo "Scenario B — route with one CNG stop"
echo "  Go back, choose 'Aggiungi tappa', then 'Metano'. Use range 300 and deviation 10."
echo "  Select any returned station and tap 'Avvia navigazione'."
echo "  The navigation-ready screen must show the station marker/name, one CNG stop,"
echo "  20 minutes of refuelling, and a total trip time exactly 20 minutes longer than"
echo "  the displayed driving time. The route must still run from origin to destination."
echo
echo "Lifecycle check"
echo "  Rotate once on the navigation-ready screen, background the app, then resume it."
echo "  The route and CNG stop must remain visible and the app must not crash."
echo
echo "Return the COMPLETE script output and these two screenshots:"
echo "  1. Scenario A navigation-ready screen with map and timing card visible."
echo "  2. Scenario B navigation-ready screen with CNG stop and +20-minute timing visible."
