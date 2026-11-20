#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_root="$repo_root/android"

: "${JAVA_HOME:?Set JAVA_HOME to the JDK 17 installation.}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK.}"

adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
apk_path="$android_root/app/build/outputs/apk/debug/app-debug.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"

[[ -x "$JAVA_HOME/bin/java" ]] || { echo "ERROR: JDK executable not found." >&2; exit 1; }
[[ -x "$adb_binary" ]] || { echo "ERROR: adb not found." >&2; exit 1; }

adb_args=()
if [[ -n "${COMPASS_ADB_SERIAL:-}" ]]; then
    adb_args=(-s "$COMPASS_ADB_SERIAL")
else
    mapfile -t devices < <("$adb_binary" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ "${#devices[@]}" -ne 1 ]]; then
        echo "ERROR: expected one authorized Android device; found ${#devices[@]}." >&2
        "$adb_binary" devices -l >&2
        exit 1
    fi
    adb_args=(-s "${devices[0]}")
fi

gradle_properties=()
if [[ -n "${COMPASS_API_BASE_URL:-}" ]]; then
    api_url="${COMPASS_API_BASE_URL%/}/"
    gradle_properties+=(-PCOMPASS_API_BASE_URL="$api_url")
fi

echo "[1/4] Checking the selected Android device"
"$adb_binary" "${adb_args[@]}" get-state >/dev/null
"$adb_binary" "${adb_args[@]}" shell getprop ro.product.model

echo "[2/4] Building Android 0.21.0"
(cd "$android_root" && ./gradlew --no-daemon "${gradle_properties[@]}" assembleDebug)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }

echo "[3/4] Installing without clearing persistent Compass data"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[4/4] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

echo
echo "ANDROID 0.21.0 INSTALLED"
echo "Saved server and vehicle profiles were preserved. No UIAutomator or screenshot check ran."
