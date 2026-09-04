# Architecture overview

## Boundaries

Compass uses six explicit server/domain boundaries and one first-class device client:

1. **Source adapters** fetch MIMIT CSV and OSM Overpass JSON without embedding source-specific
   behavior in routing code.
2. **ETL** retains exact payloads, parses source records and publishes counts. Normalization is a
   separate restartable command so source acquisition remains independently useful.
3. **PostgreSQL/PostGIS** stores raw source history, normalized/spatial station data, price history
   and reconciliation evidence.
4. **Routing provider** exposes a provider-neutral async interface. Its Valhalla adapter owns HTTP
   translation, response validation and failure classification.
5. **Routing intelligence** owns corridor policy, spatial pruning, batched road detour math,
   arrival-time opening status, explainable ranking and reserve-aware predictive reachability. It
   does not live in the Android UI or raw ETL adapters.
6. **FastAPI** exposes strict versioned mobile contracts without exposing provider response shapes.
7. **Android (Kotlin, Jetpack Compose, MapLibre Native)** owns presentation and interaction state.

Valhalla is the selected routing engine. Phase 3 adds its independently persisted tile bootstrap and
provider adapter; Valhalla remains inaccessible from host/public ports.

## Phase 1 data flow

```text
MIMIT active-station CSV ─┐
                          ├─> fetch limit + exact snapshot ─> parse ─> select CNG IDs ─> raw tables
MIMIT price CSV ──────────┘

OSM Overpass JSON ──────────> fetch limit + exact snapshot ─> validate CNG features ─> raw table
```

Every retained exact payload has a SHA-256 identity. MIMIT's two related payloads receive a
deterministic combined identity for an ingestion run. OSM runs use a versioned canonical identity of
the parsed CNG feature collection, excluding the volatile Overpass response timestamp; otherwise an
unchanged query would create a new run every minute. The first exact payload for that logical content
is retained. A completed run with the same identity is returned as reused. Database constraints
independently protect snapshot, run and row identity.

The current raw model preserves:

- source URL and content type;
- payload bytes and SHA-256;
- source observation/extraction time and fetch/ingestion time separately;
- source IDs and original source record JSON;
- explicit CNG price currency (`EUR`) and unit (`kg`);
- OSM element type + ID and unmodified tags;
- per-run visible metrics.

## Phase 2 normalized data flow

```text
latest completed MIMIT run ─> MIMIT-anchored stations ─> geography(Point, 4326) + price history
                                        │
                                        ├─ ST_DWithin 250 m candidate search (GiST)
                                        │          └─ deterministic distance/name policy
latest completed OSM run ───> stable OSM features ─> matched / ambiguous / unmatched evidence
                                                               └─ current enrichment link
```

`stations` represents official MIMIT station identity and fields. `osm_cng_features` represents
stable OSM type/ID and enrichment fields. `station_osm_links` is the current accepted relationship;
it does not copy or overwrite authoritative station fields. Price observations are semantically
deduplicated in `station_prices`, while `station_current_prices` points to the latest observation for
each station/fuel/service-mode tuple.

Each reconciliation run records its MIMIT/OSM input run IDs, algorithm version, effective policy,
manual overrides, configuration hash, decisions, ranked candidates and summary counts. Identical
inputs are reused. A changed override or policy creates a distinct run. An OSM feature cannot be
linked to two stations: automatic conflicts become ambiguous, while conflicting manual claims fail.

Source coordinates outside conservative Italian bounds, or missing coordinates, remain preserved in
raw tables but produce a null normalized geography and an explicit unmatched outcome.

## Deliberate Phase 2 limits

- MIMIT coordinates remain authoritative source values, not verified road-access points.
- OSM data never overwrites MIMIT source data; only accepted links expose enrichment.
- Reconciliation uses conservative deterministic proximity/name rules, not address geocoding or an
  opaque fuzzy model.
- No route, detour, opening-hours evaluation, ranking, traffic or predictive logic exists yet.

## Phase 3 base-routing flow

```text
POST /api/v1/routes ─> strict A/B request ─> RoutingProvider
                                               └─ Valhalla adapter ─> internal /route
                                                    └─ metres + seconds + polyline6 + maneuvers

Geofabrik Italy/regional PBF ─> one-shot tile build ─> named volume ─> Valhalla service
```

The public base-route operation supports exactly two coordinates and automobile costing. The
provider adapter converts Valhalla kilometres to metres and validates the response before creating
domain objects. No source station model is consulted in this phase.

## Deliberate Phase 3 limits

- No route corridor, CNG candidate selection, matrix call or detour calculation.
- No traffic input or claim of traffic-aware routing.
- No opening-hours evaluation, ranking, predictive range model or multi-waypoint public API.
- Tile refresh remains an explicit operator job; application startup never erases the tile volume.

## Phase 4 spatial-pruning flow

```text
A/B + effective CNG range ─> one base-route call ─> decode polyline6
                                                        │
                                                        └─> transient PostGIS LineString
                                                              │
20% range policy + caps ──────────────────────────────────────┤
                                                              └─ ST_DWithin geography corridor
                                                                  │
stations.location GiST ───────────────────────────────────────────┘
                                    └─ cheap route distance/projection ordering + count metrics
```

The route is request state, not durable domain history, so Phase 4 does not add a route table.
`stations.location` remains the MIMIT-anchored geography point from Phase 2. `ST_DWithin` performs
metre-based corridor filtering and can use its existing GiST index. `ST_Distance` and
`ST_LineLocatePoint` are computed only for the reduced spatial set and are labeled as cheap
prefilter values.

## Deliberate Phase 4 limits

- Effective CNG range is an explicit request input; no tank/consumption model estimates it yet.
- Corridor inclusion proves spatial proximity only, not fuel reachability or road access.
- Candidate distance-to-route is not road-network distance from the previous waypoint.
- No candidate route, matrix, detour threshold, opening-hours evaluation or score is calculated.
- A configurable response limit protects the contract while the true pre-limit count remains in
  metrics for observability.

## Phase 5 network-detour flow

```text
Phase 4 returned candidates ── batches of 40 ─┬─ previous waypoint -> stations (one-to-many)
                                              └─ stations -> destination (many-to-one)
                                                                  │
base A/B route cost ──────────────────────────────────────────────┤
user maximum detour ─────────────────────────────────────────────┤
                                                                  └─ eligible road-cost results
```

The provider boundary exposes a rectangular matrix of optional road costs; the Valhalla adapter
owns `/sources_to_targets`, kilometre conversion, source/target index validation and unreachable
pair semantics. Routing intelligence combines the two legs, compares them to the Phase 4 base
route, calculates ETAs from an offset-aware departure and applies the caller's maximum detour
inclusively.

Phase 5 evaluates no more than Phase 4's configurable returned-candidate limit. With the defaults,
200 candidates require a clean-path minimum of ten matrix calls rather than 200 full route calls.
An error-171 batch is binary-split to isolate uncorrelatable stations without losing valid siblings.
Metrics expose the pre-limit corridor count, matrix-evaluated count, reachability, eligibility,
batch/call/fallback counts and the absence of per-candidate route calls.

## Deliberate Phase 5 limits

- Graph speeds are used without an external traffic overlay; responses explicitly report
  `traffic_state=not_configured` and `traffic_aware=false`.
- ETAs are elapsed-time projections only. Opening-hours parsing and status at ETA are later work.
- Eligibility ordering by detour cost is deterministic but is not price/opening/quality ranking.
- Refuelling dwell time is not included in detour duration.
- A Phase 4 candidate limit can make evaluation non-exhaustive; that state remains visible.
- Selecting a station and returning a full route through it is not part of this endpoint.

## Phase 6 availability and ranking flow

```text
Phase 5 eligible tuple ───────────────┬─> one relational enrichment query
                                     │      ├─ accepted OSM opening/phone/brand/operator
station ETA in Europe/Rome ──────────┤      └─ current MIMIT CNG price modes
                                     │
OSM opening_hours parser ────────────┼─> open / closed / unknown at ETA
price observation time ──────────────┼─> fresh / stale / future / unknown
explicit fixed weights ──────────────┴─> score components + deterministic ranked list
```

`POST /api/v1/cng/ranked-candidates` composes the accepted Phase 4 and Phase 5 stages; it does not
re-query the all-Italy inventory after detour eligibility. Only eligible station IDs enter one
outer-joined enrichment query; no query is issued for an empty eligible tuple. Accepted OSM links
supply enrichment without overwriting the MIMIT-anchored station. Across the current CNG price
pointers, the lowest unit price is selected; equal prices prefer the newest observation and then a
stable service-mode order.

Opening expressions are evaluated at each road-network station ETA after converting the instant to
the configured IANA timezone (`Europe/Rome` by default). A missing expression is `unknown/missing`,
a parser failure is `unknown/invalid`, and a valid expression can itself evaluate to `unknown`.
None of those states is silently treated as open.

The score is a documented weighted sum of normalized detour, opening, unit-price and price-freshness
components. Missing prices remain eligible with zero price contributions. Future observations are
exposed but excluded from price scoring. Closed stations are excluded by default; the opt-in
`include_closed` diagnostic mode retains them with zero opening score and a configurable availability
multiplier. Rank, raw component scores, contributions and multiplier are all returned. Stable
tie-breakers are detour duration, price presence/value and internal station ID.

## Deliberate Phase 6 limits

- Opening evaluation uses OSM expressions attached through accepted reconciliation links; unmatched
  stations correctly remain `unknown`.
- The configured timezone is national (`Europe/Rome`), appropriate to the current Italy-only scope;
  per-station timezone lookup is deferred until the geographic scope expands.
- The ranking weights are a transparent baseline policy, not a personalized model or opaque score.
- Current MIMIT prices are ranked as unit prices; refill quantity/cost requires a future vehicle and
  tank-state model.
- Graph-speed durations still have no external traffic overlay and remain explicitly non-traffic-aware.
- Selecting a candidate and recomputing the route through it remains outside this phase.

## Phase 7 public contract boundary

Phase 7 exposes completed domain behavior without coupling the client to database primary keys or
Valhalla payloads:

```text
MIMIT station ID ──> one joined normalized detail read ──> provenance + price freshness
origin + MIMIT ID + destination ──> station resolution ──> one Valhalla waypoint route
ingestion/reconciliation runs ──> freshness policy ──> detailed report + readiness data state
domain/API schemas ──> FastAPI OpenAPI ──> checked docs/openapi.json contract
```

`mimit_station_id` is the public station identity because MIMIT is authoritative and the internal
database ID is an implementation detail. Station detail performs one joined query for the station,
accepted OSM link and all current CNG service modes. Optional `arrival_at` uses the same explicit
opening-hours evaluator as ranking; omission is `not_requested`, not an implicit open state.

Selected-stop routing sends origin, resolved station point and destination as three Valhalla break
locations in one request. The response retains two separate legs, each with its own polyline6 and
maneuver indices. Compass therefore does not concatenate independently encoded geometry or leak the
provider response shape to clients. Inactive stations and missing station coordinates are distinct
409 domain conflicts; an unknown ID is 404.

Freshness compares source observation time (or completion time where no source observation exists)
with configurable thresholds. Missing required MIMIT or reconciliation data makes readiness 503.
Present but stale/future data is explicitly `degraded` and remains queryable with readiness HTTP 200.
OSM freshness can degrade enrichment quality without erasing authoritative MIMIT data. Traffic has a
separate health state and is never inferred from graph speeds.

All public request models reject unknown fields. Validation and operational failures use a shared
`{code,message}` JSON envelope; health responses intentionally use their dependency-state schema.
The generated OpenAPI document is checked into `docs/openapi.json` and tests require byte-equivalent
semantic content to the runtime schema.

## Deliberate Phase 7 limits

- The selected stop is supplied explicitly; predictive fuel reachability remains a later phase.
- Route alternatives, navigation session state and rerouting are not introduced by this contract.
- Data freshness is observed on request; no scheduler/alerting infrastructure is added without an
  operational requirement.
- Stale data thresholds are operator policy, not claims about upstream publication guarantees.

## Live traffic boundary

The traffic subsystem is provider-independent and feeds Valhalla rather than replacing it:

```text
provider feed ─> normalized TrafficFlowSegment ─> directed-edge matching
                                                                │
                                                                v
                                         deterministic edge update planner
                                                                │
                                                                v
                                                Valhalla traffic.tar overlay
                                                                │
                                                                v
                                   time-dependent Valhalla route/matrix requests
                                                                │
                                                                v
                                  Compass CNG detour/ranking/predictive strategy
```

The current TomTom implementation targets the base Traffic Flow API `flowSegmentData` endpoint
available to the project key. Probe points are sampled, with a strict configurable cap, from a
route already calculated by Valhalla. A private on-demand updater deduplicates the same itinerary
for five minutes; it does not poll TomTom while no route is being calculated. This is suitable for
route-corridor coverage, but it is not a nationwide bulk feed. TomTom Intermediate Traffic / Orbis
remains a future adapter option. In every mode, TomTom payloads stop at the adapter boundary.
Compass domain code sees normalized speeds, confidence, congestion, closure flags, OpenLR and
optional OSM way hints. OSM way IDs are never treated as Valhalla edge IDs.

Traffic remains disabled by default. When `TRAFFIC_ENABLED=true` but the Valhalla overlay is not yet
enabled, the API reports provider configuration without claiming traffic-aware routing. When both
`TRAFFIC_ENABLED=true` and `TRAFFIC_VALHALLA_OVERLAY_ENABLED=true`, the Valhalla adapter sends
time-dependent route/matrix requests with current/predicted/constrained/freeflow speed types.

Routing tiles, `traffic.tar` and the provider-to-directed-edge mapping are a matched set. A tile
rebuild invalidates old traffic edge mappings; operators must regenerate the traffic extract and
mapping before enabling live traffic again.

## Current live-traffic limits

- The provider boundary, mock fixtures, TomTom HTTP adapter, dynamic health endpoint and hardened
  Docker traffic-updater service exist.
- The native `traffic.tar` writer supports transactional set/reset batches as well as operator
  inspection and synthetic directed-edge updates.
- Native OpenLR decoding verifies provider direction; Valhalla geometry tracing supplies ordered
  directed GraphIds. Independent LRP-to-GraphId resolution is not implemented yet.
- A provider-independent planner now creates deterministic whole-edge set/reset/expiry operations
  and a tileset-bound state schema. The route-scoped updater applies native batches and saves state
  only after success; failed fetches retain unexpired observations and reset expired owned edges.
- API routing behavior remains backward-compatible while traffic is disabled.
- Synthetic traffic has proven that time-dependent Valhalla ETA changes while a request without
  `date_time` remains static. Live TomTom matching and direction checks have also passed read-only.
- Synthetic, read-only matching/planning, controlled writer and periodic one-probe TomTom gates have
  passed, including API `fresh`/fallback health. Nationwide production coverage remains blocked by
  the point/probe nature of the base API.
- Traffic-aware CNG eligibility and ranking use Valhalla duration outputs directly; there is no
  parallel traffic penalty. Scheduled departure is propagated to base, selected-stop, itinerary,
  detour and predictive pairwise routing boundaries.
- Batched matrices do not yet model a distinct path-dependent departure instant for every later
  candidate leg. The recomputed waypoint route is authoritative after selection; exact multi-stop
  future-leg matrix semantics remain follow-up work.

## Phase 8 Android route-preview boundary

The first device client consumes the public API rather than provider or persistence models:

```text
Compose screen ─> RoutePreviewViewModel ─> RoutingRepository
                                                │
                                                └─> strict Compass API DTOs ─> POST /api/v1/routes
                                                              │
polyline6 decoder <─ domain RoutePreview <────────────────────┘
        │
        └─> MapLibre GeoJSON source/style layers + endpoint markers
```

`data` owns HTTP and JSON translation, `domain` owns route concepts and geometry decoding, and `ui`
owns Android lifecycle and presentation state. Compose never receives raw HTTP DTOs, and MapLibre
types do not enter the repository interface. Manual application-level dependency construction keeps
the boundary replaceable without adding framework infrastructure for a single screen.

The debug API endpoint and map style are build properties. Emulator/USB development can reach the
loopback-bound backend through `10.0.2.2` or `adb reverse`; cleartext is limited to those development
addresses, while non-local endpoints require HTTPS. The checked-in device runner preflights the
backend, builds, installs and launches the app but leaves visual rendering as an explicit human gate.

## Deliberate Phase 8 limits

- The preview uses a fixed accepted Milan-to-Bologna fixture pair; destination entry is later UI.
- No CNG candidate request, station card, Add Stop flow or selected-stop route is implemented before
  Phase 9.
- No navigation session, location tracking, voice guidance, rerouting or background service exists.
- Map-style availability is an independent external dependency and is not labeled as backend
  routing failure.
- Physical-device rendering and lifecycle behavior require operator evidence before Phase 8 is
  accepted.

## Phase 9 Android manual CNG-stop workflow

Phase 9 extends the same data/domain/UI boundary without moving route policy to the device:

```text
Base preview ─> Add stop ─> Metano + detour/range ─> ranked-candidates
                                                        │
                                                        ├─> route + station markers
                                                        └─> ranked cards
                                                               │ MIMIT ID
                                                               v
                                                routes/with-cng-stop
                                                               │
                                                               └─> two legs + CNG waypoint
```

One lifecycle-preserved planner ViewModel owns the explicit preview, configuration, candidate and
selected-route stages. The device supplies an offset-aware departure instant but does not evaluate
OSM hours, calculate detours or rescore stations. Complete strict DTOs are translated into domain
models before Compose sees them. Candidate cards expose road distance, detour, ETA, opening state,
hours, phone, current CNG price/freshness and score components; missing price and unknown opening
state remain explicit.

MapLibre receives only decoded domain geometry and coordinates. Candidate markers remain a visual
index to the ranked list. A selected station is routed by official MIMIT ID and produces one
combined map line while retaining the two maneuver legs and their station boundary.

## Deliberate Phase 9 limits

- Milan and Bologna remain the deterministic endpoint pair; endpoint search/editing is not bundled
  into the CNG workflow.
- Manual Add Stop does not estimate remaining tank state or proactively suggest a reachable stop.
- Traffic remains disabled by default; graph-speed duration is not presented as live traffic.
- No location permission, navigation session, voice instruction, background tracking or rerouting
  service is introduced.

## Phase 10 predictive CNG reachability

Phase 10 composes accepted route, candidate, detour and ranking boundaries without moving
safety-relevant policy into Compose:

```text
request origin + caller range/reserve
                 │
                 v
        one remaining base route
                 │
       ┌─────────┴──────────┐
       │ destination within │ yes ─> not_needed; skip DB/matrices/enrichment
       │ usable road range? │
       └─────────┬──────────┘
                 │ no
                 v
   PostGIS corridor ─> bounded Valhalla matrices ─> detour eligibility
                                                        │
                                                        v
                       first road leg <= remaining - reserve
                                                        │
                                                        v
                       candidate-to-candidate Valhalla matrices
                                                        │
                                                        v
                       complete forward itinerary search
                          (later legs <= full - reserve)
                                                        │
                                                        v
                      per-stop ETA/opening/price enrichment
```

`request_origin` is the current or previous waypoint for the remaining route. The caller supplies
estimated remaining CNG range because no telemetry integration exists. Each response identifies this
consumption model and reports whether the underlying Valhalla durations were traffic-aware; the
system does not reinterpret graph speeds as live traffic.

The predictive API returns distinct `not_needed`, `suggested`, `gasoline_fallback`, `no_reachable_station`,
`no_eligible_station` and `no_complete_itinerary` states. `suggested` means a complete ordered chain
has been found, not merely that its first station is reachable. The first leg uses the driver-supplied
remaining range; every later leg assumes a full refill and must preserve the same reserve. Search
progress is measured by remaining road-network distance to the destination, not route-projection or
Euclidean distance.

`POST /api/v1/routes/with-cng-itinerary` is the execution boundary. It resolves the ordered MIMIT
IDs in one query, asks Valhalla for one multi-waypoint route and revalidates the actual distance and
reserve margin of every returned leg. Android maps the strict predictive plan and validated route
into separate domain models, draws every stop marker, and divides maneuvers by refuelling leg. The
shared contract bounds a plan to 32 stops and derives route totals from the validated leg sums.

Vehicle profiles are local Android presentation state, persisted as a versioned strict document.
They contain a label plus effective full range and reserve for CNG and gasoline. Selecting a profile
pre-fills those policy values; it never invents current tank levels. The driver may separately enter
estimated remaining gasoline range. Only after complete CNG planning fails may the backend return a
direct-route `gasoline_fallback`, with explicit required range and reserve margin. Navigation retains
those metrics across route preview and ordinary rerouting so the fallback stays visible.

## Deliberate Phase 10 limits

- Remaining range is driver supplied; no CAN/OBD integration, tank sensor or fuel-level inference is
  claimed.
- Every planned stop assumes an immediate full refill to the configured effective range; refill
  dwell time, partial fills and station queues are not yet modeled.
- The maximum-detour policy remains a per-station eligibility bound. It is not a promise that the
  sum of all refuelling deviations is below one global detour value.
- The deterministic request origin replaces live navigation progress for this gate.
- Traffic-adjusted consumption, route-progress updates and proactive background notifications remain
  future work.
- Active guidance, voice, rerouting and location permissions are not bundled into predictive
  reachability. Address search and current-location selection remain future route-input work.

## Phase 11 Android route endpoint editing

Phase 11 keeps routing policy on the backend and changes only the Android route-input boundary:

```text
editable coordinate form
          │
          v
  RoutePlannerViewModel active origin/destination
          │
          ├─> POST /api/v1/routes
          ├─> POST /api/v1/cng/ranked-candidates
          ├─> POST /api/v1/cng/predictive-candidates
          ├─> POST /api/v1/routes/with-cng-stop
          └─> POST /api/v1/routes/with-cng-itinerary
```

The Milan-to-Bologna pair is now only the default state. When the driver applies a new
origin/destination coordinate pair, the ViewModel reloads the base route, records the active
coordinates and clears stale ranked candidates, predictive suggestions and selected routes. All
downstream CNG requests use the active route's origin/destination, not constructor defaults.

The increment intentionally accepts raw coordinates rather than adding geocoding. That keeps Phase
11 small and testable: endpoint editing is independent from address search, current-location
permissions, saved places and navigation-session state.

## Android navigation boundary

The backend returns Valhalla geometry/maneuvers and Compass fuel-stop timing as a provider-neutral
`NavigationRoute`. After the user starts navigation, an application-scoped session and foreground
location service own live progress:

```text
Android LocationManager -> LocationFilter -> route-window matcher -> NavigationEngine StateFlow
                                                              |                  |
                                                              |                  +-> Compose / MapLibre
                                                              +-> ManeuverController -> Android TTS

confirmed off-route / 5-minute refresh -> RouteUpdateController -> Compass API -> Valhalla
                                                                    |
                                                                    +-> hot NavigationRoute replace
```

Raw GPS is exposed in state for diagnostics but is never rendered as the active vehicle position.
Filtering and projection are pure Kotlin and have deterministic replay fixtures. The matcher uses a
bounded window around prior progress, heading compatibility and backwards penalties. Three
consecutive poor fixes are required to confirm off-route; the decision combines route distance,
accuracy, heading and implausible backwards progress. Ordinary fixes never call the backend.

The service and UI share the session through `AppContainer`, allowing Activity recreation,
backgrounding and screen-off operation without losing the downloaded route. Stage 3 owns
TextToSpeech in that service, deduplicates early/prepare/immediate announcements and applies a
smoothed, speed-aware MapLibre camera with explicit follow and overview modes.

Navigation UI Phase 2 formalizes that camera boundary. `NavigationCameraConfig` owns the driving
pitch, continuous speed- and maneuver-density-dependent zoom, forward look-ahead and transition
timing policy;
`NavigationCameraController` converts the current `NavigationState` into a MapLibre-independent
camera instruction whose target lies ahead on the remaining route and whose bearing follows a
short forward route tangent instead of a potentially lagging location heading. `NavigationMap`
only renders that instruction with an eased transition. A MapLibre gesture changes UI-owned camera mode to
`FREE`, suppressing later automatic camera updates until `Ricentra` or the inactivity timeout;
north-up overview continues to fit only remaining geometry. This keeps camera presentation out of route matching and avoids a
second copy of navigation progress.

The free-camera state has a UI-owned ten-second inactivity timeout, reset by each MapLibre gesture;
the navigation engine remains unaware of this presentation timer. Follow instructions also carry
centralized asymmetric top padding. The matched vehicle is rendered by a vector locked vertically
to the viewport during heading-up follow, then rotated against the map in free and overview modes.
Consecutive-maneuver spacing contributes a bounded continuous zoom adjustment: dense junction
sequences move closer and sparse stretches widen the view. The primary trip summary is opt-in
through a compact map control rather than permanently covering the lower map.

The Android development build uses the keyless OpenFreeMap Liberty style as its road-capable
MapLibre baseline. `COMPASS_MAP_STYLE_URL` remains injectable for self-hosted/deployment styles.
This selection fixes the absence of local streets in the former low-zoom demo tiles; it does not
pre-empt the dedicated Compass day/night cartographic work in Navigation UI Phase 5.
After a style loads, Compass rewrites only name-bearing symbol layers to prefer Italian names with
neutral/local fallbacks, leaving route shields untouched. This is deliberately a rendering policy,
not a mutation of OpenStreetMap or backend data.

The active-navigation renderer also intersects every basemap `poi` symbol layer with a conservative
driving whitelist while preserving that layer's original rank and geometry filter. Fuel/charging,
toll booths, border control and traffic signals are eligible; transit, retail, tourism and civic
POIs are suppressed. Traffic signals remain absent with the current OpenMapTiles schema rather than
being inferred or fabricated. Compass-owned CNG waypoint sources are not part of this filtering.
The combined Phase 2 camera, interaction, cartographic-context and lifecycle gate was accepted on
an Android device on 2026-09-04.

Confirmed deviations and five-minute active-navigation refreshes call Compass—not Valhalla
directly—so Valhalla traffic costing and the remaining selected CNG itinerary stay authoritative.
The current route remains active while the request is in flight and after a failure; a successful
response atomically replaces the route inside the existing session. The current increment preserves
remaining planned stations and fuel-range inputs.

Stage 4 makes next-stop replacement explicit. The foreground service snapshots the current
navigation state, derives remaining range from the accepted predictive plan and local progress, and
asks Compass for a new predictive itinerary with the unavailable official MIMIT ID excluded. The
exclusion happens in PostGIS before the candidate limit and Valhalla matrices. Android accepts only
an acknowledged exclusion plus a complete `suggested` itinerary or a proven `not_needed` direct
route, then atomically replaces the route and restarts debug replay on the new geometry. Missing
range state, no complete itinerary and transport failures all retain the downloaded route.

## Phase 12 destination and journey-time flow

```text
Android text/coordinate query -> Compass /places/search -> PlaceSearchProvider -> Nominatim
          |                              |
current device location                 +-> normalized address/locality/POI/coordinate
          |                                               |
          +-------------------- selected A/B coordinates -+
                                                          v
                                           Compass /routes -> Valhalla maneuvers
                                                          v
                                         foreground NavigationSession
```

Android never contacts Nominatim directly. Coordinate queries are resolved inside Compass; textual
queries pass through the provider abstraction and return a strict normalized contract. Current
location is requested by Android only after user action and is then used as an ordinary route
origin.

The established local matcher, maneuver controller, confirmed off-route state machine and
foreground service continue to own live progress. A successful reroute first attempts to retain the
remaining ordered CNG stops and their range policy. If Compass reports a missing, unavailable or
range-invalid stop, Android excludes that invalid remainder and requests a fresh complete predictive
plan before atomically replacing the active route. A failure leaves the downloaded route active.

Journey chronology is represented independently from physical route cost:

```text
total trip duration = Valhalla driving duration + sum(CNG stop dwell)
stop N ETA = departure + driving through leg N + dwell at stops before N
```

The default dwell is 1,200 seconds and is injected from server configuration into predictive and
selected-route responses. It changes later opening-hours evaluations and ETAs, but never the road
detour comparison. Traffic delay remains a nullable, explicitly unavailable component unless an
enabled provider can supply a defensible separate estimate.

## Runtime

The default Compose graph contains:

- `db`: pinned PostGIS 16 / PostGIS 3.5 family with a named data volume and readiness healthcheck;
- `migrate`: one-shot Alembic upgrade after the database is healthy;
- `api`: non-root FastAPI container with liveness/readiness healthcheck and loopback-only host bind;
- `etl`: profile-gated one-shot job, not a persistent service.

Routing adds two opt-in workloads:

- `valhalla-tiles`: `routing-build` profile, one-shot Italy/regional graph construction;
- `valhalla`: `routing` profile, persistent internal router with a status healthcheck.

Both routing workloads use the same `valhalla_data` named volume. The API does not require the
routing profile merely to start, but readiness is `not_ready` until both PostgreSQL and Valhalla are
available. `VALHALLA_VOLUME_NAME` makes the physical volume versionable so graph updates can be
built and validated before activation, while the previous graph remains available for rollback. The
builder checks the scripted image's PBF registration (`use_tiles_ignore_pbf=False`); only the serving
process trusts and reuses the completed graph (`use_tiles_ignore_pbf=True`). Exact input identity is
an explicit content SHA-256, because the image's internal registration hashes file paths.

The migration, API and ETL workloads use the same configurable `COMPASS_IMAGE`. Only `api` declares
that repository build, so one build produces the exact image used for those three workloads. The
traffic updater deliberately uses `COMPASS_TRAFFIC_IMAGE`, built by `Dockerfile.traffic` from the
same pinned Valhalla image as the router. That image packages Compass with the native OpenLR and
traffic helper linked against the router's `libvalhalla`; it remains an internal, non-public
service.

Secrets are environment supplied. No host-specific paths or privileged containers are required.

## Phase 13 device cache and degraded operation

The application-scoped `NavigationSession` sits in front of a versioned private-storage route
store. Preview/start and successful route replacements persist the complete `NavigationRoute`;
explicit stop/abandon clears it. On process reconstruction the store creates a cached preview rather
than silently restarting location or a foreground service.

```text
Compass live route -> NavigationSession -> versioned route store
                           |                       |
                           v                       +-> process-restart cached preview
                    local matcher/GPS
                           |
Compass unavailable ------+-> keep route + REROUTING_UNAVAILABLE
Compass restored -> successful route replace -> LIVE + ONLINE
```

The HTTP repository independently keeps ten normalized exact-query search result sets. Only
network/server failures may read that cache. MapLibre retains already requested style/tile/font
resources in its bounded ambient database. Neither cache performs routing; any new physical route
or reroute continues to cross the Compass API and server-side Valhalla boundary. See ADR 0018.
