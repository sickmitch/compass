# Navigation UI upgrade — Phase 6 acceptance record

Status: accepted after repository-local validation and operator-assisted device checks on
2026-09-06.

## Scope

This phase replaces the provisional text-character maneuver symbols introduced in Navigation UI
Phase 1 with a complete vector visual system over Valhalla maneuver types 0–36. It covers the
current and following maneuver, accessibility labels, a debug-only visual catalog and the device
gate needed to inspect rare families. It does not change route calculation, maneuver progression,
voice guidance, traffic, CNG planning, map matching, puck motion, camera behavior or cartography.

## Design contract

- `ManeuverVisual` is a presentation-only interpretation of the structured Valhalla `type`. The
  original maneuver and localized instruction remain authoritative navigation data.
- Every currently published Valhalla type from 0 through 36 maps explicitly to a non-fallback
  family and a non-empty Italian accessibility label. The mapping follows Valhalla's
  [turn-by-turn API reference](https://github.com/valhalla/valhalla-docs/blob/master/turn-by-turn/api-reference.md).
- The system distinguishes start and destination sides; straight, slight, normal, sharp and
  U-turn directions; ramp, exit, keep and merge junctions; roundabout entry/exit; ferry
  entry/exit; transit, transfer/remain and transit-connection states.
- Left/right variants share one geometry policy and are mirrored from the same route shape, which
  prevents the two sides drifting into inconsistent designs.
- Unknown future type identifiers use a bounded Italian-instruction fallback. If no safe hint is
  present, a visibly unknown glyph is used instead of inventing a maneuver.
- `ManeuverIcon` draws density-independent Compose vectors using the surrounding Material content
  color. No font arrow, bitmap, icon package, navigation SDK or provider artwork is required.
- The primary 46 dp glyph and compact 20 dp `Poi` glyph are derived independently from current and
  following maneuvers. A following instruction therefore no longer lacks visual direction.
- All glyphs expose semantic content descriptions. The normal driving surface contains no numeric
  provider types.
- Debug builds expose a full-screen, scrollable catalog under `Strumenti sviluppatore`; it is not
  reachable from release navigation controls and does not mutate the active session.
- Android version is `0.16.0` (`versionCode=17`).

## Repository-local validation

From the repository root:

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
bash -n scripts/run-navigation-ui-phase6-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Pure Kotlin tests enumerate 0–36, reject accidental fallback/blank labels for published types,
verify turn angles and mirrored sides, distinguish junction and transport families, and cover the
bounded unknown-type fallback. Android compilation validates the Compose vector renderer.

The initial repository run completed with 300 Python tests passed, 5 PostGIS integration tests
skipped because `TEST_DATABASE_URL` was not configured, and 26 dependency deprecation warnings.
Ruff passed. Android unit tests, lint and debug APK assembly completed successfully.

## Live/device gate

If the backend is remote, leave this tunnel running separately:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

On the workstation attached to the Android device:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-ui-phase6-live.sh
```

Return the complete output and screenshots A–D: live primary/following turns, catalog start/turn
families, road-junction/roundabout families, then ferry/transit/connection families.

## Expected invariants

- The live primary right-turn icon is a crisp route shape rather than a text arrow, and the small
  following icon independently previews the subsequent left turn.
- Turn angle and side are visually unambiguous; left/right variants are clean mirrors.
- Ramp, exit, keep, merge and roundabout families communicate different path structures.
- The debug catalog contains every type 0–36 exactly once with a visible Italian label; types
  28–36 provide visually legible ferry and public-transport states.
- Icons retain contrast under the active Material day or night palette and scale without clipping.
- Arrow shafts terminate underneath their triangular heads; no round stroke cap protrudes beyond
  an arrow tip at either the primary or compact size.
- Opening/scrolling/closing the catalog does not restart navigation, interrupt matched puck
  updates, or stop the foreground service.
- Explicit termination removes the service and active notification, with no fatal exception.

## Failure diagnostics

Return the bounded `/tmp/compass-navigation-ui-phase6-*` artifacts printed by the runner. Do not
return `.env`, provider credentials or custom map URLs containing tokens.

## Live findings

The first operator visual pass on 2026-09-05 confirmed the live current/following icons and the
catalog through type 36. It also exposed a thin shaft protruding beyond triangular arrow tips. The
first correction removed the round terminal, but the operator's 2026-09-06 retest showed that this
was insufficient: the full-width shaft was still painted through the triangle's taper up to its
apex. Arrowed paths are now measured and shortened by exactly the arrowhead length, ending at the
triangle base. The same geometry helper bounds merge, roundabout-exit, ferry-exit and transit
connection stems. The operator confirmed the corrected live and catalog rendering on 2026-09-06:
arrow shafts no longer protrude beyond their triangular heads. Together with the earlier catalog
inspection through type 36 and live-navigation evidence, this accepts Navigation UI Phase 6.
