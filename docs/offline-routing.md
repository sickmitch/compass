# Android offline routing

## Joint regional routing in 0.28.63

Compatible regional packages are now used **together for one route**, including intermediate
stops and navigation recalculation. Android groups areas from the same complete graph build
and exposes their tiles to one native Valhalla actor. Installed schema-1 packages keep working
individually; to combine areas, import schema-2 regions exported from a common graph.
See [ADR 0026](adr/0026-composable-offline-map-regions.md) for the format and compatibility rules.

### Weekly Italy packages

The [container scheduler](scheduled-updates.md#weekly-offline-packages) now generates
Nord-Ovest, Nord-Est, Centro, Sud/isole and full Italy together every Sunday at 01:00
Europe/Rome, with names such as `nord-est_22112026.zip` under `./offline_assets`
in the deployment project.
Use all regional files from the same completed set (`latest.json`). These are schema-2
packages for the existing client; no APK update is needed for scheduled generation.

### Generate compatible packages

From the repository root, with Python 3.11+ (standard library only), no credentials required:

```bash
python3 tools/split-offline-routing.py \
  --package custom_files/nord-est-offline.zip \
  --engine-version 3.9.0 \
  --regions docs/examples/offline-nord-est-regions.json \
  --output-dir offline-routing-build/nord-est-combined
```

This reuses the original Nord-Est graph without rebuilding or modifying it. Outputs are
`nord-est-ovest.zip` and `nord-est-est.zip`; import **both** in Options → Routing offline.
The example splits at longitude 11.5 so a Verona–Padova journey uses both areas.
Files with existing output names are never overwritten. For a repeat export choose another
output directory. Locally supplied `custom_files/*.zip` and generated builds are ignored by Git.

For other regions, define a JSON list with `id`, `label`, `bounds` (south/west/north/east) and
two local `probe` coordinates per area. The input must cover the union of all required areas.
A complete Valhalla 3.9.0 `valhalla_tiles.tar` can be supplied via `--tiles` instead of `--package`,
adding `--source-date YYYY-MM-DD`. Existing schema-1 ZIPs are checksum-verified before splitting.
A collection of independently built regional graphs cannot be passed as one input: build a
wider graph once and partition that exact build. The same engine version or source date alone
is not enough to establish compatibility. Never edit `graphFamily` manually.

The UI marks new packages as **Area combinabile** with a short set identifier (matching identifiers
indicate the same full build), and legacy packages as **Pacchetto indipendente**.
No fixed package-count cap is introduced. The importer verifies every tile and rejects conflicting
shared files. Removing a region invalidates the derived shared view without deleting other areas.
There must still be installed road coverage for the whole journey, including any necessary detour.

### Phone acceptance for 0.28.63

Only the Android app and exported packages change. No backend redeploy/database migration is
needed. On a machine with platform-tools and an authorized USB-debugging phone, repository root:

```bash
adb install -r dist/compass-0.28.63.apk
adb push offline-routing-build/nord-est-combined/nord-est-ovest.zip /sdcard/Download/
adb push offline-routing-build/nord-est-combined/nord-est-est.zip /sdcard/Download/
```

1. Import both ZIPs in Options. Both must be listed as combinable and survive app restart.
   Keep the original ZIP outside the app, and remove the legacy Nord-Est installation **in the
   app** for this test so it cannot act as a full-area fallback.
2. In airplane mode with GPS enabled, select Verona (45.438, 10.992) and Padova (45.406, 11.877)
   using map points/favorites/coordinates. A single continuous route must be calculated with
   nominal ETA and maneuvers. Repeat in reverse and with an ordinary intermediate waypoint.
3. Start navigation and exercise offline recalculation as usual. Confirm it can still use
   both regions. Record latency and any route/geometry discontinuity; this is a device check.
4. Remove the eastern package: Verona–Padova must fail explicitly rather than fabricate a
   connection or retain the removed area through the derived view. A short Verona route must
   still work. Reimport the eastern package and verify cross-area routing recovers.
5. A package from another complete build must remain independent, never supply missing tiles
   to this set. Legacy schema-1 standalone routes must remain usable.

Return the import summaries, app version, route outcomes/durations for steps 2–4, and any
failure text. If import or routing fails, collect:

```bash
adb logcat -d -s CompassOfflineRouting:I CompassNavigation:I AndroidRuntime:E
adb shell dumpsys meminfo org.compass.cng.debug
```

Native cross-region routing, import probes on the phone and physical hard-link support remain
operator acceptance checks. Local fixtures and tile-integrity verification do not establish
those device results.

### Local validation for 0.28.63

```bash
android/gradlew -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
.venv/bin/pytest --noconftest -q tests/test_offline_routing_split.py tests/test_offline_routing_export.py
.venv/bin/ruff check tools/split-offline-routing.py tests/test_offline_routing_split.py
```

JDK 17/Android SDK: build succeeded, 394 JVM tests passed without failures/skips; lint had
zero errors, 100 warnings and three hints. Instrumentation was compiled, not executed.
Python: eight tests passed and Ruff passed. Tests cover compatible cross-region selection,
actual hard-linked union construction, shared tile conflicts, canonical paths, checksums,
cancellation, removal/reassembly and separation of different builds.

The supplied `custom_files/nord-est-offline.zip` was checksum-verified and exported unchanged
into the example western/eastern packages (113 and 150 tiles). Their union contains 214 unique
tiles, all byte-identical to the source; common files have matching hashes. The source has
296 tiles overall: local tiles outside the example coverage/halo are intentionally excluded.
Both packages have set prefix `a87ab06b`; the original ZIP remains intact and untracked.
These local artifacts are in `offline-routing-build/nord-est-combined/`, not in Git.

APK 0.28.63/versionCode 109: 93,133,784 bytes, signature verified against the existing update
certificate; `dist/last.txt` points to `compass-0.28.63.apk`.

## Offline search availability in 0.28.62

Place search now uses the existing backend-availability signal, just like CNG features.
Search buttons for origin, destination and ordinary stops are disabled when the network or
server is unavailable. An already open search disables its field, submit, result selection
and pagination; its voice dialog is disposed and recording cancelled. A short message points
to favorites, current location and map selection, which remain available offline.

The ViewModel also rejects search/resolution actions, cancels pending debounce and HTTP work,
clears old suggestions and invalidates their revision on backend loss. The typed query and
existing route are retained. Local waypoint routing is not cancelled by this search guard.
Backend recovery re-enables search without automatically submitting the retained query.
Detection reuses the network callback and existing readiness probe (every 30 seconds while
visible, five-second deadline); no extra polling or backend changes are introduced.

Local validation: `android/gradlew -p android :app:testDebugUnitTest :app:lintDebug
:app:assembleDebug :app:compileDebugAndroidTestKotlin` succeeded with JDK 17 and the configured
Android SDK. All 388 JVM tests passed (zero failures/skips), including offline entry guards,
cancelled debounce, recovery and cancellation of in-flight search/resolution. Lint reported
zero errors, 100 warnings and three hints. Instrumentation was compiled, not run on a device.
APK 0.28.62/versionCode 108 is 92,725,979 bytes, its signature matches the previous release,
and `dist/last.txt` points to it. Phone UI/network acceptance remains pending below.

### Phone acceptance for 0.28.62

No backend redeploy or ZIP reimport is required. From the repository root, with Android
platform-tools and an authorized USB-debugging device, no secrets required:

```bash
adb install -r dist/compass-0.28.62.apk
```

1. Enable airplane mode: both endpoint **Ricerca** buttons and the ordinary-stop **Cerca**
   action must be disabled. Favorites, current GPS position and map selection stay usable.
2. Online, open search and type a query, then disable connectivity. The field/submit must
   become disabled, old suggestions disappear and any spinner stops. Repeat while the voice
   dialog is open: it must close and release the microphone.
3. Reconnect and wait for server readiness (up to the existing probe interval). Search must
   become usable again; submit the retained query to get fresh results. Also verify a normal
   offline route and ordinary stop using the already installed package.
4. Repeat with network available but Compass server unreachable: search must be disabled
   after readiness failure, without disabling local selection or routing.

Return the app version and behavior in those four checks. If a button stays enabled or the
UI gets stuck, return a screenshot and the following output (review private data first):

```bash
adb logcat -d -s CompassPlanner:I CompassApi:I CompassOfflineRouting:I AndroidRuntime:E
```

## Multiple packages in 0.28.61

Compass can retain any number of independently imported ZIP packages, limited by available
storage rather than a hard-coded package count. The existing schema-1 ZIP format and Valhalla
3.9.0 compatibility checks stay unchanged; the 8 GiB per-archive safety limit still applies.
In Options → Routing offline, select one or several ZIPs, inspect installed labels/dates/sizes,
and remove individual packages with confirmation. Exact graph duplicates (SHA-256) do not
create another installed copy. Different graphs, including different versions of one area,
coexist until explicitly removed. Import validation still needs temporary space for one ZIP's
graph; imports are streamed sequentially, never all loaded in memory.

A private, atomically published `catalog.json` replaces the single `active.txt` pointer.
The existing package is migrated automatically without reimport. A failed/cancelled import
leaves previously published packages intact, including earlier successes in the same batch.
Restart removes only unpublished staging directories. An unreadable package does not disable
other areas and remains individually removable. The UI virtualizes the package list.

Routing automatically filters packages by **all** waypoint coordinates, tries the most recently
imported eligible graph first, and tries other overlapping packages on routing failure.
Import, removal and native calculation share the existing mutex/IO dispatcher, so removal
cannot delete an in-use graph. Native actors close between attempts; the runtime explicitly
disables the global tile cache. This follows the pinned engine's
[GraphReader/cache implementation](https://github.com/valhalla/valhalla/blob/a919bb6b64d61d12f79ea5466d5cf599a39714ad/src/baldr/graphreader.cc).

**Multiple installed packages do not imply a merged graph.** Each route must be calculable
within one package. Independently built ZIPs are kept separate: this implementation does not
merge conflicting tile IDs or stitch route fragments at their boundaries. If no package covers
the entire journey, online routing or a larger unified package is required. Bounding rectangles
are only a prefilter; the native graph determines whether a valid route actually exists.

### Local validation for 0.28.61

On 2026-11-20, using JDK 17 and the configured Android SDK from the repository root:

```bash
android/gradlew -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
.venv/bin/pytest --noconftest -q tests/test_offline_routing_config.py
```

385 JVM tests passed with zero failures/skips, including migration, 40 coexisting packages,
duplicate imports, cancellation, failed publication/removal, corrupt packages and automatic
area selection. Clean build succeeded; lint reported zero errors, 100 warnings and three
hints. Python: one config regression passed; the optional `pyvalhalla` check was
skipped because that native dependency is absent. Instrumentation was compiled, not run.
The restored GPS-provider tests also compile again through a helper extracted from the
existing provider filtering, with unchanged subscription behavior.

APK versionCode 107, versionName 0.28.61, 92,722,287 bytes. Signature verification passed;
the signing certificate is unchanged. `dist/last.txt` contains `compass-0.28.61.apk`.

### Phone acceptance for 0.28.61

No backend redeploy, new ZIP format or rebuild of existing graphs is needed. Update the app
without clearing its data. From the repository root on a machine with Android platform-tools,
USB debugging enabled and the phone authorized (no secrets/environment variables required):

```bash
adb install -r dist/compass-0.28.61.apk
```

1. Open Options → Routing offline: the previously installed Nord-Est package must remain listed.
2. Select two different ZIP graphs together. Both must be listed after validation and app restart,
   alongside the old package. Importing the same ZIP again must not increase the count.
3. In airplane mode with GPS enabled, calculate within area A, then area B, then A again without
   restarting the process; verify the correct area is used each time. Test an ordinary waypoint too.
4. Import a bad ZIP alongside a valid one, then interrupt a later import by leaving the screen.
   Already verified packages must remain routable. Only completed imports survive restart.
5. Remove B with confirmation: A remains usable. Remove the last valid package: offline routing
   is no longer advertised as available, while existing cached guidance remains unaffected.
6. Try a route spanning disjoint packages: no artificial connection or fabricated route is returned.
   Try overlapping packages where only one has the full road path: a later candidate may succeed.

Return the app version, labels/count before and after restart, import summary and the A→B→A
area-switch results. If it fails, collect on the same computer (review private coordinates):

```bash
adb logcat -d -s CompassOfflineRouting:I CompassNavigation:I AndroidRuntime:E
adb shell dumpsys meminfo org.compass.cng.debug
```

Device-level switching between real native graphs and UI acceptance remain operator checks;
unit tests use fixture graphs/fake native callbacks and do not establish those results.

## Initial planning in 0.28.60

On 2026-11-19 the operator confirmed that import and offline rerouting work on the phone,
accepting the preceding increment. This release extends the same engine to initial **car
A→B routes and ordinary intermediate stops**. The new phone acceptance is pending.

`OfflinePlanningRepository` decorates the existing HTTP repository for direct/waypoint
planning only. An unreachable backend or an eight-second deadline falls back to the installed
regional package; known offline state bypasses HTTP. Highway exclusions, origin heading,
ordered legs, distances, nominal durations and explicit destination selection are preserved.
Native calls remain serialized off the UI thread. Authentication, invalid/no-route responses
and caller cancellation do not silently trigger fallback.

While the activity is visible, a network callback plus a `/health/ready` probe every 30 seconds
(with a five-second cancellable deadline) monitors backend availability. Routing request
failures also update that state. A network connection alone does not establish readiness;
a LAN-only connection can still reach a self-hosted backend. No extra GPS requests or background
polling are introduced. Recovery re-enables CNG commands after a successful probe/request.

When unavailable, CNG stop search, station selection, whole CNG planning and replacement are
disabled/dimmed. Pending CNG calculations are cancelled without discarding the base route.
Ordinary stops can still be added, reordered, removed and calculated with the local graph.
Existing navigation retains its prior CNG stops/range/dwell through the accepted rerouting
flow; this is not a new offline CNG ranking engine.

Select endpoints/stops using **favorites, coordinates, current GPS position or map points**.
New address/Google Places/POI search still requires the backend. The package does not include
the visual basemap: only cached map imagery may be available. Walking/cycling offline,
automatic package downloads and new offline CNG selection remain outside this increment.
A car route outside the installed graph fails explicitly; coverage is not extended by fallback.

### Phone acceptance for 0.28.60

Install `dist/compass-0.28.60.apk` on FP4/Android 15; keep the already imported Nord-Est package.
No backend sync/redeploy, graph rebuild, reimport or app-data clearing is needed. From a
computer with Android platform-tools, USB debugging and authorized phone (no secrets needed),
repository root:

```bash
adb install -r dist/compass-0.28.60.apk
```

1. Online, calculate an ordinary car trip: existing routing behavior stays intact.
2. Reopen the app in airplane mode, GPS enabled. Choose endpoints from favorites or coordinates
   inside Nord-Est and calculate a **new** route. Expect preview, Italian maneuvers, nominal
   ETA and local-route status; start navigation without first calculating online.
3. Add two ordinary stops from favorites/map, reorder/delete one, calculate and start. Verify
   stop order, distance/time and unchanged destination; repeat with highways excluded.
4. CNG buttons must be dimmed and untappable in preview, Add stop and any CNG page left open
   before disconnection. Losing connectivity during CNG calculation must stop the busy state
   and preserve the ordinary route. Repeat with the backend unreachable but Wi-Fi active.
5. Restore access and keep the app visible: within a probe cycle (up to ~35 seconds), CNG
   actions become available again. New routes prefer the backend.
6. Test outside coverage and walking/cycling without a server: no invented route or silent
   mode change. Confirm existing offline off-route recalculation still behaves as before.

If a check fails, return the step, version, package label, visible message and logs (review
private coordinates before sharing):

```bash
adb logcat -d -s CompassOfflineRouting:I CompassPlanner:I CompassApi:I CompassNavigation:I AndroidRuntime:E
adb shell dumpsys meminfo org.compass.cng.debug
```

Local unit/build tests do not establish FP4 latency, map rendering or multi-stop JNI behavior.
Those require the phone checks above before further expansion.

### Local validation for 0.28.60 (2026-11-20)

From `android/`, JDK 17 / Android SDK configured:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

372 JVM tests passed (zero failures/skips); build and instrumentation compilation succeeded.
Lint: zero errors, 98 warnings and 3 hints. Instrumentation was compiled, not run
on a device. The Python runtime-config regression passed; its optional `pyvalhalla` native
check was skipped because that separate dependency is not installed in this environment.
APK versionCode 106, versionName 0.28.60, 92,700,709 bytes. Signature verification passed;
the certificate matches 0.28.59. `dist/last.txt` contains `compass-0.28.60.apk`.

## Accepted import/rerouting foundation (0.28.58–0.28.59)

The initial import failed with `No such node (meili.default.breakage_distance)`. 0.28.59 restored
the Meili defaults needed even by ordinary route initialization, from the
[pinned Valhalla configuration](https://github.com/valhalla/valhalla/blob/a919bb6b64d61d12f79ea5466d5cf599a39714ad/scripts/valhalla_build_config).
The same package subsequently imported and offline rerouting was confirmed by the operator.

That increment preserves remaining ordered ordinary/CNG stops, highway exclusion, departure
heading, voice maneuvers and dwell. Completed stops are omitted; a route crossing an existing
CNG plan's reserve is rejected. Prices/opening snapshots remain cached. Recovery uses the
existing navigation connectivity flow. Runtime initialization regressions are covered by
`tests/test_offline_routing_config.py` (optional native Linux check with `pyvalhalla==3.9.0`)
and `OfflineNativeInitializationTest` (Android instrumentation, device required).

## Architecture and compatibility

- `OfflineRouter` returns existing `RoutePreview` legs. `CompassNavigationRouteRecalculator`
  reuses the existing waypoint/leg-to-navigation converters; planning wraps the HTTP repository.
  The API client's GET/POST transport cancels its OkHttp call when the coroutine is cancelled,
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
  registered through an atomic catalog. A failed/cancelled import leaves the prior graphs.
  Startup reclaims incomplete imports left by process termination while preserving every registered package.
  SHA-256 detects corruption, not the publisher's authenticity: import only operator-built
  packages. Imported coverage bounds are a declared envelope, not a guarantee that every
  road/path exists; native failure keeps the current navigation route.
- Existing app/API authentication, networks, production datasets and refresh scheduler are
  untouched. NDK 29.0.14206865 is pinned for reproducible native symbol stripping during APK
  packaging; the dependency ships prebuilts, so no on-device build is performed.

## Build a separate package (operator)

Correction (2026-11-18): the previously documented `veneto-latest.osm.pbf` URL does not
exist (HTTP 404). [Geofabrik lists Nord-Est](https://download.geofabrik.de/europe/italy/nord-est.html)
as the available extract, with no regional subdivisions. Its PBF download was verified
with an HTTP HEAD request returning 200 after redirect. Bounds below enclose its `.poly`
extent. The earlier failed `offline-routing-build/veneto` directory is not reused; no
production volume is changed. This verifies the source URL, not completion of a graph build.

These commands are for the operator's server, from `~/docker/compass`, with Docker access,
Python 3.12+ and several GiB of free space. They download open Geofabrik data and run a
separate build; they do not mount or alter `compass_valhalla_datasets`. Use the Nord-Est
extract (including Veneto) for the initial Verona tests. The digest is pinned to the 3.9.0
scripted image.

```bash
mkdir -p offline-routing-build/nord-est
docker run --rm --name compass-offline-nord-est-build \
  --mount "type=bind,source=$PWD/offline-routing-build/nord-est,target=/custom_files" \
  -e tile_urls=https://download.geofabrik.de/europe/italy/nord-est-latest.osm.pbf \
  -e serve_tiles=False -e build_tar=True -e build_elevation=False \
  -e build_admins=True -e build_time_zones=True -e server_threads=2 \
  ghcr.io/valhalla/valhalla-scripted:3.9.0@sha256:89daaf61547167893cf58c03defd43c6eba0071dc965988c16a629260f8ac683 \
  build_tiles &&
python3 tools/export-offline-routing.py \
  --tiles offline-routing-build/nord-est/valhalla_tiles.tar \
  --output offline-routing-build/nord-est-offline.zip \
  --engine-version 3.9.0 --label "Nord-Est" --source-date "$(date +%F)" \
  --bounds 43.72 9.19 47.11 13.94 \
  --probe 45.438 10.992 45.414 11.044
```

The envelope bounds the published Nord-Est polygon; it does not guarantee road coverage
throughout the rectangle. Both probe endpoints must be routable within the extract.
Build completion and file sizes
should be checked before export. The exporter refuses an existing output file: choose a
new filename for updates. `source-date` records the operator-supplied snapshot/build date;
it is not a live freshness guarantee. Building Nord-Est is intentionally separate from the
server graph and can take time; do not start it concurrently with a resource-heavy nightly
build on a constrained host. Additional local space is needed for source data and archives.

Transfer `nord-est-offline.zip` to the phone's Downloads using your normal file-transfer
method. Install the versioned APK, open **Opzioni → Routing offline → Importa pacchetto
offline**, select the ZIP and remain on this screen until verification finishes. Expect
`Pacchetto verificato` and the package label/date. There is no public dataset URL to configure
and no backend redeploy/migration required for this increment.

Package structure: `manifest.json` first, then `tiles.tar`; no other entries. Schema version
1 carries engineVersion, label, sourceDate, bytes, SHA-256, bounds, two probe points and OSM
attribution. No zip paths are extracted directly. Limits: 16 KiB manifest, 8 GiB tar, available
storage checked before import. The old and new copies coexist until verification completes.

## Rerouting regression checks on FP4 / Android 15

1. Before importing, verify ordinary online routing and cached navigation still behave as
   before. Offline rerouting is unavailable without a package.
2. Import the Nord-Est package. Native initialization and the local probe must succeed. A
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
