# Navigation UI upgrade — Phase 8 acceptance record

Status: accepted after repository-local validation and operator-assisted live backend and
physical-device validation on 2026-09-06.

## Scope

Phase 8 adds a source-backed current speed-limit badge to active navigation. It does not compare the
driver's speed with the limit, issue warnings, change routing cost, parse signs from maneuver text,
or add lane guidance.

## Design contract

- `VALHALLA_SPEED_LIMITS_ENABLED` defaults to `true` and can disable enrichment without disabling
  routing.
- A successful route is walked through Valhalla `/trace_attributes` using its returned polyline6.
  The filter requests only shape indexes and `edge.speed_limit`.
- Numeric limits are ordered half-open shape ranges. Equal adjacent ranges are compacted. Unknown
  zero and unlimited 255 values are omitted.
- A successful trace identifies `speed_limit_source=valhalla_graph`. Any HTTP, transport, JSON or
  validation failure yields an empty profile and null source while preserving the route.
- Base routes and every CNG route leg expose the same strict fields in OpenAPI.
- Android validates, retains and shape-offsets each profile across base, selected-stop, predictive,
  rerouted and cached routes. Additive cache fields retain version-1 compatibility.
- Only a map-matched `NavigationPosition.routeSegmentIndex` may select a displayed limit. Missing
  position or uncovered segment renders no placeholder badge.
- The badge follows the Italian/European white disc with red border and remains independent of map
  day/night theme. It stays above MapLibre attribution and does not obstruct trip controls.
- Follow-mode pinch zoom uses the puck as its fixed focal point at 75% of viewport height. Scaling
  never releases follow mode and its selected zoom survives later GPS frames; a deliberate
  single-finger pan still enters the existing temporary free mode.
- The camera targets the same interpolated pose rendered by the puck on every animation frame.
  Screen-space top padding, rather than a zoom-dependent geographic look-ahead target, fixes that
  pose at 75% of viewport height.
- When the compact trip panel is open, `Nascondi ﹀` sits inside it on the left and `Dettagli ︿` on
  the right, using centered vector chevrons instead of font glyphs. Its measured height becomes
  bottom camera padding, keeping the puck at 75% of the unobscured map above the panel. The details
  sheet wraps short content and grows only until scrolling is required.
- Android version is `0.18.0` (`versionCode=19`).

Valhalla documents `edge.speed_limit`, `edge.begin_shape_index`, and `edge.end_shape_index` as
filterable trace attributes in its
[official map-matching API reference](https://valhalla.github.io/valhalla/api/map-matching/api-reference/).

## Repository-local validation

From the repository root:

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
.venv/bin/python scripts/export-openapi.py --check
bash -n scripts/run-navigation-ui-phase8-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Live gate

After repository synchronization, rebuild only the API; there is no migration and the accepted
traffic overlay must not be reset:

```bash
cd ~/docker/compass
docker compose --profile traffic up -d --build --no-deps api
docker compose --profile traffic ps api valhalla traffic-updater
curl --fail --silent http://127.0.0.1:8000/health/ready
```

With the backend tunnel open, run on the workstation attached to Android:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-ui-phase8-live.sh
```

No new secret is required. The server's existing uncommitted `.env` may omit the new variable
because its safe default is enabled. Return the complete runner output, route-context summary and
screenshots A–D.

## Expected invariants

- The live route reports `speed_limit_source=valhalla_graph` and at least one valid ordered range.
- During replay, a badge appears only after the puck has a covered matched segment; its number
  agrees with the live profile for the logged segment.
- An aggressive pinch zoom keeps the puck on the horizontal line one quarter of the viewport above
  the bottom, retains the selected zoom across subsequent fixes and logs
  `camera_interaction=zoom mode=follow ... retained=true`, without entering free mode.
- Puck animation logs `camera_sync=puck_pose`; the symbol never trails a separately animated camera.
- The trip panel has no floating hide control near the puck, its chevrons are centered on their
  text, and opening it moves the puck anchor to 75% of the remaining visible map. The details sheet
  occupies only the height required by its current content.
- The badge is legible in day and night themes, does not cover MapLibre attribution, and disappears
  on an uncovered/unknown interval rather than showing zero or 255.
- Route guidance, puck animation, trip controls, foreground service and explicit teardown retain
  their accepted behavior.

## Live findings

The first device inspection on 2026-09-06 accepted the 30 km/h badge in both day and night themes,
including its placement above MapLibre attribution. Aggressive pinch zoom exposed that the generic
camera-gesture listener treated scaling as a free-camera pan, allowing the moving puck to leave its
driving anchor. The corrected gesture policy keeps scale in follow mode with a 75%-height focal
point while retaining free mode for deliberate panning. A subsequent device inspection found two
remaining issues: the floating `Nascondi` control could overlap the puck, the details sheet forced
itself to 90% height, and the independently animated camera could still advance ahead of the puck.
The compact controls are now contained within the trip panel, its details sheet is content-sized,
and the camera consumes the puck animator's exact frame pose. A later device run showed that the
fixed full-screen anchor could still meet the opened trip panel; the panel now contributes measured
bottom camera padding and its typographic chevrons are vector-drawn. The repeated device inspection
then verified these final corrections.

The final physical-device inspection on 2026-09-06 accepted the corrected behavior: the puck stays
at its driving anchor with aggressive zoom, moves upward when the measured trip panel opens, never
falls behind the camera or under the panel, and the `Nascondi`/`Dettagli` vector chevrons are centered
with their labels. The operator explicitly gave the phase a green light, closing the live gate.

## Failure diagnostics

Return `/tmp/compass-navigation-ui-phase8-*`, API logs containing `speed-limit enrichment`, and
Valhalla logs containing `trace_attributes`. Never return `.env`, provider keys or tokenized map
URLs.
