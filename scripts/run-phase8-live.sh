#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
route_request="$repo_root/scripts/fixtures/phase8-route-request.json"
route_response="/tmp/compass-phase8-route-preflight.json"
launch_output="/tmp/compass-phase8-android-launch.txt"
api_base_url="${COMPASS_API_BASE_URL:-http://127.0.0.1:8000/}"

if [[ "$api_base_url" != */ ]]; then
    api_base_url="${api_base_url}/"
fi

: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation before running this script.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK containing platform 37.0.}"

java_binary="$JAVA_HOME/bin/java"
adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id="org.compass.cng.debug"
activity_component="$application_id/org.compass.cng.MainActivity"

if [[ ! -x "$java_binary" ]]; then
    echo "ERROR: JDK executable not found at $java_binary" >&2
    exit 1
fi
if [[ ! -x "$adb_binary" ]]; then
    echo "ERROR: adb executable not found at $adb_binary" >&2
    exit 1
fi
if [[ ! -x "$android_root/gradlew" ]]; then
    echo "ERROR: Gradle wrapper is missing or not executable." >&2
    exit 1
fi

case "$api_base_url" in
    http://127.0.0.1:8000/)
        use_adb_reverse=true
        ;;
    https://*)
        use_adb_reverse=false
        ;;
    *)
        echo "ERROR: use http://127.0.0.1:8000/ with adb reverse, or an HTTPS API URL." >&2
        echo "Debug cleartext access is intentionally restricted to localhost/emulator addresses." >&2
        exit 1
        ;;
esac

echo "[1/6] Checking backend readiness at ${api_base_url}health/ready"
curl --fail --silent --show-error "${api_base_url}health/ready"
echo

echo "[2/6] Checking the Phase 8 base-route contract"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$route_request" \
    --output "$route_response" \
    "${api_base_url}api/v1/routes"
echo "Route response saved to $route_response"

adb_args=()
if [[ -n "${COMPASS_ADB_SERIAL:-}" ]]; then
    adb_args=(-s "$COMPASS_ADB_SERIAL")
else
    mapfile -t connected_devices < <(
        "$adb_binary" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
    )
    if [[ "${#connected_devices[@]}" -ne 1 ]]; then
        echo "ERROR: expected exactly one authorized Android device; found ${#connected_devices[@]}." >&2
        echo "Set COMPASS_ADB_SERIAL when more than one device is connected." >&2
        "$adb_binary" devices -l >&2
        exit 1
    fi
    adb_args=(-s "${connected_devices[0]}")
fi

echo "[3/6] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
    echo "adb reverse active: device tcp:8000 -> build host tcp:8000"
fi

echo "[4/6] Running unit tests, lint and debug APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon \
        -PCOMPASS_API_BASE_URL="$api_base_url" \
        testDebugUnitTest \
        lintDebug \
        assembleDebug
)

if [[ ! -f "$apk_path" ]]; then
    echo "ERROR: expected APK was not generated at $apk_path" >&2
    exit 1
fi

echo "[5/6] Installing $apk_path"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[6/6] Launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo
echo "AUTOMATED PHASE 8 DEVICE CHECKS COMPLETED"
echo
echo "Manual acceptance still required on the device:"
echo "  1. The map renders a route from Milan to Bologna with two endpoint markers."
echo "  2. Distance and duration are non-zero and provider is Valhalla."
echo "  3. The maneuver list is visible and scrollable."
echo "  4. Rotation/background-resume does not crash the map."
echo
echo "Return this script output plus one screenshot of the rendered route."
