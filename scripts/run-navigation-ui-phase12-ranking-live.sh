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
ui_log=/tmp/compass-navigation-ui-phase12-ranking-ui.txt
fatal_log=/tmp/compass-navigation-ui-phase12-ranking-fatal.txt

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
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassCngCandidates:I' '*:S' \
        >"$ui_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d | \
        grep -E 'FATAL EXCEPTION|AndroidRuntime' >"$fatal_log" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/7] Checking the reachable Compass API"
curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    "${curl_auth[@]}" "$api_base/health/live"

echo "[2/7] Running Phase 12 candidate tests, lint and APK assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base/" \
        testDebugUnitTest lintDebug assembleDebug
)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }

echo "[3/7] Installing Android 0.22.1 without clearing persistent profiles"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[4/7] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR VISUAL CHECK — PHASE 12 CNG CANDIDATE ORDER AND PRICE TIERS

1. Calculate a route and open “Imposta una sosta” with a detour limit that returns at least three
   stations with a displayed price. Confirm cards are ordered only by increasing deviation time:
   for example +0,1 min must always precede +1,2 min and +10 min, regardless of opening or price.
2. Confirm the visible #1, #2, #3… badges follow this new list order. Tapping a card must still only
   highlight its map point; “Scegli” must still be the action that adds it to the route.
3. Among prices shown in this one result set, confirm the cheapest displayed value has a pastel
   green background, the second distinct value pastel yellow, and every later value pastel red.
   Equal displayed prices share the same tier; stations without price show no coloured price box.
4. Check the palette in the active day/night theme: text must remain legible and the three colours
   must look pastel rather than saturated. Selection, route calculation and map highlighting must
   remain unchanged.

No UIAutomator or screenshot inspection is performed. Press ENTER after all four checks.
CHECKS
read -r

echo "[5/7] Capturing bounded diagnostics"
capture_diagnostics

echo "[6/7] Verifying emitted presentation invariants"
grep -q 'candidate_order .*monotonic=true' "$ui_log" || {
    echo "ERROR: no monotonically ordered candidate presentation was recorded." >&2
    exit 1
}
grep -Eq 'price_tiers=.*cheapest:[1-9]' "$ui_log" || {
    echo "ERROR: no cheapest visible-price tier was recorded." >&2
    exit 1
}
grep -Eq 'price_tiers=.*second_cheapest:[1-9]' "$ui_log" || {
    echo "ERROR: choose a result set with at least two distinct displayed prices." >&2
    exit 1
}

echo "[7/7] Checking fatal diagnostics"
if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi

echo
echo "NAVIGATION UI PHASE 12 CANDIDATE-RANKING GATE COMPLETE"
echo "Return this output and pass/fail notes for the four operator checks."
echo "Artifacts on failure: $ui_log $fatal_log"
echo "Do not proceed to Navigation UI Phase 13 until this supplemental gate is accepted."
