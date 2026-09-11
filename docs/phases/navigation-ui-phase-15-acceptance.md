# Navigation UI upgrade — Phase 15 acceptance record

Status: complete; physical-device/live gate accepted by the operator on 2026-09-11.

## Implemented scope

Phase 15 now follows one stable creation flow instead of unrelated forms:

```text
Crea viaggio
  → Personalizza viaggio
      → Percorso diretto
      → Sosta CNG
      → Piano CNG
      → Tappe ordinarie
  → Riepilogo comune
  → Avvia navigazione
```

- Partenza and destination expose current position, the disabled favourites placeholder, Google
  search and direct selection on a MapLibre map.
- `Calcola percorso` immediately opens route personalization. There is no second continue action.
- The personalization map is followed by distance, duration and traffic. Single CNG stop and full
  CNG planning are side by side; ordinary stops are a separate full-width choice; the direct-route
  action is compact and centred.
- These planning modes are intentionally alternative. Compass does not combine an ordinary stop
  with a CNG plan until the backend fuel-reachability planner can validate both stop classes in one
  authoritative itinerary.
- Ordinary stops are plural (one to eight), preserve user order and add no dwell. Their list is
  reordered by long-press drag. The default maximum total added road distance is 30% of the direct
  route length and remains editable.
- `POST /api/v1/routes/with-intermediate-stops` sends all ordered coordinates to the existing
  Valhalla waypoint route path and returns one sequenced leg more than the stop count. Traffic
  refresh and navigation timing are retained; CNG stop count and dwell remain zero.
- Each ordinary stop can use current GPS position, future favourites, Google search or map
  selection. A Google result is resolved once, then shown as an exact highlighted point on a route
  preview; `Scegli` is the commit action.
- Google results and textual addresses remain on the mapless search surface. Tap-list maps and the
  final route map receive only WGS84 coordinates and neutral Compass labels.
- CNG station selection retains its map/list interaction: tapping a station card highlights its
  point and `Scegli` commits it.
- Full CNG planning keeps vehicle management in the same screen. The lower panel changes between
  range parameters, saved vehicle list and vehicle form. Selecting a profile returns to parameters;
  saving a profile returns to the updated list. Residual autonomy and maximum deviation are still
  explicit trip inputs.
- Direct, ordinary-stop, single-CNG and predictive-CNG routes converge on one final summary.
- The summary shows destination, distance, driving duration, total duration, cumulative CNG dwell,
  traffic availability and relevant warnings. It does not show route IDs or cache internals.
- `Modifica` expands an inline editor. Endpoints and every ordinary/CNG stop are selectable; only
  the selected row exposes modify/delete actions. Deleting a stop recalculates and returns to the
  common summary. Removing one stop from a multi-stop CNG plan reuses its autonomy/reserve inputs.
- Navigation keeps ordinary waypoints as first-class no-dwell visits: arrival pauses guidance until
  explicit completion or confirmed GPS departure, then ETA is recalculated.
- Active navigation persistence restores neutral ordinary-stop state after process recreation.
- Compose stage transitions and the vehicle subpanel are state-driven; recomposition does not reset
  the trip workflow.
- The complete non-map UI now shares one Material 3 design system: branded day/night schemes,
  typography, shape scale, semantic success/warning/error colors, 56 dp primary/outlined controls
  and 48 dp compact actions. The route-creation screen uses Material icons, two-column endpoint
  choices, clear selected/acquiring/error states and a persistent primary route action.
- Navigation overlays, sheets, dialogs, CNG cards, forms and developer surfaces inherit the same
  tokens. Their behavior and information density remain unchanged.
- The navigation puck is a Material-style high-contrast circular direction indicator with dedicated
  day/night assets. Its validated matching, rotation, anchoring and zoom-dependent 50% lower scale
  are unchanged.
- Android version is `0.25.3` (`versionCode=35`).

## Architectural decisions

The direct route remains the immutable comparison basis while optional itineraries are separate
models. This prevents a waypoint route from becoming the next deviation baseline and avoids losing
CNG constraints while editing.

Google Autocomplete suggestions are not bulk-resolved or plotted. Predictions have no coordinates,
and the configured EEA boundary disallows moving Google name/address content onto MapLibre. Only the
selected Place ID is resolved; textual selection and map-safe navigation targets use different
models.

Ordinary and CNG stops remain separate types. Ordinary stops have ordered arrival lifecycle but no
dwell or fuel semantics. CNG stops carry range validation, live evidence and centralised refuelling
dwell. The common summary is a presentation convergence, not a merger of the domain models.

## Repository-local validation

From the repository root:

```bash
PYTHONPATH=src API_AUTH_ENABLED=false .venv/bin/python -m pytest -q
.venv/bin/ruff check .
PYTHONPATH=src API_AUTH_ENABLED=false .venv/bin/python scripts/export-openapi.py --check
docker compose config --quiet
python3 -m pytest tests/test_navigation_ui_phase15_script.py -q
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The directly installable APK is `dist/Compass-0.25.3-debug-installabile.apk`. Its repository-local
build SHA-256 is `4a2251298db560ca93e2d81eff9916ad8ae5a07686a17440ce9726901fa653c4`;
the live runner rebuilds it for the configured API and prints the resulting hash.

## Physical-device/live gate

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER='...'
export COMPASS_CHECK_PASSWORD='...'
bash scripts/run-navigation-ui-phase15-live.sh
```

The server must be rebuilt from this repository version because the runner validates the new plural
waypoint endpoint before touching Android. The runner keeps persistent server and vehicle profiles.
It performs no UIAutomator or screenshot inspection; the operator report is the visual evidence.

## Expected result

1. Endpoint selection, personalization and common summary replace each other with short transitions.
2. Two ordinary stops can be selected on a map, reordered and routed in the displayed order.
3. Google text appears only in the mapless result/selection content; mapped points are neutral.
4. Vehicle list and form replace the lower CNG-planning panel instead of opening unrelated screens.
5. Every planning mode reaches the same summary; inline editing and deletion return there after
   recalculation.
6. Ordinary-stop arrival/resume, puck scaling, voice, traffic, offline guidance and guarded
   termination remain operational.
7. Every non-map surface uses the same Material 3 color, type, shape and control hierarchy in both
   system themes. The route-creation layout keeps the previously accepted flow and visually matches
   the large-title, icon-led, two-column reference.
8. The puck changes with day/night theme, remains directionally legible over MapLibre and keeps its
   established anchor and zoom behavior.

## Operator result

The operator reported the complete physical-device/live gate green on 2026-09-11. This accepts the
route-creation flow, ordinary-waypoint and CNG planning paths, common summary, Material 3 visual
system, and day/night navigation puck covered by the runner. Phase 15 is closed on that evidence.

## Failure diagnostics

Return the complete runner output and:

```text
/tmp/compass-navigation-ui-phase15-navigation.txt
/tmp/compass-navigation-ui-phase15-api.txt
/tmp/compass-navigation-ui-phase15-fatal.txt
/tmp/compass-navigation-ui-phase15-service.txt
```

If API validation fails, also return `docker compose logs --tail=200 --no-color api` from the live
server.

## Remaining limitation

Ordinary waypoints and a CNG itinerary cannot coexist until the server planner validates fuel
reachability over the combined ordered waypoint set. The UI presents them as explicit alternatives
instead of silently weakening either constraint.
