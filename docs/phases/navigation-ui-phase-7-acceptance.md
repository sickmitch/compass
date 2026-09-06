# Navigation UI upgrade — Phase 7 acceptance record

Status: live structured-data checks passed on 2026-09-06; device gate pending UX remediation
retest.

## Scope

This phase carries Valhalla's structured junction signs and roundabout exit count from the routing
adapter through the public API, Android domain/cache boundary and active guidance overlay. It is a
small continuation of Phase 6 iconography: it does not infer signs from localized instructions and
does not add lane guidance, speed limits, route calculation changes, traffic changes or a second
navigation engine.

## Design contract

- A maneuver may expose a nullable `sign` object with Valhalla's four documented element groups:
  `exit_number_elements`, `exit_branch_elements`, `exit_toward_elements` and
  `exit_name_elements`. Each element preserves `text` and nullable `consecutive_count`.
- A maneuver may independently expose nullable `roundabout_exit_count`. Missing provider data stays
  null and is never reconstructed from the Italian instruction.
- The Valhalla adapter rejects malformed sign containers, blank sign text, negative consecutive
  counts and negative roundabout exit counts. An empty sign object normalizes to null.
- FastAPI publishes strict nested schemas in the checked OpenAPI contract. The same maneuver schema
  is reused by base, selected-stop and predictive-itinerary route legs.
- Android maps these fields through DTO, API, domain and `NavigationRoute` models. The version-1
  private route cache adds optional fields with defaults, so pre-Phase-7 cached documents remain
  readable while new documents retain the structured guidance offline.
- The driving overlay renders a compact theme-aware sign only when structured data exists. It shows
  an exit number/branch heading and exit-name/toward line with bounded ellipsis. A positive
  roundabout exit count appears as a high-contrast badge on the roundabout icon.
- The UI keeps localized maneuver text authoritative. At most three distinct provider elements from
  each group are displayed; no hidden routing or ranking decision is made in Compose.
- Debug builds expose four deterministic rendering examples under `Strumenti sviluppatore` →
  `Verifica segnaletica e uscite`. They are explicitly labelled debug examples and never enter a
  production route or navigation state.
- Android version is `0.17.0` (`versionCode=18`).
- The predictive form is named `Crea viaggio`. Its decimal IME action advances from remaining CNG
  range to reserve, full range and maximum detour without dismissing the keyboard; when a vehicle
  profile exposes the optional gasoline field, detour advances there before the final Done action.
- Ramp, keep and exit glyphs use one selected trunk on the requested side. The alternative begins
  only at the junction, so paths meet but never cross or paint two overlapping trunks. The final
  selected segment and arrowhead share one direction vector.
- Slight-left/right glyphs share a centred entry trunk, then follow a gentle mirrored curve into a
  straight terminal tangent longer than the arrowhead. The shaft therefore reaches the triangle
  base without a kink, premature stop or side-dependent offset.

Lane-level arrows and speed-limit display are intentionally outside this phase. Valhalla's normal
turn-by-turn maneuver response documents sign elements and roundabout exit counts, but not a stable
lane-guidance field. Adding those features requires an explicit, source-backed data contract.

## Repository-local validation

From the repository root:

```bash
.venv/bin/ruff check .
.venv/bin/pytest -q
.venv/bin/python scripts/export-openapi.py --check
bash -n scripts/run-navigation-ui-phase7-live.sh
git diff --check
```

From `android/`:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Tests cover provider normalization and rejection, exact public serialization, strict Android JSON
mapping, UI-model formatting without instruction parsing, cache round-trip preservation, and
mirrored/tangent-aligned geometry for slight-left/right maneuvers.

## Live/backend and device gate

After synchronizing the repository on the live server, rebuild only the API container; this phase
has no migration and must not reset the accepted traffic overlay:

```bash
cd ~/docker/compass
docker compose --profile traffic up -d --build --no-deps api
docker compose --profile traffic ps api valhalla traffic-updater
curl --fail --silent http://127.0.0.1:8000/health/ready
```

The existing Valhalla and private on-demand traffic updater must remain healthy. The real
uncommitted `.env`, including the persisted tileset identity, remains operator-owned and unchanged.

If the backend is remote, keep this tunnel open separately:

```bash
ssh -N -L 8000:127.0.0.1:8000 mike@TEST_SERVER
```

On the workstation attached to the Android device:

```bash
cd /home/mike/NAS/tech/projects/compass
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
export COMPASS_API_BASE_URL=http://127.0.0.1:8000/
bash scripts/run-navigation-ui-phase7-live.sh
```

The server must already contain the repository changes and its real uncommitted `.env`; no new
secret or Phase-7 environment variable is required. Return the complete output and screenshots
A–C.

## Expected invariants

- The live Milan-to-Bologna response contains the new maneuver keys on every maneuver and contains
  at least one provider-backed sign or roundabout exit count for operator inspection.
- Ordinary urban maneuvers without sign data retain the compact Phase-6 overlay without an empty or
  invented sign panel.
- Live and debug-catalog types 9 and 16 enter on the same centred trunk, bend gently in mirrored
  directions and meet their arrowheads without a visible gap or kink.
- The debug gallery renders an autostrada exit sign, a roundabout icon with exit badge `2`, and an
  A1/E 35 direction sign with legible day/night contrast and no clipping.
- Closing the gallery returns to the same active navigation session; matched puck motion continues
  and the foreground service remains active.
- Explicit termination removes the service and active notification, with no fatal exception.

## Failure diagnostics

Return the bounded `/tmp/compass-navigation-ui-phase7-*` artifacts printed by the live runner. Do
not return `.env`, provider credentials or map URLs containing tokens.

## Live findings

The first operator run returned 19 maneuvers, four provider-backed sign objects and one structured
roundabout exit count. The live urban card correctly omitted nonexistent sign data, and the debug
gallery rendered all Phase-7 information. Visual inspection nevertheless found the old fork
geometry misleading: a right branch originated on the left and overlapping complete paths appeared
to cross; its arrowhead also did not meet the selected line cleanly. The same review requested the
predictive-form rename and sequential IME focus. These points are corrected in the repository and
the operator confirmed the corrected fork/exit families. A second inspection isolated the remaining
visual defect to slight-left/right types 9 and 16; their centred, tangent-aligned replacement remains
pending a focused device retest before acceptance.
