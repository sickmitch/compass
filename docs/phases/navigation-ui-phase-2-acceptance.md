# Navigation UI upgrade — Phase 2 acceptance record

Status: accepted after repository-local validation and operator-assisted device gate on 2026-09-04.

## Scope

This phase upgrades MapLibre navigation-camera behavior and replaces the unsuitable low-zoom demo
basemap with a configurable road-capable development default. Routing, navigation progress, route
matching, the vehicle puck, CNG planning, voice guidance and offline recovery remain authoritative
in their existing components. Navigation UI Phase 3 will address puck interpolation separately;
Navigation UI Phase 5 still owns Compass-specific day/night cartographic design.

## Design contract

- `NavigationCameraConfig` is the single policy source for pitch, zoom, speed thresholds,
  look-ahead distance, animation timing and overview padding.
- Follow mode aligns MapLibre bearing with a short forward tangent of the matched route rather than
  a potentially lagging raw-location bearing, and maintains a 52–58 degree pitch within the
  requested 45–60 degree driving range.
- Zoom changes continuously between urban and motorway policy values. An approaching maneuver adds
  bounded detail, while the distance between consecutive maneuvers moves the camera closer in dense
  junction sequences and farther away on sparse route sections.
- The camera target is projected forward from the matched position on the local route-heading
  centreline. Look-ahead is limited near an imminent maneuver, so the vehicle appears below center
  without drifting sideways when the route bends beyond the immediate road segment.
- MapLibre executes camera changes with eased transitions rather than instantaneous resets.
- A pan, rotation or zoom gesture enters `FREE` mode and stops automatic camera updates until the
  driver presses `Ricentra` or the ten-second inactivity timeout expires.
- `Panoramica` frames only the remaining route at zero bearing and pitch; `Ricentra` restores the
  forward-looking heading-up view.
- The development build defaults to OpenFreeMap Liberty, an OpenStreetMap/OpenMapTiles road-capable
  style usable by MapLibre Native. `COMPASS_MAP_STYLE_URL` remains the deployment override and can
  point to a self-hosted style; no tile-provider key or proprietary SDK is introduced.
- Name-bearing map layers prefer `name:it` and then neutral/local names, while road-reference
  shields retain their original expression. Italian labels are a client presentation policy and do
  not alter route or search data.
- Active navigation combines each basemap POI layer's existing rank/geometry filter with a strict
  driving whitelist: fuel/charging stations, toll booths, border control and traffic signals when
  the tileset supplies them. Transit stops, libraries, shops, tourism and other generic POIs are
  suppressed. Compass-planned CNG waypoint layers remain independent and always visible.
- The matched position uses a directional vector vehicle instead of a circular point. In heading-up
  follow it is locked vertically to the viewport, avoiding transient tilt while the camera bearing
  is easing; free and overview modes rotate it against the map using the matched-route tangent. CNG
  waypoints and the compact trip summary use explicit `CNG` markers instead of a generic pump.
- The follow camera applies centralized top viewport padding so the vehicle sits lower and keeps
  more road visible ahead.
- A manual viewport returns to follow after ten seconds without another gesture. Every new gesture
  restarts the timeout; `Ricentra` remains available for an immediate return and uses explicit
  contrast colors.
- The compact trip/CNG panel is hidden initially and exposed by the `Viaggio` map control. Its
  expanded details sheet and actions are unchanged.
- Camera mode remains presentation state owned by the active navigation surface. Route and progress
  state remain in the existing `NavigationState`.
- Android version is `0.12.0` (`versionCode=13`).

No navigation SDK or new binary dependency was introduced. The existing filtered/matched Compass
navigation position and MapLibre camera APIs provide everything required for this increment, so an
external component would add ownership and integration cost without solving a missing capability.

## Repository-local validation

From the repository root:

```bash
bash -n scripts/run-navigation-ui-phase2-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Unit tests cover forward route targeting, continuous speed-dependent zoom/pitch/look-ahead,
imminent-turn look-ahead limiting, route-tangent heading when the location bearing lags, camera
placement/timeout defaults and Italian label-layer selection with fallbacks.

The live runner uses bounded `CompassNavigationUi` events for camera-mode transitions. UIAutomator
capture remains best-effort during an animated map/replay; screenshots and operator observation are
the acceptance evidence for motion, perspective and visual placement.

## Live/device gate

The operator synchronizes the repository and runs:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
export COMPASS_MAP_STYLE_URL=https://tiles.openfreemap.org/styles/liberty
bash scripts/run-navigation-ui-phase2-live.sh
```

For a remote backend, keep the SSH tunnel printed by the script open. Return the complete output
and screenshots A–F: dense-maneuver follow, sparse-maneuver follow, optional trip/CNG summary,
manually positioned map with `Ricentra`, automatic restored follow and north-up remaining-route
overview.

## Expected invariants

- Follow motion is continuous over multiple replay/GPS updates rather than visibly teleporting.
- Bearing tracks travel direction, the follow vehicle stays vertical and pitch remains suitable for
  driving.
- Zoom eases closer for consecutive nearby maneuvers and wider for sparse maneuvers, within the
  centralized minimum and maximum bounds.
- Ordinary streets, their hierarchy and Italian labels are visible beneath the Compass route.
- Bus/rail stops, libraries, shops and generic POIs are absent; only available driving-relevant
  infrastructure survives the basemap POI filter.
- A directional vehicle replaces the point and is below the map center with more route ahead.
- The summary is hidden by default, toggles without opening the details sheet and identifies CNG
  without a petrol-pump glyph.
- Manual pan/rotate/zoom leaves follow mode and subsequent GPS updates do not steal the viewport.
- Ten seconds without interaction restores follow automatically; `Ricentra` is legible and restores
  follow immediately when pressed.
- Overview is north-up and frames only remaining geometry.
- Camera interactions do not stop navigation or its foreground service.
- Explicit termination removes the foreground service and notification.

## Accepted device evidence

The operator completed `scripts/run-navigation-ui-phase2-live.sh` on 2026-09-04. The returned run
reported a successful Android build/install/cold launch, completed every operator checkpoint,
preserved the foreground service during navigation and removed the service and notification after
termination. Returned screenshots demonstrated dense and sparse maneuver zoom, a vertical low-set
vehicle in heading-up follow, Italian road context without generic POI clutter, the CNG details
surface, manual-camera suspension with legible `Ricentra`, and north-up remaining-route overview.

## Known limits

- This phase animates the camera around the existing matched position source. Smooth puck movement
  and rotation are intentionally reserved for Navigation UI Phase 3.
- Pitch is easier to judge while moving than in a static screenshot, particularly with the current
  general-purpose development style; the operator must observe the transition on-device.
- OpenFreeMap Liberty is a development baseline, not the final Compass cartography. Navigation UI
  Phase 5 will define purpose-built low-noise day/night styles and their deployment strategy.
- The current OpenMapTiles POI schema does not publish traffic-signal nodes. The filter is ready for
  a compatible future tileset, but Compass does not fabricate unavailable traffic-light data.

## Failure diagnostics

For future regressions, return the bounded `/tmp/compass-navigation-ui-phase2-*` artifacts printed
by the runner.
