# AGENTS.md

## Project mission

This repository implements an open-source, CNG-aware navigation and routing system for Italy.

The end product is not a POI viewer. It is a navigation system that can:

- calculate a normal A → B route;
- insert CNG refuelling stops dynamically;
- restrict candidates by a user-defined maximum detour time;
- rank stations using route cost, opening status at estimated arrival time, CNG price, distance from the previous waypoint, and traffic;
- proactively suggest refuelling stops based on remaining CNG range;
- expose the entire experience through a first-class Android mobile client.

The project must remain modular enough to add other stop classes later (EV charging, LPG, parking, food, etc.).

---

## Working relationship with the human operator

The agent modifies the repository only.

The human operator:

1. pulls/synchronizes repository changes to the live test server;
2. performs live deployment and integration testing there;
3. returns command output, logs, API responses, screenshots, performance results, and failures to the agent.

The agent MUST NOT assume it can access the live server.

The agent MUST NOT claim that a live deployment, live routing query, external network integration, mobile-device test, or production-like test succeeded unless the human operator has explicitly returned evidence of that success.

Local/static/unit tests that can run inside the repository environment should still be implemented and run by the agent whenever possible.

When live validation is required, finish the iteration with a compact test handoff containing:

- exact commands to run;
- required working directory;
- any environment variables or secrets that must already exist;
- expected result or invariant;
- diagnostic commands to run if the expected result is not obtained;
- which output the human should return.

Do not proceed into the next project phase until the current phase's acceptance criteria are met or the human explicitly instructs otherwise.

---

## Delivery model

Work in small, reviewable increments.

For each requested phase or task:

1. inspect the existing repository before designing changes;
2. identify what already exists and preserve compatible work;
3. state the smallest coherent implementation increment;
4. modify only what is necessary for that increment;
5. add/update tests;
6. update documentation and configuration examples;
7. run all repository-local validation available to you;
8. provide the human with the live test handoff;
9. wait for the returned live-test result before advancing when live validation is required.

Avoid broad speculative rewrites.

Do not implement future phases merely because their interfaces are visible.

It is acceptable and encouraged to define stable interfaces, schemas, extension points, TODOs, and ADRs for future phases without implementing them early.

---

## Containerization rules

All server-side deployable components MUST be structured for Docker deployment.

Use Docker Compose as the reference integration/development deployment unless the repository explicitly establishes another container orchestrator.

Expected server-side workloads may include:

- API service;
- ETL/import service;
- PostgreSQL + PostGIS;
- Valhalla routing service;
- optional cache/queue services when justified;
- scheduled data-refresh jobs.

Requirements:

- pin image/application versions where practical;
- add healthchecks to long-running services;
- use named volumes or explicitly documented bind mounts for persistent state;
- keep secrets out of Git;
- provide `.env.example` for configuration;
- ensure containers can be rebuilt from repository state;
- do not encode host-specific paths unless configurable;
- do not require privileged containers without strong justification;
- use migrations for database schema changes;
- separate one-shot jobs (imports/migrations/tile building) from persistent services where practical.

The Android app is a device application, not a Docker runtime workload. Do not attempt to run the Android application itself in Docker on the target device. Its server dependencies must be Dockerized. A containerized/reproducible mobile build job may be added later if useful.

---

## Preferred architecture

Unless an ADR proves a materially better choice, use:

- **Routing engine:** Valhalla
- **Spatial database:** PostgreSQL + PostGIS
- **Backend/API:** Python + FastAPI
- **Mobile:** native Android, Kotlin + Jetpack Compose
- **Map renderer:** MapLibre Native
- **Data exchange:** REST/JSON initially, strict OpenAPI schemas
- **Geometry interchange:** encoded polyline and/or GeoJSON depending on endpoint needs
- **Database migrations:** Alembic or an equivalent migration system
- **Tests:** pytest for Python components; appropriate Android unit/UI tests for mobile code

Why Valhalla is preferred:

- dynamic costing;
- time-dependent routing;
- turn-by-turn maneuver generation;
- support for externally supplied historical/predicted traffic;
- support for live traffic overlays;
- suitable APIs for matrices, route geometry and navigation.

Do not replace Valhalla with GraphHopper, OSRM, or another engine casually. If a replacement is proposed, create an ADR comparing at minimum live traffic ingestion, time-dependent routing, maneuver generation, self-hosting, licensing, operational complexity, and detour/matrix performance.

---

## Data-source policy

### CNG station inventory and prices

Treat the Italian MIMIT / Osservaprezzi Carburanti open datasets as the preferred authoritative source for:

- active fuel-station registry;
- official station identifier (`idImpianto`);
- coordinates/address where available;
- current reported fuel prices;
- price observation/update timestamps;
- CNG/`metano` availability.

MIMIT data is expected to refresh periodically (normally daily for downloadable open data). Preserve source timestamps and ingestion timestamps separately.

### OpenStreetMap

Use OpenStreetMap as a complementary geospatial/enrichment source, especially for:

- `amenity=fuel`;
- `fuel:cng=yes`;
- `opening_hours`;
- `phone` / `contact:phone`;
- brand/operator metadata;
- access metadata and other map attributes.

Do not silently overwrite authoritative fields from one source with another.

### Source reconciliation

MIMIT stations and OSM features will not share a universal identifier. Implement reconciliation explicitly.

The data model should support:

- MIMIT source ID;
- OSM type + OSM ID;
- normalized name/address;
- spatial distance between source records;
- match confidence;
- match method/version;
- manual overrides;
- unmatched records;
- source provenance per field where relevant.

Use deterministic matching where possible. Any fuzzy/spatial matching must have testable thresholds and must not silently create low-confidence joins.

### Traffic

Live traffic data is an external input to Valhalla, not something Valhalla magically supplies.

Create a provider abstraction before binding the project to a commercial or public traffic source.

The system must support:

- no-traffic fallback;
- test fixtures/synthetic traffic for deterministic tests;
- future live-traffic provider(s);
- source timestamp and freshness;
- historical/predicted speed data if available.

Do not fake live traffic in user-visible production responses.

---

## Opening-hours semantics

Opening-hours logic is arrival-time-aware.

For a candidate CNG station, the relevant status is not merely `open_now`.

Compute and expose status at the predicted station arrival time, for example:

- `open_at_eta`;
- `station_eta`;
- `opening_hours`;
- `opening_hours_source`;
- `opening_hours_confidence` or validation state where useful.

Timezone handling must be explicit and correct.

If opening hours are missing or unparsable, return an explicit `unknown` state. Never interpret missing hours as open.

---

## Candidate-station pruning

Never calculate full detour routes against every CNG station in Italy.

Use a staged candidate-selection pipeline.

### Manual “Add CNG stop” mode

Given the remaining route from the previous waypoint/current origin to the destination:

1. calculate the optimal base route;
2. construct a spatial corridor around the route;
3. use a configurable lateral search radius based on vehicle CNG autonomy;
4. default baseline:
   `corridor_radius_km = 0.20 * effective_cng_range_km`;
5. apply configurable sensible minimum/maximum caps;
6. query candidate stations using PostGIS spatial indexes;
7. pre-rank/prune cheap spatial candidates;
8. use Valhalla to compute real road-network detours only for the reduced candidate set;
9. keep candidates whose **traffic-aware extra travel time** is within the user's maximum detour constraint.

The 20% value is a default policy, not a hard-coded constant. Put it in configuration/domain policy and cover it with tests.

### Predictive refuelling mode

Predictive mode additionally restricts candidates by fuel reachability.

Use:

- estimated remaining CNG range;
- reserve/safety margin;
- route progress;
- traffic-adjusted consumption where the model supports it;
- route segment reachable before the reserve threshold.

Do not suggest a station that is spatially near the route but unreachable with the estimated remaining fuel.

### Distances

The user-facing “distance from departure or previous stop” must be road-network/route distance from the previous waypoint to the station, not simple Euclidean distance.

Keep Euclidean distance only as a cheap prefiltering metric.

---

## Detour definition

Define detour using route cost, not straight-line geometry.

For a base remaining route:

`previous_waypoint → destination`

and a candidate:

`previous_waypoint → station → destination`

compute at minimum:

- base travel time;
- candidate travel time;
- extra travel time / detour minutes;
- base distance;
- candidate distance;
- extra distance;
- distance from previous waypoint to station;
- ETA at station;
- ETA at destination.

When traffic is enabled, the primary detour value presented to the user must be traffic-aware.

---

## Station ranking

Ranking must be explainable and deterministic.

Do not create an opaque “AI score”.

Inputs may include:

- detour minutes;
- extra distance;
- distance from previous waypoint;
- open/closed/unknown at ETA;
- CNG unit price;
- price freshness;
- traffic-adjusted travel time;
- station-data confidence;
- optional user preferences.

Store or return enough component values that the mobile UI can explain why a station ranks well.

A closed station should normally be excluded or heavily penalized depending on requested behavior.

An `unknown` opening state must be distinct from `closed`.

Missing price must not automatically make a station unusable.

---

## Price semantics

For CNG, model price explicitly as a unit price, normally EUR/kg when supplied by the Italian official dataset.

Recommended fields include:

- `fuel_type`;
- `unit_price`;
- `currency`;
- `unit`;
- `service_mode` when available;
- `price_observed_at`;
- `price_ingested_at`;
- `price_source`.

Do not present stale prices without exposing their observation timestamp/freshness.

Future estimated refill cost must be a separate derived field and requires a vehicle/tank state model.

---

## Mobile client is first-class

The Android application is mandatory, not an optional demo.

Primary stack:

- Kotlin;
- Jetpack Compose;
- MapLibre Native.

The mobile client owns presentation and interaction state.

The backend owns routing, spatial computation, ranking, and normalized data.

The backend MUST NOT return UI instructions such as widget placement or color choices.

The API SHOULD return enough structured data to support:

- route preview;
- route geometry;
- alternate routes where later supported;
- “Add stop” categories;
- CNG candidate list;
- candidate markers;
- detour minutes;
- road distance from previous waypoint;
- station ETA;
- opening status at ETA;
- human-readable opening hours;
- phone number;
- CNG unit price + freshness;
- ranking components;
- traffic status/freshness;
- route recomputation after station selection;
- turn-by-turn maneuvers when navigation is implemented.

Avoid coupling mobile domain models directly to database tables.

---

## Core UX contract

The target interaction is:

1. user selects A → B;
2. route preview is displayed;
3. user chooses **Add stop**;
4. user selects **CNG / Metano**;
5. user provides maximum permitted detour time;
6. backend returns eligible ranked stations;
7. mobile client displays map + list;
8. each station can show:
   - detour time;
   - distance from previous waypoint;
   - ETA at station;
   - opening hours;
   - open/closed/unknown at ETA;
   - phone;
   - CNG price and timestamp;
9. user selects a station;
10. backend recalculates route with the stop as a waypoint;
11. updated route is shown;
12. later, predictive mode may initiate the same selection flow proactively.

Keep this contract stable unless a versioned API/ADR changes it.

---

## API design rules

Use versioned API paths once public/mobile integration begins, e.g. `/api/v1/...`.

Use strict request/response schemas.

Return machine-readable error codes in addition to human-readable messages.

Do not expose raw internal exceptions.

Use consistent units and document them.

Prefer explicit nullable/unknown states over magic sentinel values.

Include source/freshness metadata for dynamic information.

OpenAPI documentation must stay synchronized with implementation.

---

## Database rules

Use PostGIS-native geometry/geography types appropriately.

Create spatial indexes for station geometries.

Do not store route polylines as arbitrary unindexed text when spatial operations require geometry.

Use migrations; never rely on undocumented manual schema edits.

Preserve source raw data or enough provenance to reproduce normalized records.

Design ingestion to be idempotent.

A repeated import of the same source snapshot must not duplicate stations or prices.

---

## ETL rules

ETL jobs must be restartable and observable.

Separate stages where useful:

1. fetch;
2. persist raw snapshot/metadata;
3. parse;
4. normalize;
5. reconcile sources;
6. validate;
7. upsert;
8. publish ingestion metrics.

Do not make successful ingestion dependent on every enrichment source being available.

For example, a temporary Overpass failure should not erase valid MIMIT stations/prices.

Record failures and continue in a controlled degraded mode where safe.

---

## Testing strategy

Every phase must contain automated tests appropriate to its scope.

Prioritize:

- parser tests using checked-in small fixtures;
- normalization tests;
- source reconciliation tests;
- PostGIS query/integration tests;
- routing-adapter tests;
- candidate-pruning tests;
- detour math tests;
- opening-hours-at-ETA tests;
- scoring tests;
- API contract tests;
- migration tests;
- Android ViewModel/domain tests;
- selected Compose/UI tests.

External services must be mockable/fixture-driven for automated tests.

Do not make the normal unit-test suite depend on live Overpass, MIMIT, traffic providers, or public routing endpoints.

Live smoke tests are separate and are executed by the human operator when server/network access is required.

---

## Performance principles

Optimize only after correctness, but design hot paths consciously.

Spatially prune before routing.

Batch/matrix routing requests where the routing engine supports it.

Avoid N+1 database queries.

Cache immutable or slowly changing computations only with explicit invalidation/freshness rules.

Measure:

- candidate count before/after spatial pruning;
- routing calls per request;
- API latency;
- DB query latency;
- traffic data age;
- ETL duration;
- source reconciliation counts.

Do not add Redis or a queue solely because the project is “large”. Add infrastructure only when a measured or architectural requirement justifies it.

---

## Observability

Use structured logging for server-side components.

Include correlation/request IDs where useful.

Expose health endpoints that distinguish:

- process alive;
- database ready;
- routing engine ready;
- latest successful data ingestion;
- traffic provider status/freshness.

Never log secrets.

---

## Security and configuration

No credentials, tokens, API keys, or production endpoints committed to Git.

Use environment variables/secrets.

Provide `.env.example` with safe placeholders.

Validate external inputs.

Apply request timeouts to outbound calls.

Use retry/backoff only where semantically safe.

Do not expose internal database/routing services publicly unless explicitly required; normally expose only the API through the operator's chosen reverse proxy.

The human operator controls public exposure and reverse-proxy configuration unless explicitly asked to add repository examples.

---

## Documentation discipline

Keep at minimum:

- root `README.md`;
- architecture overview;
- setup/development instructions;
- Docker deployment instructions;
- API docs/OpenAPI;
- data-source notes and licensing/attribution;
- phase-specific acceptance criteria;
- ADRs for consequential architectural decisions.

Use `docs/adr/NNNN-title.md` for major decisions.

Important ADR candidates:

- Valhalla selection;
- MIMIT + OSM source reconciliation;
- Android/Kotlin/MapLibre choice;
- traffic provider interface;
- candidate-pruning policy;
- predictive fuel model.

---

## Phase gates

A phase is complete only when:

- implementation for that phase exists;
- automated tests for the phase pass locally where runnable;
- Docker/config/docs changes are included;
- acceptance criteria are documented;
- human-required live test procedure is supplied;
- any required live test has been reported successful by the human, or the human explicitly waives it.

When the human reports a failure, debug the current phase first. Do not mask it by advancing architecture.

---

## Definition of done for an agent iteration

Before ending an implementation iteration, report:

1. **Changed:** concise list of files/components changed.
2. **Why:** important design choices.
3. **Validated locally:** commands/tests actually run and results.
4. **Live test handoff:** exact server commands for the human.
5. **Expected result:** concrete outputs/invariants.
6. **If it fails:** targeted diagnostic commands.
7. **Next step:** only the next smallest step, not an unrequested implementation.

Never state “works” when only static analysis or mocks passed; name the validation level precisely.
