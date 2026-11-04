# Android 0.28.54 — visual and spoken camera approaches

## Scope and evidence

The operator confirmed that the two cameras displayed in the free-follow screenshot correspond
to real locations. This validates those displayed inventory points, not warning accuracy.
This increment removes the persistent “Autovelox · solo mappa” status badge. Camera markers,
tap details and OSM/ODbL attribution remain. Both map modes now show a temporary approach card
with road distance and reported speed limit, and speak one warning per passage when voice is enabled.
Unknown limits are omitted from speech and explicitly marked unavailable in the card.

Eligibility is intentionally narrower than displayed inventory: only current, matched,
single-OSM-way directional associations can produce an alert. The current import and graph must
agree with the stored proof. Unmatched/ambiguous controls and direction-unknown nodes remain map
icons. Do not infer that every visible camera will warn. Tutor alerts identify the **entry** of a
verified section; section tracking, exit handling and average-speed computation are not implemented
by this increment. MIT ingestion, multi-way associations and complete offline routing remain pending.

## Backend

Authenticated `POST /api/v1/enforcement/approach` accepts the existing bounded GPS-trace contract:
3–12 points, increasing relative timestamps, at most 10 seconds between samples, no implausible
jumps. Approach checks additionally require accuracy within 12 m and at least 20 m displacement.
Stationary/poor fixes return `uncertain` before routing calls.

The API brackets processing with Valhalla graph identity checks, uses `trace_attributes` for
directed vehicle-road matching and verbose `locate` to inspect alternative roads. Heading-compatible
roads within max(10 m, twice reported accuracy) must agree on the matched OSM way and forward flag;
the matched edge must be present. Missing/incomplete evidence suppresses the alert. This conservative
check reduces parallel/opposite-road false positives; it is not a field-validated guarantee.

One spatial DB query selects at most 100 current controls within 1.5 km on that directed way.
The full imported source-way geometry extends the short matched control zone upstream. Distance
is measured along that shape, up to 1.2 km; fixed devices must project within 50 m of their verified
zone, while Tutor targets use its from-node. Duplicate-node loops are rejected. Conflicting limits
for the same physical device produce an unknown limit. The nearest eligible control is returned
with its short approach geometry. No import, write, new table, external provider or route-ETA
change occurs. The API returns `approach`, `none` or `uncertain`; failures use a generic 503 code.
Logs contain outcome/reason, never GPS traces, credentials or request bodies.

## Android

The app-scoped coordinator reuses existing locations and inventory cache. No extra GPS listener,
foreground service, dependency or permission is added. The existing navigation foreground service
updates it during guidance, including background navigation; free follow updates it only while
the map lifecycle is started. Six samples at least 10 m apart form a short in-memory trace.

Requests are limited to once per five seconds, with only one in flight, and only near a known
matched control with usable movement. Failure backoff doubles up to 60 seconds. GPS age >5 seconds,
accuracy >12 m, gaps >10 seconds, jumps, departure from the geometry, opposite direction, rerouting,
non-driving routes or a changed server/graph invalidate the approach. Delayed responses cannot
restore invalidated state. Active guidance additionally checks that the approach agrees with a
short window of the remaining planned route, including 150 m behind to accommodate request latency.

Prewarning distance is `clamp(speed_m_s * 15, 100, 600)`; stopped vehicles do not announce. The card
updates locally as GPS advances and disappears on passing/deviation/invalid evidence. A physical
control + way + direction receives one announcement per passage, independently of route IDs or
API refreshes. Muted passages are also consumed. Rearming requires at least two minutes and 1.5 km
separation; in-memory deduplication is bounded to 128 entries and does not survive process death.

Speech uses the existing Italian TTS/system media volume and transient ducking. During navigation
it shares the maneuver TTS instance; free follow owns a lifecycle-bound instance and respects the
voice default. The visual card is independent of mute. Actual audio focus/device behavior must be
tested on a phone.

During a transient network failure only the last verified in-memory approach can continue, for at
most 60 seconds and with uninterrupted reliable GPS on its geometry. An explicit negative response,
auth error or geometry/graph change clears it. Saved inventory alone cannot authorize a warning;
there is no fresh offline matching, full-route offline coverage or restart-persistent approach.

## Local validation and delivery

From repository root:

```bash
.venv/bin/ruff check src tests scripts
.venv/bin/pytest -q -m 'not integration'
.venv/bin/python scripts/export-openapi.py --check
JAVA_HOME=/home/mike/toolchains/jdk17 ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
  android/gradlew -p android --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug
```

Backend fixtures exercise directed along-road distance, reverse travel, Tutor entry, parallel
competitors, missing geometry, current-source SQL filtering and graph changes. Android tests cover
free-follow/guidance consumers, cadence, mute, deduplication, route changes, stale/inaccurate GPS,
wrong direction, offline continuation and stale responses after server/mode changes.
Local result: 456 Python tests passed, six DB integration tests excluded; all 323 Android JVM
tests passed. Ruff, OpenAPI synchronization, lintDebug and assembleDebug passed. APK verification
confirmed versionCode 100 and the same signing certificate as 0.28.53. No live Valhalla, DB or
physical-device approach/audio test was executed by the agent.

The artifact is `dist/compass-0.28.54.apk`, versionCode 100; Gradle updated `dist/last.txt`.

## Operator handoff

On the live server in `~/docker/compass`, with the existing `.env`, DB, Valhalla and previously
imported/associated controls:

```bash
git pull --ff-only
docker compose build api
docker compose up -d --no-deps api
docker compose exec -T api python3 < scripts/check-enforcement-approach.py
```

No new migration/import is required for this change. The diagnostic uses synthetic traces drawn
from stored source ways and only the local API/Valhalla: `stationary` must return HTTP 200 with
`uncertain`, invalid timestamps HTTP 422. Moving samples may return `approach`, `none` or `uncertain`
with an explicit reason. Return the entire compact output, especially if no sample is `approach`.
Synthetic traces do not certify actual vehicle behavior. Stale graph associations require the
existing graph-specific association job, not relaxed eligibility.

Install 0.28.54 through the normal APK update prompt. Verify from a passenger position:

1. Base map: no persistent autovelox status label; camera icons and tap details remain.
2. Approach a **matched** camera on its carriageway with voice enabled: one spoken warning and
   a card with decreasing distance, a limit only if known. Repeat with A→B guidance; maneuver
   speech remains available and ETA is unchanged.
3. On the opposite carriageway/parallel street, or after turning away, no warning for that control.
   A route refresh must not repeat the same passage announcement. Muting suppresses speech only.
4. After an online approach has been verified, temporarily disconnect data while retaining GPS:
   that short corridor may continue; after expiry/GPS interruption no new warning is claimed.
5. Check light/dark theme and PiP for card visibility and unobstructed essential controls.

If alerts are absent or misplaced, return the camera source key from association diagnostics,
mode (base/guidance), direction, APK version and these logs; do not post credentials:

```bash
docker compose logs --since 10m --tail 300 api | rg 'approach|road-context'
docker compose exec -T api python3 < scripts/check-enforcement-associations.py
```

Next gate is the operator's actual approach/audio test. Local fixtures/build success do not prove
live Valhalla matching, phone audio or absence of false positives on real carriageways.
