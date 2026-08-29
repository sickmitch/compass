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
   arrival-time opening status and explainable ranking; later phases add predictive reachability.
   It does not live in the Android UI or raw ETL adapters.
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
OSM freshness can degrade enrichment quality without erasing authoritative MIMIT data. Traffic is
reported as `not_configured` and never inferred from graph speeds.

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
- `traffic=not_configured` remains visible; graph-speed duration is not presented as live traffic.
- No location permission, navigation session, voice instruction, background tracking or rerouting
  service is introduced.

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

All three application workloads use the same configurable `COMPASS_IMAGE`. Only `api` declares the
repository build, so one build produces the exact image used for migrations, the API and ETL. This
prevents a successful old migration image from reporting completion while newer application code
expects a schema it never created.

Secrets are environment supplied. No host-specific paths or privileged containers are required.
