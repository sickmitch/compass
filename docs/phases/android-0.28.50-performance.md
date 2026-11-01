# 0.28.50 — Routing and navigation overhead

## Changes and bounds

- Automatic destination search waits 800 ms after the last edit; manual search stays
  immediate and cancels the delayed request. Android build configuration, API settings,
  Compose and the environment example share the default. Existing explicit overrides
  still apply; set `DESTINATION_SEARCH_DEBOUNCE_MS=800` if overriding the default.
- API lifespan owns one HTTPX client for Valhalla connection reuse. Request-specific
  adapters retain their own timeouts/settings; shutdown closes the pool even on failure.
  No route/ETA caching is introduced. Adapter use outside the API lifecycle keeps its
  existing request-scoped fallback.
- CNG matrix batches calculate invariant prefix/suffix leg totals once per batch rather
  than once per candidate, preserving arithmetic order and ranking/filter semantics.
- Camera look-ahead uses a constant-space view of the remaining geometry and reads only
  the segments needed for its heading. It no longer copies the whole route or allocates
  a pair for every segment on each animation frame. Route rendering is unchanged.
- Driving UI presentation is memoized by navigation state and display timezone: opening
  menus does not rebuild unchanged maneuver/arrival labels. GPS/animation rates unchanged.

Regression checks cover shared HTTP-client lifetime/error cleanup, camera output and
bounded geometry reads on a 100,000-point route, search at 799/800 ms, cancellation and
immediate search, plus existing detour tests. These prove behavior and reduced work in
specific code paths, not device FPS or production latency improvements.

## Local validation

From repository root:

```bash
.venv/bin/ruff check src tests scripts
.venv/bin/pytest -q -m 'not integration'
.venv/bin/python scripts/export-openapi.py --check
docker compose --profile routing config --quiet
cd android
JAVA_HOME=/home/mike/toolchains/jdk17 ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk ./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug
```

The last command uses this development machine's toolchain paths; configure your own
JDK 17 and Android SDK paths on a different machine.

Local result: 347 backend tests passed (5 integration tests deselected); 293 Android
unit tests passed. Ruff, OpenAPI consistency, Compose configuration, Android lint, APK
assembly and APK signature verification passed. Live server/device checks remain pending.

## Operator acceptance

Backend sync/redeploy required. In the server repository (`~/docker/compass`), with the
existing `.env` credentials and routing stack available:

```bash
git pull --ff-only
docker compose --profile routing up -d --build --no-deps api
docker compose ps api
```

Install `dist/compass-0.28.50.apk` (or accept the automatic update after publication).
Type a place continuously, pause less than 800 ms, then continue: no automatic request
until the last edit has settled for 800 ms. Pressing Search should still be immediate.
Check rapid changes do not display results from an older query.

Compare normal routing and CNG plans including intermediate stops with the previous
release: distance, nominal ETA and candidate eligibility must remain consistent for the
same inputs/maps. Exercise long-route navigation, rotation, PiP and opening/closing menus:
puck/camera must remain smooth and route/arrival labels must update normally.

If failures occur, return the action sequence, APK version and bounded diagnostics:

```bash
docker compose logs --since 10m --tail 200 api
adb logcat -d -s AndroidRuntime CompassNavigation
adb shell dumpsys gfxinfo org.compass.cng.debug framestats
```

Frame timings are device-specific: compare equivalent runs on the same device and route.
No live-server/device result is claimed by local unit tests. Next step: operator validation
before expanding the optimization scope.
