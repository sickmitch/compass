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
installable_apk="$repo_root/dist/Compass-0.25.3-debug-installabile.apk"
application_id=org.compass.cng.debug
activity_component="$application_id/org.compass.cng.MainActivity"
service_class=org.compass.cng.navigation.NavigationForegroundService
artifact_prefix=/tmp/compass-navigation-ui-phase15
api_log="$artifact_prefix-api.txt"
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
    "$adb_binary" "${adb_args[@]}" logcat -d -v brief -s 'CompassNavigation:I' '*:S' \
        >"$navigation_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" logcat -d | \
        grep -E 'FATAL EXCEPTION|AndroidRuntime' >"$fatal_log" 2>/dev/null || true
    "$adb_binary" "${adb_args[@]}" shell dumpsys activity services "$application_id" \
        >"$service_dump" 2>/dev/null || true
}
trap capture_diagnostics EXIT

echo "[1/8] Checking the reachable Compass API"
curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    "${curl_auth[@]}" "$api_base/health/live"

echo "[2/8] Checking the live ordinary-waypoint route contract"
curl --fail --silent --show-error --connect-timeout 5 --max-time 90 \
    "${curl_auth[@]}" \
    --header 'Content-Type: application/json' \
    --data '{"origin":{"latitude":45.4642,"longitude":9.19},"intermediate_stops":[{"latitude":45.5416,"longitude":10.2118},{"latitude":45.4384,"longitude":10.9916}],"destination":{"latitude":45.0703,"longitude":7.6869},"costing":"auto","language":"it-IT"}' \
    "$api_base/api/v1/routes/with-intermediate-stops" >"$api_log"
python3 - "$api_log" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    body = json.load(handle)
assert [leg["kind"] for leg in body["legs"]] == [
    "origin_to_intermediate_stop",
    "intermediate_stop_to_intermediate_stop",
    "intermediate_stop_to_destination",
]
assert len(body["intermediate_stops"]) == 2
assert body["navigation"]["refueling_stop_count"] == 0
assert body["navigation"]["total_refueling_dwell_seconds"] == 0
assert body["navigation"]["total_trip_duration_seconds"] == body["duration_seconds"]
print(json.dumps({
    "provider": body["provider"],
    "legs": len(body["legs"]),
    "distance_meters": round(body["distance_meters"]),
    "duration_seconds": round(body["duration_seconds"]),
    "dwell_seconds": body["navigation"]["total_refueling_dwell_seconds"],
}, separators=(",", ":")))
PY

echo "[3/8] Running Phase 15 tests, lint and Android 0.25.3 assembly"
(
    cd "$android_root"
    ./gradlew --no-daemon -PCOMPASS_API_BASE_URL="$api_base/" \
        testDebugUnitTest lintDebug assembleDebug
)
[[ -f "$apk_path" ]] || { echo "ERROR: APK not generated at $apk_path" >&2; exit 1; }
mkdir -p "$(dirname -- "$installable_apk")"
cp "$apk_path" "$installable_apk"

echo "[4/8] Installing without clearing persistent server or vehicle profiles"
"$adb_binary" "${adb_args[@]}" install -r "$apk_path"

echo "[5/8] Cold-launching Compass"
"$adb_binary" "${adb_args[@]}" shell am force-stop "$application_id"
"$adb_binary" "${adb_args[@]}" logcat -c
launch_output="$("$adb_binary" "${adb_args[@]}" shell am start -W -n "$activity_component")"
printf '%s\n' "$launch_output"
grep -q '^Status: ok$' <<<"$launch_output"

cat <<'CHECKS'

OPERATOR CHECK A — COHERENT ROUTE-CREATION FLOW

From route-free follow, tap “Crea viaggio”. Confirm Partenza and Destinazione each offer Posizione
attuale, Posizioni preferite (disabled placeholder), Ricerca and Selezione dalla mappa. Calculate a
route. It must open the animated Personalizza viaggio surface with the map first, the route facts,
side-by-side Cambia percorso / Aggiungi tappe, side-by-side Sosta CNG / Piano CNG and a compact
centred Percorso diretto action. Screens must replace each other instead of accumulating panels.

Press Back from the overview and confirm it returns to the relevant planning choice, without an
extra “selected route” confirmation screen.

Press ENTER when complete.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK E — UNIFIED MATERIAL 3 PRESENTATION

Repeat the creation flow in light and dark system themes. Confirm that every non-map surface uses
the same Material 3 typography, tonal colors, rounded shapes and control hierarchy. “Crea viaggio”
must keep the accepted behavior while showing icon-led Partenza/Destinazione sections, equal-width
two-column choices, visible selection/acquisition states and one persistent primary route action.

Briefly inspect search, map-point confirmation, personalization, CNG selection, full CNG planning,
the common summary, active-navigation overlays and Dettagli: controls remain at least 48 dp high,
text is not clipped and no old isolated color/style remains. On the navigation map, the circular
direction puck must use its day/night treatment and remain legible, correctly rotated, anchored and
scaled down by no more than the established 50% minimum when zooming out.

This is a presentation-only increment: confirm screen order, callbacks, stored profiles, route
calculation and navigation behavior are unchanged. Press ENTER when complete.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK B — MAP-BASED, ORDERED ORDINARY WAYPOINTS

Choose Aggiungi tappe. Its map/list panel must offer the same acquisition methods and default the
maximum total deviation to 30% of the direct-route length. Add two points, at least one through
Ricerca: Google results/addresses remain on the mapless selector; tapping a result resolves only
that result and opens an exact-route map preview with a highlighted neutral point; only Scegli adds
it to the ordered list. Long-press and drag to reverse the two stops, calculate, and verify the map
and common summary preserve that order and add zero dwell. A very small custom limit must reject an
excessive exact road-distance deviation.

Press ENTER when complete.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK C — CNG CHOICE WITHOUT REDUNDANT SCREENS

Choose “Sosta CNG”. The candidate map/list must keep the established interaction: tapping a card
only highlights the corresponding map point; “Scegli” commits it. After route calculation Compass
must go directly to the final route overview. Repeat with “Piano CNG”: after confirming the proposed
plan, the vehicle controls must stay on the same screen: Carica o crea profilo replaces the lower
panel with the saved-profile list; Aggiungi veicolo replaces it with the form; saving returns to the
updated list. Selecting a vehicle returns to the range inputs, where residual autonomy and maximum
deviation are still required. The completed plan goes to the same common overview.

Press ENTER when complete.

CHECKS
read -r

cat <<'CHECKS'

OPERATOR CHECK D — COMMON SUMMARY EDITOR AND WAYPOINT LIFECYCLE

In the common summary confirm destination, distance, driving/total time, CNG stop count/dwell,
traffic and warnings are visible without route IDs or cache internals. Modifica must expand inline:
endpoints and each ordinary/CNG stop are selectable, with Modifica/Elimina only for the selected
row. Delete one stop and confirm Compass recalculates then returns to the updated summary.

Start demo guidance on an accepted ordinary-waypoint route. At “Tappa intermedia”, route guidance
and replay pause with no dwell; explicit completion resumes the second leg and recomputes ETA. If a
safe real GPS pass is practical, stationary noise must not complete the visit and moving away must
resume after the established confirmation fixes. Also confirm the zoom-dependent puck lower bound,
voice/traffic/offline guidance and guarded navigation termination remain unchanged.

No automated visual inspection is performed; your report is the acceptance evidence.
Press ENTER when complete.

CHECKS
read -r

echo "[6/8] Capturing bounded diagnostics"
capture_diagnostics

echo "[7/8] Checking fatal diagnostics and clean teardown"
if [[ -s "$fatal_log" ]]; then
    echo "ERROR: fatal Android diagnostics detected." >&2
    cat "$fatal_log" >&2
    exit 1
fi
if grep -q "$service_class" "$service_dump"; then
    echo "ERROR: navigation foreground service remained active after operator termination." >&2
    exit 1
fi

echo "[8/8] Navigation UI Phase 15 automated and operator-assisted checks complete"
echo
echo "NAVIGATION UI PHASE 15 LIVE GATE COMPLETE"
echo "Return this complete output and pass/fail notes for operator checks A-E."
echo "Installable APK: $installable_apk"
sha256sum "$installable_apk"
echo "Artifacts: $navigation_log $api_log $fatal_log $service_dump"
echo "Do not proceed to Navigation UI Phase 16 until this gate is accepted."
