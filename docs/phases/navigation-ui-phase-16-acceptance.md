# Navigation UI upgrade — Phase 16 acceptance record

Status: repository implementation complete; physical-device visual gate pending.

## Implemented scope

- Android `0.26.5` (`versionCode=41`) retains the accepted Phase 15 workflow and introduces one
  deterministic responsive policy for compact, medium and expanded widths.
- Landscape phones with sufficient width use two panes for endpoint configuration,
  personalization, map-point confirmation, ordinary-stop preview and CNG map/list selection.
  Portrait continues using the familiar vertical flow.
- Reduced-height windows use shorter headers, tighter section spacing and a smaller preview-map
  allocation. Large text triggers the same protective policy before controls can crowd the map.
- Active guidance bounds the maneuver, route-update and trip cards instead of stretching them on
  wide displays. The maneuver card has a reduced-height variant while retaining the current and
  following instructions.
- In reduced height, the textual `Viaggio` and `Ricentra` controls become Material icon controls
  with complete accessibility descriptions. All interactive targets remain at least 48 dp; primary
  actions remain 56 dp.
- Distance, duration, ETA and station metrics receive equal layout weight, one-line bounds and
  controlled ellipsis so increased font scale cannot make adjacent values collide.
- Route-personalization actions now use consistent local Material icons. Selected-state semantics,
  CNG colors and destructive actions remain distinct.
- `Cambia percorso` and `Aggiungi tappe` form the second equal-width action row. Merely opening and
  leaving the empty ordinary-stop editor no longer enables the ordinary itinerary or disables the
  other planning modes; all uncommitted drafts are discarded.
- Endpoint and ordinary-stop map pickers start from the latest acquired device coordinate. An
  existing explicit map selection remains centred when edited. There is no city fallback: without
  GPS or another explicitly selected endpoint, the picker waits for a real territorial context.
- Destination Autocomplete uses the latest acquired GPS coordinate even outside active follow
  state. Generic ordinary-stop POI queries remain constrained to the route corridor; syntactically
  specific addresses use GPS bias without that corridor restriction.
- Ordinary-stop suggestions retain Google ordering and are not pre-resolved or filtered. Only the
  result explicitly selected by the user is resolved and sent to Valhalla. The configured deviation
  threshold is then enforced for a resolved business/POI selection; exact addresses, current
  position and manually selected map points are not rejected by that policy. Map-point confirmation
  shows an in-button progress indicator while Valhalla calculates the candidate route.
- Origin and destination expose their location methods in the same order. All operational Android
  defaults and examples are neutral or GPS-derived; no city is silently supplied as a search/map
  fallback.
- Route-free follow subscribes concurrently to Android GPS and network location providers. A quick
  network fix can therefore leave the Italy overview immediately while GPS continues acquiring a
  more precise fix; the freshest cached fix across both providers is also applied at startup.
- The dark Material scheme uses true black for the application background, base surface and lowest
  containers. Elevated cards use neutral near-black steps, eliminating the former green cast while
  preserving hierarchy and OLED pixel shutoff on the main canvas.
- Screen changes and progress use shared 180/300 ms motion tokens. Recomposition, rotation and
  theme changes do not create routing calls or clear planner/navigation state.
- Existing edge-to-edge rendering and `safeDrawing` insets remain authoritative for status bars,
  navigation bars and display cutouts.
- The accepted day/night navigation styles, intense-blue route, themed puck, route contrast and
  MapLibre lifecycle are unchanged. This phase does not rebuild style sources or map layers.

## Architectural decisions

`CompassLayoutMetrics` is calculated from available width, height and font scale and provided with
the theme. It contains presentation policy only; navigation and route state remain in their
existing owners. The same classifications drive planning and active-navigation chrome, avoiding
per-screen orientation checks and device-name special cases.

Maps remain unconstrained inside their pane. Maximum widths apply only to non-map cards and action
panels, preserving MapLibre as the dominant surface. A rotated phone therefore gains simultaneous
map/context visibility, while a tablet does not stretch text and controls across its full width.

## Repository-local validation

From the repository root:

```bash
.venv/bin/pytest tests/test_navigation_ui_phase16_script.py -q
PYTHONPATH=src API_AUTH_ENABLED=false .venv/bin/pytest -q
.venv/bin/ruff check .
PYTHONPATH=src API_AUTH_ENABLED=false .venv/bin/python scripts/export-openapi.py --check
docker compose config --quiet
bash -n scripts/run-navigation-ui-phase16-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover width classification, two-pane eligibility, font-scale compact-height behavior,
touch targets, motion durations, current-GPS search context, address/POI deviation policy, absence
of an invented map coordinate, common endpoint-method ordering, dual-provider follow acquisition
and exact OLED background colors. Physical rendering, cutouts, rotation, theme transitions and
one-second glance readability require the operator gate.

The repository-local directly installable APK is
`dist/Compass-0.26.5-debug-installabile.apk` (SHA-256
`58d0951fd4ea972e920822a6b5e5cf57c2fd7ec931ee58fa334d1292529925ca`). The live runner rebuilds
that artifact for the configured API and prints its resulting hash. This patch supersedes the
pre-acceptance `0.26.0` artifact after the first device pass exposed an empty-waypoint-state
regression.

## Physical-device/live gate

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=https://compass.sickmitch.cc/
export COMPASS_CHECK_USER='...'
export COMPASS_CHECK_PASSWORD='...'
bash scripts/run-navigation-ui-phase16-live.sh
```

The runner rebuilds an API-specific APK, installs without clearing persistent server/vehicle
profiles and requests operator checks in portrait, landscape, larger text, active guidance and both
themes. It performs no automated screenshot or UIAutomator inspection.

## Expected result

1. The accepted route-creation flow and all callbacks behave exactly as in Phase 15.
2. The second personalization row contains `Cambia percorso` and `Aggiungi tappe`; cancelling an
   empty stop edit leaves no phantom stop and disables no valid planning action.
3. A new map picker opens at the latest GPS position, while editing an explicit map point reopens
   that point; no arbitrary city is used without a real context.
4. Generic service searches use the latest GPS/route context and expose Google's suggestions
   without resolving or filtering the full list. Only the selected result is resolved and routed;
   the deviation threshold remains scoped to a selected business/POI, while exact addresses and
   manual selections remain valid regardless of it.
5. Map-waypoint confirmation exposes route recalculation progress in the `Scegli` button.
6. Origin and destination choices have identical ordering.
7. Portrait remains compact and scrollable; landscape uses two panes where map and context benefit.
8. No text, metrics, controls or cards collide at normal or increased font scale.
9. Navigation remains glance-readable with a dominant map, unobstructed puck and reachable actions.
10. Light/dark transitions, status/navigation bars and cutouts retain correct contrast and insets.
11. Navigation can be terminated normally and no fatal diagnostics are present.
12. Route-free startup accepts the first network/GPS fix, leaves the national overview and follows
    the device; dark-mode application canvas pixels are true black.

## If it fails

Return the complete runner output, orientation/theme/font setting used, a screenshot when useful,
and these bounded artifacts:

```text
/tmp/compass-navigation-ui-phase16-ui.txt
/tmp/compass-navigation-ui-phase16-fatal.txt
/tmp/compass-navigation-ui-phase16-service.txt
```

## Remaining limitations

- Fold posture is not available to the current dependency set; layouts react to the resulting
  window dimensions rather than hinge metadata.
- Material controls honor large fonts and scroll instead of shrinking text. Extremely large
  accessibility scales may show less simultaneous map content, by design.
- Landscape and physical cutout behavior cannot be accepted from JVM tests and remains gated on a
  real device.
