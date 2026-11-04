# Android 0.28.53 — shared enforcement map packages

## Confirmed server prerequisite

The operator returned map_package HTTP 200 for graph 3.8.3:1793503103, four controls,
truncated=false and alert eligibility false. Relations 10048062 and 10048063 share device
osm:node:5739547477 but retain opposite forward flags and different directed edge IDs.
This accepts the package integration gate, not vehicle-approach accuracy.

## Delivered increment

Both the destination-free FollowMap and NavigationMap consume the authenticated package API
through the same app-scoped repository and existing HTTP connection/authentication configuration.
No new GPS listener or service is introduced. While the map lifecycle is STARTED, a five-second
consumer checks the most recent existing location; it ignores fixes older than 90 seconds or
accuracy worse than 100 m. This check interval is not a new API request interval.

Requests cover 3 km around the device. Refresh occurs after 1 km displacement or 15 minutes,
with at least 60 seconds between attempts, including failures. A mutex serializes consumers
across map transitions. Calls have a 12-second total timeout, 10-second read timeout and 2 MB
response limit. Parsing, validation and disk work run off the UI thread. No routing engine or
Overpass requests are made directly by Android; the backend package calls only its own DB and
Valhalla status. Screen disposal/background lifecycle cancels the consumer; an already running
blocking HTTP call remains bounded by the timeout.

One app-private atomic file retains the last package (at most 2.1 MB including metadata), outside
Android backup. No credentials or GPS trace are written. The package includes its geographic
center/extent. Cache ownership is a hash of server URL and username; changing profile suppresses
other profiles' data, including a change while a request is in flight. HTTP 401/403 clears the
cached area. The cache expires after 24 hours; a clock rollback also invalidates it. Corrupt or
incompatible cache entries are ignored. Failed writes do not block map presentation.

A successful response replaces the complete snapshot, never mixes graph versions, and preserves
all source records, string graph IDs, directional geometries, source dates and truncation.
Only map display deduplicates explicit physical aliases, separately for fixed/Tutor kinds;
opposite directional records remain intact in the cache. Conflicting reported speed limits
produce an unknown displayed limit, not an arbitrary selection.

MapLibre draws camera icons at zoom 11 and above with Velox/Tutor labels and reported limits
where available. They reload after style/theme changes. Tap an icon for its source date, limit,
OSM/ODbL attribution and current limitations. A small status badge distinguishes saved data,
partial coverage and unavailable data. It stays above the ETA/MapLibre attribution and moves up
with the navigation summary. In compact PiP the badge is hidden. Source acquisition time is not
represented as a live verification of an operational camera.

## Explicit remaining scope

These icons indicate inventory positions; they are NOT approach warnings. Direction-unknown and
unmatched records can be shown on the map but never speak or trigger a driving alert. No voice,
proximity alert, road-matching claim or Tutor average-speed calculation is enabled in this APK.
The cache supports displaying the last downloaded area without connectivity, not full-route
coverage or offline road-network matching. Moving outside it hides the old area; no markers does
not prove absence of enforcement. A long Tutor whose anchor is outside the downloaded radius
may be absent even if its road section crosses it. MIT integration is still an extension point.

The next coherent increment is vehicle-road evidence and warning logic (shared across both map
modes), with opposite/parallel roads, repeated alerts, route deviation and GPS gap regressions.
It must not turn inventory proximity or a single plausible road match into alert eligibility.

## Local and operator validation

Local command, from android, using the repository's installed toolchains:

    JAVA_HOME=/home/mike/toolchains/jdk17 ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk ./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug

Unit tests cover strict contracts, large integer IDs, opposite directions sharing a map icon,
conflicting limits, package replacement on graph changes, cadence/movement, offline retention,
expiry/clock rollback, profile changes, failed authentication, cancellation and bounded HTTP.
Local result: all 309 Android JVM tests passed, including 11 new enforcement tests; lintDebug
and assembleDebug passed. APK signatures were verified and match the previous 0.28.52 signing
certificate; versionCode is 99. Physical MapLibre rendering, device lifecycle and offline display
require operator verification.

The build publishes dist/compass-0.28.53.apk and dist/last.txt without a -debug suffix, using the
existing update workflow. Backend already validated at cb47209 is sufficient: no redeploy,
migration, import or association run is needed for this Android-only increment.

On the phone, accept the automatic update or open the versioned APK, retaining the existing
server profile. Use an area with known inventory controls:

1. Open the base map with GPS available, then navigate an A→B route through the same area.
   Zoom in to street level. Both maps should show camera icons; a reported limit must be absent
   when unknown. Tap a marker and the status badge to inspect acquisition date and attribution.
2. Change light/dark theme and enter/leave PiP. The layer must return without duplicate icons,
   broken camera follow or obscuring ETA/MapLibre controls.
3. After loading an area, disable data/Wi-Fi while retaining GPS. After restarting the app, saved
   markers should remain in that area; the badge must indicate saved data. Outside the area or
   after expiry no cached coverage is claimed. Restore connectivity; new data replaces the cache.
4. Standing still, no repeated requests every GPS update: refresh is normally 15 minutes. After
   roughly 1 km, a new area can be fetched, at most once per minute. No autovelox speech is expected.

Return screenshots from base and navigation mode, offline status and any UI/network failure.
If markers are unexpectedly missing, note whether the badge says unavailable/partial/saved and
whether zoom is at street level. On the server (~/docker/compass, existing .env credentials):

    docker compose logs --since 10m --tail 200 api | rg 'enforcement/map-package'
    docker compose exec -T api python3 < scripts/check-enforcement-associations.py

Return these outputs on failure. The agent does not claim device or live-network validation from
unit tests or a successful build.
