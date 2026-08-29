#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"
validator="$repo_root/scripts/validate-phase9-live.py"
ranked_request="/tmp/compass-phase9-ranked-request.json"
ranked_response="/tmp/compass-phase9-ranked-response.json"
selected_request="/tmp/compass-phase9-selected-request.json"
selected_response="/tmp/compass-phase9-selected-response.json"
launch_output="/tmp/compass-phase9-android-launch.txt"
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
if [[ ! -f "$validator" ]]; then
    echo "ERROR: Phase 9 validator is missing at $validator" >&2
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
        exit 1
        ;;
esac

echo "[1/8] Checking backend readiness at ${api_base_url}health/ready"
curl --fail --silent --show-error "${api_base_url}health/ready"
echo

echo "[2/8] Checking live ranked CNG candidates"
python3 "$validator" prepare-ranked --output "$ranked_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$ranked_request" \
    --output "$ranked_response" \
    "${api_base_url}api/v1/cng/ranked-candidates"
echo "Ranked response saved to $ranked_response"

echo "[3/8] Checking selected-stop route recomputation"
python3 "$validator" prepare-selected \
    --ranked "$ranked_response" \
    --output "$selected_request"
curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$selected_request" \
    --output "$selected_response" \
    "${api_base_url}api/v1/routes/with-cng-stop"
python3 "$validator" validate \
    --ranked "$ranked_response" \
    --selected-route "$selected_response"

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

echo "[4/8] Preparing device connectivity"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
if [[ "$use_adb_reverse" == true ]]; then
    "$adb_binary" "${adb_args[@]}" reverse tcp:8000 tcp:8000
    echo "adb reverse active: device tcp:8000 -> build host tcp:8000"
fi

echo "[5/8] Running unit tests, lint and debug APK assembly"
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

echo "[6/8] Installing $apk_path"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[7/8] Launching Compass"
"$adb_binary" "${adb_args[@]}" logcat -c
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component" | tee "$launch_output"
grep -q '^Status: ok$' "$launch_output"

echo "[8/8] Checking the launched process for an immediate fatal exception"
sleep 2
if "$adb_binary" "${adb_args[@]}" logcat -d -t 300 \
    | grep -E 'FATAL EXCEPTION.*org\.compass\.cng|Process: org\.compass\.cng\.debug' >/dev/null; then
    echo "ERROR: a fatal Compass exception was found after launch." >&2
    "$adb_binary" "${adb_args[@]}" logcat -d -t 300 >&2
    exit 1
fi

echo
echo "AUTOMATED PHASE 9 DEVICE CHECKS COMPLETED"
echo
echo "Manual acceptance on the device:"
echo "  1. Tap 'Aggiungi tappa'; the Metano form shows detour and effective-range inputs."
echo "  2. Tap 'Cerca stazioni Metano'; route markers and a ranked station list appear."
echo "  3. A card shows detour, road distance, ETA, opening state/hours, price freshness and score."
echo "  4. Select a station; the map shows the CNG marker and a two-section maneuver list."
echo "  5. 'Cambia stazione', 'Rimuovi tappa', rotation and background/resume do not crash."
echo
echo "Return this complete output plus screenshots of the candidate list and selected-stop route."
