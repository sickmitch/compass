# Navigation UI upgrade — Phase 5 acceptance record

Status: accepted after repository-local validation and operator-assisted device gate on 2026-09-05.

## Scope

This phase replaces the general-purpose development cartography with purpose-built low-noise
Compass day/night presentation. It covers every route preview and active-navigation map, automatic
theme selection, deployment overrides and map-owned route/marker contrast. It does not change
routing, live traffic, location filtering, route matching, camera policy or CNG planning.

## Design contract

- `compass-day.json` and `compass-night.json` are MapLibre Style Specification v8 documents bundled
  in the APK. They use the existing OpenFreeMap/OpenMapTiles source and fonts without a token.
- The styles share one restrained layer structure: land/water context, flat buildings, four road
  hierarchy levels, road names, water names and place labels. Neither style uses building
  extrusions or a generic POI layer, so dense urban junctions remain readable.
- Label expressions prefer Italian, then local/neutral and Latin/English fallbacks. The runtime
  localization/filter policy remains active for operator-supplied styles during navigation.
- Compose system dark mode selects the night style. A configuration change reloads only the map
  style and presentation layers; navigation session, matched progress, replay and foreground
  service remain owned by their existing components.
- Day and night palettes independently style remaining/travelled route, origin/destination, CNG
  candidates/stops and marker outlines/text. Night mode uses a bright route on a dark muted map;
  day mode retains the established Compass green on a warm light map.
- `COMPASS_MAP_DAY_STYLE_URL` and `COMPASS_MAP_NIGHT_STYLE_URL` are independent Gradle deployment
  overrides. `COMPASS_MAP_STYLE_URL` remains a backwards-compatible override for both if the new
  properties are absent. Defaults use `asset://` and require no style network request.
- Operators can point either style at an HTTPS-hosted/self-hosted complete style document without
  rebuilding map code. Tile/font endpoints inside a custom document remain the operator's concern.
- Logs expose theme, surface and a bounded source class (`bundled`, `remote_https`, `custom`) but
  never the configured URL, query string or credential.
- Android version is `0.15.0` (`versionCode=16`).

## Repository-local validation

From the repository root:

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
bash -n scripts/run-navigation-ui-phase5-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Pure Kotlin tests cover theme/style resolution, palette separation and redacted logging classes.
Python tests parse both bundled style documents and enforce structural equivalence, flat rendering,
road hierarchy, Italian-first label fields, HTTPS resources and distinct backgrounds.

The initial 2026-09-05 repository run completed with 298 Python tests passed and 5 PostGIS integrations
skipped because `TEST_DATABASE_URL` was not configured. Android unit tests, lint and debug APK
assembly completed successfully. Ruff, OpenAPI drift, shell syntax and whitespace checks passed.
After adding the notification-archive regression tests, the complete Python suite passed with 300
tests and the same 5 environment-dependent skips.

## Live/device gate

If the backend is remote, leave this tunnel running in a separate terminal:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

On the workstation attached to the Android device:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
unset COMPASS_MAP_STYLE_URL COMPASS_MAP_DAY_STYLE_URL COMPASS_MAP_NIGHT_STYLE_URL
bash scripts/run-navigation-ui-phase5-live.sh
```

The runner preserves and restores the device's original day/night setting. It validates both style
documents, builds and installs the APK, requires successful bundled-style callbacks, switches the
system theme twice during an active replay, verifies foreground-service continuity and checks final
notification teardown.

Return the complete output and screenshots A–D: day route preview, day active navigation, the same
navigation in night mode and restored day navigation.

## Expected invariants

- The day map is light and quiet while retaining road hierarchy, Italian/local labels, route,
  endpoints and CNG markers.
- Buildings are flat and cannot obscure the route or junction under the accepted driving camera.
- The night map is genuinely dark rather than a dimmed day bitmap; road/label hierarchy remains
  legible and the route, vehicle, travelled line and CNG markers retain clear contrast.
- Changing theme while replay/navigation is active does not restart navigation, lose progress,
  remove planned CNG stops, interrupt puck motion or stop the foreground service.
- Returning to day mode reloads the bundled day style and retains the same session.
- Logs prove day/night bundled style completion without exposing a style URL.
- Explicit termination removes the foreground service and notification with no fatal exception.

## Failure diagnostics

Return the bounded `/tmp/compass-navigation-ui-phase5-*` artifacts printed by the runner. Do not
return `.env`, map-provider credentials or custom style URLs containing tokens.

## Accepted device evidence

The operator returned all four requested screenshots on 2026-09-05. They show the bundled light
preview, low-noise day guidance, genuinely dark night guidance and restored day guidance. Buildings
remain flat; road names, route and vehicle stay legible in both palettes. Maneuver distance changes
across the three guidance screenshots demonstrate that the same replay continued through both
theme changes.

The bounded artifacts provide the remaining evidence: the route cache recorded four CNG stops,
MapLibre completed bundled day-preview, day-navigation, night-navigation and restored-day loads,
and 52 animated puck transitions occurred. Final state recorded `operator_stop`, no Compass
foreground service, no active Compass notification and no fatal exception.

The original runner's teardown check also searched Android's historical notification archive and
could falsely report an already removed navigation notification. The gate now extracts only the
current `Notification List`; deterministic tests cover both an archived Compass notification and a
genuinely active one. The returned notification dump passes the corrected check, so another device
run is unnecessary.
