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
installable_apk="$repo_root/dist/Compass-0.26.5-debug-installabile.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"
service_class=org.compass.cng.navigation.NavigationForegroundService
artifact_prefix=/tmp/compass-navigation-ui-phase16
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
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief \
        -s 'CompassUi:I' 'CompassFollowUi:I' '*:S' \
        >"$ui_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d | \
        grep -E 'FATAL EXCEPTION|AndroidRuntime' >"$fatal_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/6] Checking the reachable Compass API"
curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    "${curl_auth[@]}" "$api_base/health/live"

echo "[2/6] Running Phase 16 tests, lint and Android 0.26.5 assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base/" \
        testDebugUnitTest lintDebug assembleDebug
)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }
mkdir -p "$(dirname -- "$installable_apk")"
cp "$apk_path" "$installable_apk"

echo "[3/6] Installing without clearing persistent server or vehicle profiles"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[4/6] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR CHECK A — PORTRAIT, TYPOGRAPHY AND TOUCH TARGETS

Cold-launch without a route and leave both GPS and network location providers enabled. Compass must
replace “Ricerca del segnale GPS…” as soon as either provider supplies a fix, zoom from the Italy
overview to the device position and remain in follow mode. A later GPS-quality fix may refine the
position without jumping back to overview.

In portrait, walk through endpoint selection, Google search, map-point selection, personalization,
ordinary stops, CNG selection/planning and the common summary. Confirm the hierarchy is readable in
under one second, controls share the same Material shapes and icon language, and values never
collide or clip. Try the device's larger text setting if practical: content may scroll, but primary
actions, field labels and 48/56 dp touch targets must remain usable. Restore your preferred setting.

On Personalizza viaggio, Cambia percorso and Aggiungi tappe must form the second two-column row,
matching Sosta CNG and Piano CNG above it. Enter Aggiungi tappe and immediately go back without
adding anything: no phantom stop or warning may appear, and all four planning actions must remain
available.

Open Selezione dalla mappa for both an endpoint and a new ordinary stop. Each picker must initially
frame the latest acquired GPS position. If no GPS or selected endpoint exists, Compass must ask for
a real position instead of opening on an arbitrary city. While confirming a mapped ordinary stop,
the Scegli button must show a spinner and “Ricalcolo percorso” until the preview is ready.

On Crea viaggio, Partenza and Destinazione must expose the four choices in the identical order:
Posizione attuale, Posizioni preferite, Ricerca, Selezione dalla mappa.

With the device near Verona, search a generic local category such as “tabaccheria” or “farmacia”.
Suggestions and line-of-air distances must use the available GPS context rather than an unrelated
city. For an ordinary stop, the maximum-deviation rule applies only after selecting and resolving a
searched business/POI. A manual map point or a specific address such as “Via Cappafredda, 12,
Roverchiara” remains selectable even when its route addition exceeds that threshold.

Press ENTER when complete.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK B — LANDSCAPE AND CUTOUTS

Rotate to landscape and repeat the creation path. Partenza/Destinazione must share the available
width. Personalization, point confirmation and ordinary-stop preview must show MapLibre beside a
scrollable action panel. CNG selection must keep the candidate map and list visible side by side;
tapping a card still highlights only its marker and Scegli still commits it. The compact header,
system bars and any display cutout must not obscure content. Rotate back and confirm state is kept.

Press ENTER when complete.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK C — AUTOMOTIVE NAVIGATION CHROME

Start demo guidance and inspect both orientations. The maneuver card must remain glance-readable
without consuming the map, while trip/recalculation cards have bounded centred widths. In reduced
height, Viaggio and Ricentra become clear icon controls so the row cannot overflow. Puck, route,
voice, overview, trip details, stop arrival and guarded termination behavior must remain unchanged.
Confirm the map stays dominant and no overlay covers the puck at normal and aggressive zoom.

Switch light/dark system theme once. Non-map surfaces and the puck must retain contrast without a
flash of the wrong theme. In dark mode, the application canvas must be true OLED black rather than
green-tinted; elevated cards remain distinguishable through neutral dark Material containers. End
navigation from the guarded control, then press ENTER.

No automated visual or screenshot inspection is performed; your report is the acceptance evidence.

CHECKS
read -r

echo "[5/6] Capturing bounded diagnostics"
capture_diagnostics

if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: navigation foreground service remained active after operator termination." >&2
    exit 1
fi

echo "[6/6] Navigation UI Phase 16 automated and operator-assisted checks complete"
echo
echo "NAVIGATION UI PHASE 16 LIVE GATE COMPLETE"
echo "Return this complete output and pass/fail notes for operator checks A-C."
echo "Installable APK: $installable_apk"
sha256sum "$installable_apk"
echo "Artifacts: $ui_log $fatal_log $service_dump"
echo "Do not proceed to Navigation UI Phase 17 until this gate is accepted."
