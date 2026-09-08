# Compass

Compass is an open-source navigation system in development for fuel-aware CNG/metano routing in
Italy. The product target is route planning and navigation with dynamically inserted, reachable,
arrival-time-aware refuelling stops—not a generic fuel-station map.

This repository implements the accepted **Phases 0–13** foundation:

- a Python/FastAPI service with liveness and database-readiness endpoints;
- a PostgreSQL/PostGIS Docker Compose foundation and Alembic migration path;
- one-shot acquisition/import jobs for the official MIMIT active-station and price datasets;
- one-shot OSM CNG enrichment acquisition through Overpass;
- immutable raw payload retention, source timestamps, ingestion timestamps, provenance and metrics;
- content-hash idempotency and checked-in parser fixtures;
- a MIMIT-anchored normalized CNG station model using PostGIS geography points and GiST indexes;
- semantic CNG price history plus explicit current-price pointers;
- versioned, deterministic MIMIT-to-OSM reconciliation with matched, ambiguous and unmatched states;
- auditable candidates, confidence/method values and operator-controlled manual overrides.
- a version/digest-pinned Valhalla tile bootstrap and internal routing runtime;
- a provider-neutral async routing boundary with fixture-tested Valhalla translation;
- a strict `POST /api/v1/routes` A-to-B contract returning metres, seconds, polyline6 geometry and
  maneuvers;
- dependency-specific liveness/readiness state and stable routing error codes.
- validated polyline6-to-PostGIS route geometry conversion;
- configurable autonomy-aware CNG corridors using the 20%-of-effective-range policy and caps;
- GiST-compatible spatial candidate pruning with explainable before/after metrics.
- provider-neutral one-to-many and many-to-one road-cost matrices;
- bounded Valhalla matrix batching over only the spatially pruned candidates;
- strict maximum-detour eligibility with road distance, two-leg costs and offset-aware ETAs;
- explicit no-traffic cost metadata and matrix/per-candidate routing metrics.
- OSM `opening_hours` evaluation at each station ETA in the explicit `Europe/Rome` timezone;
- distinct `open`, `closed` and `unknown` availability with missing/invalid validation state;
- deterministic ranking from detour, availability, MIMIT CNG unit price and price freshness;
- explicit score components, price/source timestamps and at-most-one-query candidate enrichment.
- stable station detail keyed by the official MIMIT identifier;
- selected-stop route recomputation with two explicit polyline6/maneuver legs;
- source-by-source freshness metadata and data-aware readiness;
- a checked-in OpenAPI artifact, shared machine error envelope and live contract verifier.
- a native Kotlin/Jetpack Compose Android application with data/domain/UI separation;
- a strict Phase 7 API client, validated polyline6 decoder and lifecycle-aware route state;
- a MapLibre route preview with endpoint layers, route summary and backend maneuvers;
- pinned Android/Gradle dependencies, JVM tests, lint/APK validation and an executable device gate.
- an Android `Aggiungi tappa → Metano` form with effective-range and maximum-detour policy inputs;
- arrival-aware ranked CNG markers/cards with road distance, ETA, hours, phone, price freshness and
  explainable score components;
- selected-stop Valhalla route recomputation by official MIMIT ID with a visible CNG waypoint and
  two preserved maneuver legs;
- strict mobile workflow state, contract tests and an executable Phase 9 API/device gate.
- a strict predictive API using caller-estimated remaining range and an explicit safety reserve;
- persistent Android vehicle profiles for per-vehicle CNG/gasoline full-range and reserve defaults;
- an explicit gasoline direct-route fallback, used only when complete CNG planning fails and both
  fuel reserves remain protected;
- complete road-network refuelling-chain search with explicit suggestion/no-suggestion states, a
  full-refill assumption and an optimized destination-reachable fast path;
- an independently range-validated multi-waypoint route through ordered official MIMIT IDs;
- an Android predictive form whose remaining-range input starts empty, ordered stop/leg reserve
  margins and an executable Phase 10 API/device gate.
- route-free Android startup that follows the live GPS position without inventing a destination,
  plus a wrapping origin/destination selector for current position, search, future favourites and
  explicit coordinates;
- a provider-independent live-traffic subsystem with mock fixtures, a TomTom base Traffic Flow API
  adapter, OpenLR direction verification, Valhalla directed-edge matching and time-dependent route
  requests;
- a hardened on-demand Docker updater with transactional native `traffic.tar` writes, explicit
  expiry to `UNKNOWN`, tileset-bound durable state and API-readable runtime health;
- a server-backed `NavigationRoute` plus an application-scoped Android foreground navigation
  session with local GPS filtering, route snapping, progress/ETA and MapLibre follow rendering;
- staged Italian TextToSpeech guidance, dynamic navigation camera, robust off-route confirmation,
  five-minute traffic refresh and hot route replacement through Compass with offline continuity.
- explicit in-navigation CNG stop skip/replacement with server-acknowledged station exclusion,
  predictive range/reserve preservation and a safe keep-current-route fallback, accepted on a
  physical Android device.
- a provider-neutral server-side place-search API for Italian addresses, localities, POIs and raw
  coordinates, consumed by Android without direct provider coupling;
- Android current-location origin selection and named destination results feeding the same Compass
  A-to-B routing contract;
- configurable CNG waypoint dwell (20 minutes by default), kept separate from driving cost and
  accumulated in stop/destination ETA, opening-hours chronology and total journey time;
- rerouting that preserves still-valid planned CNG stops and automatically requests a replacement
  predictive plan when the existing stop chain becomes invalid.
- a versioned device cache for active route geometry, maneuvers, timing, CNG waypoints/range policy
  and optional gasoline fallback, restored explicitly after process death;
- bounded exact-query recent destination caching with visible cache timestamps and no silent
  fallback for malformed input/response data;
- explicit downloaded-route, rerouting-unavailable, traffic-unavailable and cached-CNG UI states;
- configurable MapLibre ambient caching for resources already visited and live-route recovery after
  Compass connectivity returns.

It intentionally does **not** yet provide nationwide traffic coverage from the point-based TomTom
base API, independent LRP-to-GraphId resolution, historical traffic ingestion, vehicle telemetry,
automatic vehicle tank telemetry or automatic inference that a station is unexpectedly closed.

## Quick local validation

Python 3.12+ and Docker with Compose are required.

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/ruff check .
.venv/bin/pytest
docker compose config --quiet
docker compose build api
```

Start the database, migration job and health API:

```bash
cp .env.example .env
# Change POSTGRES_PASSWORD and the matching password inside DATABASE_URL.
docker compose build api
docker compose up -d db migrate api
curl --fail http://127.0.0.1:8000/health/live
```

The service binds to loopback by default. Public exposure is deliberately outside this repository's
current scope. Readiness requires Valhalla after Phase 3; follow the routing bootstrap below before
expecting `/health/ready` to return 200.

## Source imports and Phase 2 normalization

Set `HTTP_USER_AGENT` in `.env` to identify the operator and provide a contact, then run:

```bash
docker compose --profile jobs run --rm etl mimit
docker compose --profile jobs run --rm etl osm
docker compose --profile jobs run --rm etl normalize
```

Each command prints one JSON object. `metrics` contains the input and retained record counts;
`source_observed_at` is distinct from the database ingestion time. Repeating a command while the
upstream payload is unchanged prints `"reused": true` and does not add parsed records.
Repeating `normalize` against the same two source runs, reconciliation policy and overrides likewise
returns `"reused": true`. Its metrics expose matched, ambiguous and unmatched station counts.

See [deployment and live validation](docs/deployment.md),
[architecture](docs/architecture.md), [data sources](docs/data-sources.md), and
[live traffic architecture](docs/traffic.md).

## Phase 3 base routing

Build Italy tiles once into the named volume, then start the internal router and API:

```bash
docker compose --profile routing-build run --rm valhalla-tiles
docker compose --profile routing up -d db migrate valhalla api
curl --fail http://127.0.0.1:8000/health/ready
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":45.4857,"longitude":9.2045}}' \
  http://127.0.0.1:8000/api/v1/routes
```

The first full-Italy build is a substantial operator task. Image pulls, resource expectations,
regional overrides, rollback-safe graph updates, exact acceptance invariants and diagnostics are documented in
[deployment and live validation](docs/deployment.md#phase-3-valhalla-bootstrap-and-base-route-validation).

## Phase 4 spatial candidate pruning

With normalized stations and routing available, request a base route plus a spatial CNG corridor:

```bash
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300}' \
  http://127.0.0.1:8000/api/v1/cng/corridor-candidates
```

The default 300 km effective range produces an uncapped 60 km radius and applies the configured
50 km maximum. Response metrics distinguish the all-Italy station inventory from geocoded,
in-corridor and returned candidates. Candidate distance is a straight-line spatial prefilter value,
not road distance or detour. See the
[Phase 4 live gate](docs/deployment.md#phase-4-autonomy-aware-corridor-validation).

## Phase 5 road-network detour eligibility

Evaluate only the returned corridor candidates against a user-defined detour maximum:

```bash
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-28T08:00:00+02:00"}' \
  http://127.0.0.1:8000/api/v1/cng/detour-candidates
```

Results include both road legs, road distance from the origin/previous waypoint, total and extra
time/distance, station/destination ETAs and eligibility metrics. The current graph has no external
traffic feed, so the response explicitly reports `traffic_aware=false`. See the
[Phase 5 live gate](docs/deployment.md#phase-5-batched-network-detour-validation).

## Phase 6 arrival-time availability and ranking

Request ranked CNG candidates after the same spatial and road-network gates:

```bash
curl --fail -H 'Content-Type: application/json' \
  -d '{"origin":{"latitude":45.4642,"longitude":9.1900},"destination":{"latitude":44.4949,"longitude":11.3426},"effective_cng_range_km":300,"maximum_detour_minutes":10,"departure_at":"2026-08-30T10:00:00+02:00"}' \
  http://127.0.0.1:8000/api/v1/cng/ranked-candidates
```

Closed-at-ETA stations are excluded by default; `"include_closed":true` retains them with an
explicit zero opening score and configurable score multiplier. Missing or invalid hours remain
`unknown`, not open. Prices remain optional and expose MIMIT observation/ingestion timestamps plus
freshness at station ETA. See the
[Phase 6 live gate](docs/deployment.md#phase-6-arrival-time-availability-and-ranking-validation).

## Phase 7 public API contract

Phase 7 makes the completed routing/ranking domain usable through stable mobile-facing resources:

- `GET /api/v1/cng/stations/{mimit_station_id}` returns authoritative station fields, current CNG
  prices with freshness, accepted OSM enrichment and optional opening evaluation at `arrival_at`;
- `POST /api/v1/routes/with-cng-stop` resolves the official station ID and returns exactly two route
  legs with independent polyline6 geometry and maneuvers;
- `GET /api/v1/data-freshness` reports MIMIT, OSM and reconciliation ages/thresholds without
  pretending that traffic is configured;
- `/health/ready` distinguishes ready, degraded-but-usable data and missing required data.

The canonical machine-readable contract is [docs/openapi.json](docs/openapi.json), with compact
[request/response examples](docs/api.md). Regenerate or
verify it with `python scripts/export-openapi.py` and `python scripts/export-openapi.py --check`.
For external mobile access, enable environment-backed Basic Auth and terminate HTTPS at the
operator-managed ingress; Android persists the endpoint, username and a Keystore-encrypted password
without rebuilding the APK. See
[external API deployment](docs/deployment.md#external-api-access-and-authentication).
See the [Phase 7 live gate](docs/deployment.md#phase-7-public-api-contract-validation). After the
image and services are restarted, the checked-in `scripts/run-phase7-live.sh` runner executes the
complete gate without requiring inline JSON or chained shell commands.

## Phase 8 Android client foundation

The native app in `android/` consumes `POST /api/v1/routes` and displays the fixed accepted
Milan-to-Bologna preview: MapLibre road geometry and endpoints, distance, duration, provider and a
scrollable maneuver list. HTTP DTOs, domain route models and Compose state are separate. Phase 8
deliberately stopped at that boundary; Phase 9 extends it with the CNG workflow described below.

Repository-local Android validation uses the checked-in Gradle wrapper:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
cd ..
```

The reusable physical-device procedure is documented in
[Android client development](docs/android.md) and automated by `scripts/run-phase8-live.sh`.
The completed operator-run gate and device evidence are recorded in the
[Phase 8 acceptance record](docs/phases/phase-8-acceptance.md).

## Phase 9 Android Add CNG Stop

The Android app now connects the accepted ranking and selected-stop APIs into the manual core flow:

1. open `Aggiungi tappa` from the Milan-to-Bologna preview;
2. choose the Metano form and provide maximum detour plus effective CNG range;
3. inspect ranked route markers and station cards with detour, road distance, ETA, opening state,
   hours, phone, price freshness and score explanation;
4. select a station by official MIMIT ID;
5. inspect the recalculated route, CNG waypoint and two maneuver sections.

Repository-local tests, lint and APK assembly passed, followed by the accepted full-Italy
API/physical-device gate. The same operator-run gate remains reproducible with one command:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
bash scripts/run-phase9-live.sh
```

See the [Phase 9 acceptance record](docs/phases/phase-9-acceptance.md) for the exact local and live
evidence plus the reusable device checklist.

## Phase 10 predictive CNG reachability

Phase 10 adds the predictive `Crea viaggio` flow. The driver supplies remaining range, reserve,
effective full range and maximum detour; no tank value is invented or presented as telemetry. The
backend returns one of `not_needed`, `suggested`, `no_reachable_station`, `no_eligible_station` or
`no_complete_itinerary`. A suggestion is a complete ordered
origin→refuelling-stop(s)→destination plan; returning only a reachable first stop is explicitly not
success. The first road leg preserves reserve using the supplied remaining range, and every later
leg preserves reserve assuming a full refill to the effective range.

If the destination is already reachable with reserve, the backend uses one base-route request and
skips station queries, matrices and enrichment. Android exposes the assumptions and range arithmetic,
shows remaining range/reserve margin for every planned leg, and sends the ordered official IDs to a
multi-waypoint route endpoint that revalidates Valhalla's actual leg distances before display.

Repository-local validation and the operator gate are documented in the
[Phase 10 acceptance record](docs/phases/phase-10-acceptance.md). The accepted regression gate is
reproducible with `bash scripts/run-phase10-live.sh` after the synchronized server image is rebuilt.

## Phase 11 Android route endpoint editing

Phase 11 removes the remaining fixed-route limitation from the Android planner. The app still starts
from the accepted Milan-to-Bologna default, but the preview now has a `Modifica percorso` form for
explicit origin/destination latitude and longitude. Applying new coordinates recalculates the base
route and clears stale route-dependent CNG state. Manual Metano search, selected-stop routing and
predictive CNG planning then use the active route returned by the backend.

This increment deliberately does not add address search, current-location permissions, saved places
or geocoding. The accepted live/device gate is automated by `scripts/run-phase11-live.sh`.

## Phase 12 destination search and navigation completion

Android can use the current device location as origin and search a destination by address, city,
POI/business name or coordinates. It calls `GET /api/v1/places/search`; only Compass knows the
configured geocoder. The acquired GPS/network origin is shown in the coordinate form before the
user applies it. Selecting a normalized result requests the final route through the existing
backend contract and hands its geometry and Valhalla maneuvers to the established foreground
navigation session; zero-cost provider results are rejected as non-navigable.

`GEOCODING_PROVIDER=nominatim_google` optionally compares Nominatim candidates with ephemeral
Google Places API (New) candidates. Query-token coherence orders POIs; address comparison uses
structured street, locality, exact civic and a configurable 75 m proximity bound. The Android hint
defines the unambiguous Italian civic syntax as `Via Cappafredda, 12, Roverchiara`. Google payloads
are discarded before the API boundary and the resulting order is marked non-cacheable.

Route timing now separates driving duration, an explicit nullable traffic-delay component,
refuelling dwell and total journey duration. `CNG_REFUEL_DWELL_SECONDS` defaults to 1,200 seconds.
Predictive stop ETAs and opening-hours checks include all earlier dwell, while detour road cost does
not. The accepted Phase 12 live gate is `bash scripts/run-phase12-live.sh`.

## Phase 13 offline and degraded navigation

Android persists the active route, geometry, maneuvers, timing and CNG plan in versioned private
storage. A process restart restores a visibly cached preview; an explicit navigation stop clears it.
Local GPS matching continues on a downloaded route when Compass is unreachable, while the UI
separately identifies unavailable rerouting, unavailable traffic and cached CNG data. Ten recent
exact-query search result sets can be used after network/server failure with cache provenance shown.
MapLibre's bounded ambient cache retains already visited resources without promising full regional
offline coverage. The operator accepted the device gate on 2026-09-03; its runner remains available
as `bash scripts/run-phase13-live.sh`.

## Android 0.19.4 Google destination search

The active destination flow now uses Google Places API (New) Autocomplete and resolves only the
selected Place ID through Details. Google is the sole destination provider for this test increment;
Text Search, legacy geocoding and automatic provider fallback are disabled. TomTom traffic remains
independent. Search text and addresses stay on mapless screens, while MapLibre and Valhalla receive
only WGS84 coordinates, the permitted Place ID and the neutral label `Destinazione selezionata`.

Live enablement is fail-closed until the operator verifies the Cloud billing-account regime and sets
`GOOGLE_PLACES_CONTRACT_REGIME=eea`. See [ADR 0021](docs/adr/0021-google-places-new-destination-boundary.md)
and the [0.19.4 live gate](docs/phases/android-0.19.4-google-destination-search-acceptance.md),
accepted on the live backend and a physical device on 2026-09-07. The unavailable active-plan
destination-edit case is recorded there as explicitly waived, not as a successful device test.

## Navigation UI upgrade

Navigation UI Phase 1 was accepted on-device on 2026-09-03. During active guidance MapLibre fills
the screen behind a compact maneuver card and trip/CNG bottom overlay. Detailed route, status and
CNG actions move into an expandable sheet; raw diagnostics and simulation controls are isolated in
a debug-build-only developer screen. Routing, map matching, camera behavior, offline recovery and
CNG planning remain unchanged. Android version is `0.11.0` (`versionCode=12`). See
`docs/phases/navigation-ui-phase-1-acceptance.md`.

Navigation UI Phase 2 was accepted on-device on 2026-09-04. The existing Compass camera policy now
uses a centralized configuration, a forward target and heading
derived from the remaining matched route, continuous speed/maneuver-aware pitch and zoom, and eased
MapLibre transitions. Manual pan/rotate/zoom suspends follow until `Ricentra` or ten seconds of
inactivity; overview is north-up and bounded to the remaining route. The vehicle stays vertical in
heading-up follow, and bounded zoom now responds to both speed and the spacing of consecutive
maneuvers. The development map now uses
an OpenFreeMap road style so streets remain visible beneath the route and name layers prefer
Italian. Active navigation suppresses generic basemap POIs while retaining available fuel/charging,
toll, border and traffic-control infrastructure. A directional vehicle replaces the point, CNG
uses dedicated markers, manual camera mode returns to follow after ten idle seconds, and the trip
summary is available through a compact toggle instead of permanently covering the map. The final
Compass day/night styling was reserved here and is delivered by Phase 5. No external navigation SDK was added. Android version is `0.12.0`
(`versionCode=13`). See `docs/phases/navigation-ui-phase-2-acceptance.md`.

Navigation UI Phase 3 was accepted on-device on 2026-09-05. Raw Android fixes now pass through
explicit validation/smoothing and the existing route matcher into one authoritative
`NavigationPosition`. Low-speed position and heading noise are held, delayed/inaccurate/implausible
fixes are rejected, and route-matched heading is stabilized independently from raw GPS bearing.
MapLibre animates coordinate and shortest-path rotation between accepted matched positions while
snapping only initial or large discontinuous transitions. A Valhalla traffic-aware no-path now
receives one logged graph-speed retry, preserving navigation availability when time-dependent
search reaches its convergence bound. The accepted gate confirmed the exact route preflight,
continuous matched movement, density-aware urban zoom, free-camera puck motion, developer pipeline
diagnostics, follow recovery and clean teardown. Android version is `0.13.0` (`versionCode=14`).
The repeatable gate remains `bash scripts/run-navigation-ui-phase3-live.sh`; see
`docs/phases/navigation-ui-phase-3-acceptance.md`.

Navigation UI Phase 4 was accepted by the operator on 2026-09-05. Current
routes now use an explicit local `depart_at` instant with Valhalla's prioritized bidirectional
search, so the native `traffic.tar` overlay remains active without the long-route convergence
failure seen during Phase 3. Each successful traffic route is compared with a separate graph-speed
baseline; the API and Android client expose the resulting non-negative delay, provider freshness
and observation time without inventing a value after fallback. The accepted server gate used fresh
TomTom data and a tileset-bound native overlay; returned device evidence showed live traffic in the
base preview and CNG navigation before and after refresh. Preview, CNG itinerary and active route
refresh all share this contract. The gate rejects disabled, stale or fallback traffic. See
`docs/phases/navigation-ui-phase-4-acceptance.md`.

Navigation UI Phase 5 was accepted on-device on 2026-09-05. Compass now ships small,
purpose-built MapLibre day and night styles with flat buildings, a restrained road hierarchy,
Italian-first labels and no generic POI layer. The map follows the system theme while preserving
independent HTTPS/self-hosted deployment overrides; the former single-style property remains a
backwards-compatible override for both modes. Route, travelled line, endpoints, vehicle and CNG
markers use theme-specific contrast palettes, and diagnostic logs never include a configured style
URL. The accepted gate retained four CNG stops and continuous puck motion across live
day/night/day changes, then removed the service and active notification cleanly. Android version is
`0.15.0` (`versionCode=16`). Run
`bash scripts/run-navigation-ui-phase5-live.sh`; see
`docs/phases/navigation-ui-phase-5-acceptance.md`.

Navigation UI Phase 6, accepted on-device on 2026-09-06, replaces the provisional font-arrow
maneuver symbols with density-independent
Compose vectors for every published Valhalla type 0–36. Current and following maneuvers have
independent icons; turn angle/side, junction branches, merge, roundabout, ferry and transit states
are explicit, theme-aware and accessibility-labelled. A debug-only catalog makes rare families
inspectable without synthetic production routes. Android version is `0.16.0` (`versionCode=17`).
Run `bash scripts/run-navigation-ui-phase6-live.sh`; see
`docs/phases/navigation-ui-phase-6-acceptance.md`.

Navigation UI Phase 7, accepted on backend and physical device on 2026-09-06, carries Valhalla's
structured exit numbers, road branches, toward/name sign elements and roundabout exit count through
the strict API, Android client and offline route cache.
The active overlay shows a compact provider-backed sign and roundabout exit badge only when those
fields exist; it never parses localized instructions to invent missing guidance. A debug-only
gallery covers rare signage layouts. Android version is `0.17.0` (`versionCode=18`). Run
`bash scripts/run-navigation-ui-phase7-live.sh`; see
`docs/phases/navigation-ui-phase-7-acceptance.md`.

Navigation UI Phase 8, accepted on the live backend and physical device on 2026-09-06, adds
source-backed speed-limit context. Compass asks Valhalla
`/trace_attributes` for ordered graph-edge limits and publishes their polyline6 shape-index ranges;
Android selects the current limit only from its matched route segment and renders a compact Italian
regulatory badge. Missing, unlimited or unavailable graph data remains absent and never becomes an
invented numeric limit. Lane-level guidance remains outside this phase because the current route
contract has no authoritative lane assignment. Follow-mode pinch zoom keeps the puck anchored one
quarter of the viewport above the bottom, and the camera follows the puck's exact interpolated pose;
deliberate panning still enters temporary free mode. Trip controls live inside their compact panel,
using centered vector chevrons; the panel's measured height becomes camera padding so the puck stays
at 75% of the unobscured map above it. The details sheet grows only as far as its content requires.
Android version is `0.18.0` (`versionCode=19`). See
`docs/phases/navigation-ui-phase-8-acceptance.md`.

Navigation UI Phase 9, accepted on the live backend and physical device on 2026-09-06, adds a local
visual speed-compliance state without changing route semantics. It compares only the filtered
matched speed with the Phase 8 graph-backed limit, enters warning at five km/h above the limit and
clears at two km/h above it to prevent flicker. These are UI stability buffers, not legal
tolerances. The regulatory sign retains its white/red face and gains a second bright-red ring, red
number and Italian accessibility description while over limit. Missing inputs remain explicitly
unavailable, and no sound or vibration is emitted. Android version is `0.19.0` (`versionCode=20`).
See `docs/phases/navigation-ui-phase-9-acceptance.md`.

Navigation UI Phase 10 refines the existing off-route state machine with a minimum episode duration,
stationary-drift suppression and recovery hysteresis. Suspected/confirmed matches no longer advance
the old route's maneuver, ETA or CNG progress. Confirmed deviations still re-enter Compass using the
raw GPS fix and preserve or safely replan the remaining fuel plan; network failure retains local
guidance. While any replacement is pending, the driving surface shows an accessible spinner with
`Ricalcolo rotta`. Android version is `0.20.0` (`versionCode=25`). The physical-device gate is
accepted as of 2026-09-08 and documented in
[the Phase 10 acceptance record](docs/phases/navigation-ui-phase-10-acceptance.md).

Android patch `0.19.1` (`versionCode=21`) incorporates the first post-Phase-9 road-test correction:
when a structured junction sign already names the maneuver road, Compass suppresses the redundant
road subtitle while preserving the instruction and sign. The green sign is centered and sizes to
its content plus padding instead of always spanning the maneuver card.

Android patch `0.19.2` (`versionCode=22`), accepted in the operator's physical-device road test
on 2026-09-06, aligns guidance with Valhalla's maneuver-begin semantics. After a transition is
crossed, the card, voice controller, approach state and camera now advance to the next maneuver
instead of retaining the completed maneuver while displaying distance to its end. See the
[`0.19.2` road-test acceptance record](docs/phases/android-0.19.2-road-test-acceptance.md).

Android `0.19.3` (`versionCode=23`) strengthens automatic off-route recovery without rendering raw
GPS as on-route truth. Three consecutive accepted fixes are still required, but lateral deviation
and a moving heading conflict now use tighter accuracy-aware thresholds. A confirmed episode
re-enters Compass from the raw GPS coordinate; after success, a ten-second bottom overlay shows the
remaining duration before and after the alternative plus the signed difference. This server-backed
operation requires an API URL reachable by the phone after it is disconnected from ADB. See the
[`0.19.3` acceptance record](docs/phases/android-0.19.3-off-route-rerouting-acceptance.md).

The final `0.19.3` startup increment removes the automatic Milan-to-Bologna request. A fresh app
session opens on a route-free GPS-follow map: there is no `Panoramica` action because no route
exists, and `Crea viaggio` opens the origin/destination selector. Each endpoint offers adaptive,
wrapping choices for current position, a disabled saved-positions placeholder, the existing place
search and manual coordinates; current position is first for departure and last for destination.
The selector now opens without a redundant header/card and unlocks direct routing, one-stop or
extended planning only after a successful calculation. Extended planning supports saved vehicle
profiles or custom values while always asking for remaining range and maximum detour. Server setup
opens automatically when credentials are absent or routing/search reports connection/auth failure.
See the [route-free follow acceptance gate](docs/phases/android-0.19.3-route-free-follow-acceptance.md).

## Repository layout

```text
src/compass/api/       health-level FastAPI scaffolding
src/compass/etl/       source acquisition, parsing and raw ingestion
src/compass/normalization/ normalized source values and coordinate validation
src/compass/reconciliation/ deterministic source matching and manual overrides
src/compass/routing/   provider boundary and Valhalla HTTP adapter
src/compass/candidates/ corridor policy, geometry decoding and PostGIS pruning
src/compass/detours/  batched network-cost evaluation and deterministic detour math
src/compass/ranking/  arrival-time opening evaluation, price freshness and explainable ranking
src/compass/predictive/ reserve-aware road reachability and predictive suggestion states
src/compass/stations/ public station detail reads and provenance
src/compass/search/   provider-neutral search, Nominatim output and optional Google corroboration
src/compass/freshness/ ingestion/reconciliation freshness policy
android/               native Kotlin/Compose/MapLibre device client
migrations/            Alembic schema history
tests/fixtures/        small network-free source fixtures
scripts/               reproducible local/live validation runners
docs/adr/              accepted architecture decisions
compose.yaml           reference server-side deployment
```

## Project status and licensing

Phases 2–13 have passed repository-local checks and their documented operator-run live or device
tests.
Phase 3 validated the digest-pinned Valhalla 3.8.3 runtime against a full Italy graph and a
representative Milan A-to-B API route. Phase 4 validated autonomy-aware PostGIS corridor pruning on
a Milan-to-Bologna route against the full 1,512-station inventory. Phase 5 validated batched
road-network detour eligibility over 200 pruned candidates using one base route and ten Valhalla
matrix calls, with an independent known-station route comparison. Evidence is recorded in
`docs/phases/phase-2-acceptance.md`, `docs/phases/phase-3-acceptance.md` and
`docs/phases/phase-4-acceptance.md`, with the Phase 5 and Phase 6 gates in
`docs/phases/phase-5-acceptance.md` and `docs/phases/phase-6-acceptance.md`. Phase 6 validated
arrival-time opening states and explainable ranking over the same full-Italy bounded matrix pipeline,
including the real-world `Su, PH off` Sunday case.
Phase 7 validated the stable public API, official-ID station detail, two-leg selected-stop routing,
data-aware health/freshness, shared 404/422 errors and the published OpenAPI contract through the
checked-in live-gate runner.
Phase 8 passed repository-local JVM tests, Android lint and debug APK assembly. Its executable
handoff then preflighted the real backend, built and installed the APK, launched it successfully on
the operator's device, and produced the accepted MapLibre Milan-to-Bologna route preview with
endpoint markers, Valhalla summary and maneuvers.
Phase 9 passed 17 repository-local JVM tests, Android lint and debug APK assembly. The operator then
validated the real full-Italy API/device flow: 16 eligible ranked stations, explainable station
cards and a selected ARDA OVEST route with an explicit CNG waypoint and two maneuver sections.
Phase 10 passed the corrected full-Italy/device gate. The mandatory 65 km remaining, 30 km reserve
and 100 km full-range regression now produces a complete three-stop refuelling chain, validates the
actual multi-waypoint route with reserve preserved on every leg, and renders on device without
system-bar overlap.
Phase 11 passed after explicit Android route-coordinate editing was validated on device: the edited
Rome-to-Florence route preview, manual CNG search, selected-stop route, predictive CNG flow and
invalid-coordinate guard all behaved as expected, with generic destination labels on edited routes.
Phase 12 was accepted on 2026-09-03 after the operator returned normalized address/POI search,
positive A-to-B route and guidance, cumulative CNG dwell, preserved/replanned fuel stops and
foreground-notification evidence from the physical-device gate.

Compass source code is licensed under the
[GNU General Public License version 3 only](LICENSE). Source datasets retain their own licenses;
see `docs/data-sources.md`.
