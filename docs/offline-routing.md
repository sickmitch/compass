# Android offline routing: first device-validation increment

Status: implementation for device validation; native on-device acceptance pending.

Local verification (2026-11-18): 360 Android JVM tests passed via
`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` from `android/`;
lint has zero errors (96 warnings, three hints). Python validation passed with
`.venv/bin/ruff check src tests scripts tools` and
`.venv/bin/pytest -q -m 'not integration'`: 516 passed, six integration tests deselected.
Package/route tests use synthetic fixtures and a fake native probe; they do not establish
native engine performance or graph compatibility on a phone. The real probe runs at import.
`dist/compass-0.28.58.apk` is 93,027,835 bytes, versionCode 104, with all four existing ABIs;
APK signature verification passed and its signing certificate matches 0.28.57.

Compass 0.28.58 can reroute an **existing car navigation** on the device, using a previously
imported graph. Online routing remains primary. In airplane mode an off-route event goes
directly to local routing; with an installed package, a network/server failure or an eight
second online timeout triggers fallback. Authentication errors, invalid requests/no-route
responses and cancellation are not silently bypassed. Without a package the previous
online/cached-route behavior is preserved. GPS and off-route detection must still be reliable.

The fallback preserves remaining ordered ordinary/CNG stops, highway exclusion, departure
heading, voice maneuvers and dwell times. Completed stops are omitted. An existing CNG range
plan is carried forward with recomputed leg consumption; a route crossing its reserve is
rejected, not silently substituted with unreachable refuelling. Prices/opening snapshots
remain cached; the phone does not claim a new station availability check. A local route is
identified in navigation status as `Percorso ricalcolato sul dispositivo · dati offline`.
Going online triggers the existing connectivity-recovery routing flow.

This increment does **not** provide offline address/Google Places search, initial trip
planning, new CNG candidate ranking, offline stop editing, pedestrian/bicycle fallback or a
map download catalogue. Downloaded routing data do not contain the visual MapLibre basemap;
its ambient cache alone cannot guarantee map rendering offline. A manual package import is
the initial provisioning path. Automatic downloads and wider offline planning belong to
later increments after the current phone tests pass.

## Architecture and compatibility

- `OfflineRouter` returns existing `RoutePreview` legs. `CompassNavigationRouteRecalculator`
  reuses the existing waypoint/leg-to-navigation converters; the HTTP repository is unchanged.
  The API client's POST transport now cancels its OkHttp call when the coroutine is cancelled,
  including during response-body reads, so the fallback deadline also bounds network waiting.
- Pinned `io.github.rallista:valhalla-mobile:0.6.6` embeds Valhalla 3.9.0. Its upstream source
  pins Valhalla commit `a919bb6b64d61d12f79ea5466d5cf599a39714ad`. Kotlin calls the public
  `Valhalla(configPath).routeRaw()` interface. See
  [upstream release source](https://github.com/Rallista/valhalla-mobile/tree/0.6.6).
- Runtime config is derived from the upstream 3.9.0 config generator, restricted to local
  routing, 10 locations, 500 km straight-line service limit, 64 MiB graph cache and smaller
  label reservations. Those settings are not a bound on total native RSS or route latency.
  No tile URLs, dynamic speed data or arbitrary imported configuration are used.
- Backend stays on 3.8.3. Do **not** copy/relabel its tar as a 3.9.0 package. Build a separate
  graph for this engine. Package compatibility is pinned and a real native probe runs on
  the phone before activation; an engine upgrade requires revalidation.
- A serialized IO dispatcher/mutex protects import and native calls. Actors close after
  each request, including failure. Cancellation is checked while copying and before/after
  a native call; the current JNI API cannot interrupt an in-flight calculation. Its result
  is discarded on cancellation, and resources are released only once the native call ends.
  Initialization cost is currently paid per calculation; caching actors can be evaluated
  after measuring memory and latency. No native computation runs on the UI thread.
- Files are private, excluded from Android backup, hash-checked before activation and
  selected through an atomic pointer. A failed/cancelled import leaves the prior graph.
  Startup reclaims incomplete imports left by process termination while preserving active data.
  SHA-256 detects corruption, not the publisher's authenticity: import only operator-built
  packages. Imported coverage bounds are a declared envelope, not a guarantee that every
  road/path exists; native failure keeps the current navigation route.
- Existing app/API authentication, networks, production datasets and refresh scheduler are
  untouched. NDK 29.0.14206865 is pinned for reproducible native symbol stripping during APK
  packaging; the dependency ships prebuilts, so no on-device build is performed.

## Build a separate package (operator)

These commands are for the operator's server, from `~/docker/compass`, with Docker access,
Python 3.12+ and several GiB of free space. They download open Geofabrik data and run a
separate build; they do not mount or alter `compass_valhalla_datasets`. Use Veneto for the
initial Verona tests. The digest is pinned to the 3.9.0 scripted image.

```bash
mkdir -p offline-routing-build/veneto
docker run --rm --name compass-offline-veneto-build \
  --mount "type=bind,source=$PWD/offline-routing-build/veneto,target=/custom_files" \
  -e tile_urls=https://download.geofabrik.de/europe/italy/veneto-latest.osm.pbf \
  -e serve_tiles=False -e build_tar=True -e build_elevation=False \
  -e build_admins=True -e build_time_zones=True -e server_threads=2 \
  ghcr.io/valhalla/valhalla-scripted:3.9.0@sha256:89daaf61547167893cf58c03defd43c6eba0071dc965988c16a629260f8ac683 \
  build_tiles
python3 tools/export-offline-routing.py \
  --tiles offline-routing-build/veneto/valhalla_tiles.tar \
  --output offline-routing-build/veneto-offline.zip \
  --engine-version 3.9.0 --label Veneto --source-date "$(date +%F)" \
  --bounds 44.7 10.5 46.8 13.2 \
  --probe 45.438 10.992 45.414 11.044
```

The envelope is an initial Veneto coverage hint, not the administrative polygon. Both
probe endpoints must be routable within the extract. Build completion and file sizes
should be checked before export. The exporter refuses an existing output file: choose a
new filename for updates. `source-date` records the operator-supplied snapshot/build date;
it is not a live freshness guarantee. Building Veneto is intentionally separate from the
server graph and can take time; do not start it concurrently with a resource-heavy nightly
build on a constrained host. Additional local space is needed for source data and archives.

Transfer `veneto-offline.zip` to the phone's Downloads using your normal file-transfer
method. Install the versioned APK, open **Opzioni → Routing offline → Importa pacchetto
offline**, select the ZIP and remain on this screen until verification finishes. Expect
`Pacchetto verificato` and the package label/date. There is no public dataset URL to configure
and no backend redeploy/migration required for this increment.

Package structure: `manifest.json` first, then `tiles.tar`; no other entries. Schema version
1 carries engineVersion, label, sourceDate, bytes, SHA-256, bounds, two probe points and OSM
attribution. No zip paths are extracted directly. Limits: 16 KiB manifest, 8 GiB tar, available
storage checked before import. The old and new copies coexist until verification completes.

## Acceptance on FP4 / Android 15

1. Before importing, verify ordinary online routing and cached navigation still behave as
   before. Offline rerouting is unavailable without a package.
2. Import the Veneto package. Native initialization and the local probe must succeed. A
   corrupt ZIP/wrong version must fail while retaining the previous valid package.
3. Start a short online car route within Veneto. Enable airplane mode while leaving GPS
   enabled. Safely deviate from the route (a passenger handles testing). Expect a new route,
   Italian maneuvers, nominal ETA and the local-routing status. No backend request should be
   necessary after connectivity is marked offline.
4. Repeat with highway exclusion and then an ordinary stop plus a CNG stop. Stop order and
   dwell must remain; refuelling is not silently dropped. When an existing autonomy plan
   cannot reach the retained stops with its reserve, keep the current route and report failure.
5. Restore connectivity. Verify the backend can replace the local route without resetting
   completed stops. Test loss of backend access with Wi-Fi/mobile still connected: fallback
   begins after failure or the eight-second cap (only with a local package).
6. Test outside coverage, cancel import, leave the options screen during import, and restart
   the app. No broken package may replace a working one; an unsuccessful reroute must keep
   the current route. The visible basemap may be incomplete if it was not cached.

Collect timings and memory separately; no FP4 performance claims are established by unit
or build tests. From a development machine with Android platform-tools and USB debugging:

```bash
adb logcat -d -s CompassOfflineRouting:I CompassNavigation:I
adb shell dumpsys meminfo org.compass.cng.debug
```

Return import outcome, app version, package size, offline route result, native load/route
milliseconds, memory output and any crash report. Do not send credentials. Measure a short
urban trip and a longer regional trip, repeated reroutes and battery/thermal behavior.
Cancellation cannot preempt the native call in this release; investigate any unbounded
latency before widening the package coverage or enabling the feature for longer journeys.
