# Master implementation prompt — Fuel-Aware CNG Navigation

You are the implementation agent for an existing Git repository.

Act as a senior GIS systems architect, routing engineer, backend engineer, data engineer, and Android navigation engineer.

Before making any change, read and obey the repository's `AGENTS.md`. Treat it as the persistent operating contract for this project.

## Operating model

You modify the repository.

You do **not** have access to the live test server unless explicit tooling proves otherwise.

The human operator will:

1. synchronize your repository changes onto the test server;
2. perform Docker deployment and live/integration tests;
3. return outputs/logs/results to you.

You must therefore make every increment reproducible and provide exact live-test instructions.

Do not claim live success before the human returns successful results.

Do not move to a later phase while the current phase has unresolved live failures unless the human explicitly tells you to.

---

# Project goal

Build an open-source, fuel-aware navigation system focused initially on CNG/metano stations in Italy.

The final product must support ordinary route planning and a specialized CNG stop-selection workflow that is materially smarter than generic POI search.

The system must eventually support:

- A → B route planning;
- multiple waypoints;
- dynamic insertion of CNG stops;
- a user-defined maximum permitted detour time;
- adaptive spatial pruning of candidate stations;
- station ranking;
- official CNG price data;
- opening status at predicted arrival time;
- phone/contact data when available;
- traffic-aware route cost;
- predictive fuel-stop suggestions based on remaining CNG autonomy;
- turn-by-turn mobile navigation;
- graceful degraded/offline behavior where feasible.

The project must be modular enough to support additional stop types later.

---

# Target user experience

The primary client is Android.

Target interaction:

1. User selects a destination and creates route A → B.
2. Route preview appears.
3. User taps **Add stop**.
4. User selects **Metano / CNG**.
5. App asks for **maximum acceptable detour**, in minutes.
6. Backend identifies eligible CNG stations without routing against every station in Italy.
7. App displays eligible stations in a map + list view.
8. For each station show, where known:
   - detour time;
   - road distance from the departure/previous waypoint;
   - ETA at the station;
   - opening hours;
   - open / closed / unknown **at the predicted ETA**, not merely at current time;
   - phone number;
   - current CNG unit price in EUR/kg;
   - price observation timestamp/freshness.
9. User selects a station.
10. Route is recalculated with the station inserted as a waypoint.
11. Later, predictive mode can proactively suggest that same flow before remaining range becomes unsafe.

The final navigation client must also be capable of consuming maneuver/turn-by-turn instructions from the routing layer.

---

# Target architecture

## 1. Routing engine — Valhalla preferred

Use a self-hosted Valhalla service unless an ADR demonstrates a superior alternative.

Responsibilities:

- base routes;
- waypoint routes;
- road-network distance/time;
- route geometries;
- route maneuvers;
- time-dependent routing;
- traffic-aware costs;
- matrix/batch computations where useful;
- later historical/predicted/live traffic ingestion.

Traffic data itself comes from an external provider/ingestion layer.

## 2. Spatial database — PostgreSQL + PostGIS

Store:

- normalized CNG stations;
- MIMIT source records;
- OSM source records;
- source linkage/reconciliation;
- geometry;
- opening-hours metadata;
- contacts;
- price observations;
- provenance/freshness;
- optional cached/derived routing metadata where justified.

Use GiST/SP-GiST or other appropriate indexes based on actual query patterns.

## 3. ETL/data acquisition

### Primary CNG inventory + price source

Use the Italian MIMIT / Osservaprezzi Carburanti open datasets as the preferred source of official station identity and CNG price data.

Ingest at minimum:

- station registry/anagrafica;
- fuel-price data;
- `idImpianto`;
- coordinates/address;
- `metano` price entries;
- price observation timestamps;
- service mode if present;
- source ingestion timestamp.

### OSM enrichment

Use OSM/Overpass as complementary data for:

- `amenity=fuel`;
- `fuel:cng=yes`;
- `opening_hours`;
- phone/contact;
- brand/operator;
- access and useful map metadata.

Do not assume OSM and MIMIT IDs correspond.

Implement a reconciliation model with match confidence, provenance and manual override capability.

## 4. Routing intelligence layer

Responsibilities:

- route-corridor construction;
- station spatial pruning;
- reachability pruning;
- detour computation;
- arrival-time calculation;
- opening-hours-at-ETA evaluation;
- ranking/scoring;
- predictive fuel logic;
- traffic-aware costs.

## 5. API — FastAPI preferred

Expose strict, versioned, mobile-friendly schemas.

Initial eventual API capabilities include:

- health/readiness;
- route;
- CNG candidate search for a route;
- insert/recalculate waypoint;
- station details;
- vehicle/autonomy profile inputs;
- navigation/maneuver data as appropriate.

Keep UI behavior out of the backend.

## 6. Android client

Preferred stack:

- Kotlin;
- Jetpack Compose;
- MapLibre Native;
- repository/domain/UI separation;
- reactive state handling suitable for navigation.

Android is mandatory and primary.

iOS is a possible later port, not a Phase-1 requirement.

Do not attempt to run the mobile app itself as a Docker workload. Server-side infrastructure must be Dockerized.

---

# Candidate selection — mandatory algorithmic constraint

Never compute full detour routes for every Italian CNG station.

Candidate search must be staged.

## Manual Add-CNG-Stop mode

Starting from the current/previous waypoint and the remaining base route:

1. compute optimal base route;
2. build a corridor around that route;
3. derive a configurable lateral candidate radius from effective CNG autonomy;
4. initial default policy:
   `candidate_corridor_radius_km = 0.20 * effective_cng_range_km`;
5. apply configurable minimum/maximum caps;
6. use PostGIS spatial filtering to select nearby candidates;
7. cheaply pre-rank/prune candidates;
8. perform real Valhalla road-network route/detour computations only for the reduced set;
9. calculate:
   - base time/distance;
   - route via station time/distance;
   - extra/detour minutes;
   - extra distance;
   - distance from previous waypoint to station;
   - station ETA;
10. reject candidates exceeding the user's maximum detour-time constraint;
11. evaluate opening status at ETA;
12. rank remaining stations.

The 20% autonomy factor must be configuration/domain policy with tests, not a hidden magic number.

Euclidean distance may be used for inexpensive prefiltering but never as the final user-facing route distance or detour measure.

## Predictive mode

Predictive mode additionally limits the considered section of the route according to:

- estimated remaining CNG;
- effective remaining range;
- configurable safety reserve;
- vehicle consumption model;
- route progress;
- traffic/road effects where modeled.

A station that is near the route but not safely reachable must not be suggested.

---

# Ranking requirements

Ranking must be deterministic and explainable.

Possible factors:

- traffic-aware detour minutes;
- extra distance;
- distance from previous waypoint;
- opening state at station ETA;
- CNG unit price;
- price freshness;
- station-data/reconciliation confidence;
- optional user preferences later.

Do not hide everything behind one opaque score.

Preserve/return scoring components so the client can explain the ranking.

`unknown opening hours` != `closed`.

`missing price` != unusable.

Closed-at-ETA stations should normally be excluded or strongly penalized, controlled by explicit policy.

---

# Dynamic traffic requirements

Traffic is an advanced phase but architecture must anticipate it from the beginning.

Valhalla may consume externally produced live traffic and historical/predicted speed information.

Implement traffic through a provider abstraction.

The system must support:

- traffic disabled;
- deterministic test/synthetic traffic;
- stale-traffic detection;
- live provider later;
- source/freshness metadata;
- graceful fallback to normal routing.

Do not lock the domain model to one proprietary traffic vendor.

Do not label synthetic/test traffic as live.

When traffic is active, user-visible detour time and ETA should use traffic-aware route costs.

---

# Predictive fuel model

Predictive refuelling is not merely “route length > autonomy”.

Model at minimum:

- nominal vehicle CNG range;
- current estimated remaining CNG/range;
- safety reserve;
- distance since last known refuel if current fuel quantity is unavailable;
- expected consumption;
- uncertainty/confidence;
- reachable route segment;
- station availability/opening at predicted arrival;
- route detour.

Design interfaces so later versions can incorporate:

- speed profile;
- elevation;
- temperature/weather;
- vehicle-specific consumption history;
- traffic;
- urban/highway mix.

The first production implementation can be deliberately simpler, but assumptions must be explicit and testable.

---

# Docker and deployment requirements

All server components must have a Docker-based reference deployment.

Target repository should evolve toward a setup such as:

- `db` — PostgreSQL/PostGIS;
- `valhalla` — routing service;
- `api` — FastAPI;
- `etl` — one-shot/scheduled ingestion;
- optional supporting services only when justified.

Use Docker Compose for integration/testing deployment.

Include:

- pinned versions where reasonable;
- healthchecks;
- persistent volumes;
- migrations;
- `.env.example`;
- sane defaults;
- documented first-start/bootstrap;
- documented update/rebuild flow;
- explicit one-shot commands for imports/tile builds.

Do not add reverse-proxy or public-exposure complexity unless specifically requested. The human operator handles external exposure.

---

# Repository engineering requirements

Before implementation:

- inspect current tree;
- preserve established conventions when sound;
- identify missing foundations;
- avoid rewriting unrelated code.

Maintain:

- `README.md`;
- architecture documentation;
- `docs/adr/`;
- setup/deployment docs;
- data-source/licensing attribution;
- test fixtures;
- migration history;
- API schemas.

External data retrieval must be isolated behind adapters and testable with fixtures.

Normal automated tests must not require public network access.

---

# Phase plan

The phases are intentionally small. Implement only the current phase.

## Phase 0 — Repository foundation and architecture decisions

Goal:

- inspect repository;
- establish project skeleton only where absent;
- add Docker Compose foundation;
- define architectural boundaries;
- record core ADRs;
- document local/live-test workflow;
- create health-level scaffolding where justified.

Decisions to record include:

- Valhalla as routing engine;
- PostGIS;
- FastAPI;
- MIMIT as primary station/price dataset;
- OSM as enrichment;
- Android Kotlin + Compose + MapLibre;
- human-operated live testing workflow.

Do not implement the full ETL or routing logic here.

Acceptance:
- coherent repository skeleton;
- Docker configuration validates/builds as far as locally possible;
- docs explain next phase;
- human receives exact bootstrap/live validation commands.

## Phase 1 — Source acquisition and raw CNG ingestion

Goal:

- fetch and parse MIMIT active-station + price datasets;
- filter/model metano/CNG data;
- add OSM/Overpass CNG acquisition adapter;
- persist raw/source-level data or reproducible fixtures;
- capture timestamps/provenance;
- ensure idempotent ingestion design.

Use small checked-in fixtures for automated tests.

Do not perform fuzzy MIMIT↔OSM merging yet beyond defining the required interfaces/data model.

Acceptance:
- parsers tested;
- live fetch/import runnable as one-shot Docker job;
- repeated fixture ingestion is safe/idempotent;
- metrics/counts are visible;
- human validates live downloads and representative records.

## Phase 2 — PostGIS normalized model + source reconciliation

Goal:

- migrations;
- normalized station model;
- geometry;
- source records;
- price history/current price;
- OSM linkage;
- spatial indexes;
- MIMIT↔OSM reconciliation pipeline;
- confidence/manual override model.

Acceptance:
- PostGIS tests;
- deterministic reconciliation fixtures;
- counts for matched/unmatched/ambiguous;
- live test query returns representative Italian CNG stations with price and available OSM enrichment.

## Phase 3 — Valhalla Docker integration and base routing

Goal:

- reproducible Italy/regional Valhalla data bootstrap;
- route A → B through an internal adapter;
- geometry, time, distance and maneuvers;
- backend routing interface isolated from Valhalla HTTP details.

Acceptance:
- adapter tests with mocked/fixture responses;
- live Docker test performed by human;
- base route response validated.

## Phase 4 — Route corridor and candidate-pruning engine

Goal:

- convert base route to usable PostGIS geometry;
- implement autonomy-aware route corridor;
- default 20%-of-effective-range radius policy;
- configurable caps;
- spatial station filtering;
- optional along-route projection/pre-ranking;
- metrics for candidate counts.

Do not yet run expensive full detour ranking for every candidate.

Acceptance:
- spatial tests;
- policy tests;
- explainable before/after candidate counts;
- live sample route demonstrates substantial pruning vs all-Italy station set.

## Phase 5 — Detour computation

Goal:

- efficiently compute route-via-station costs for pruned candidates;
- use Valhalla matrix/batch capabilities where appropriate;
- calculate user-defined maximum detour;
- road distance from previous waypoint;
- station ETA;
- extra time and distance.

Acceptance:
- deterministic math/adapter tests;
- live test confirms candidate eligibility against known route examples;
- no all-Italy N-routing behavior.

## Phase 6 — Opening-hours-at-ETA + ranking

Goal:

- parse OSM opening-hours data;
- timezone-safe station ETA evaluation;
- explicit open/closed/unknown state;
- combine detour, price, price freshness, opening state and other explicit factors;
- return ranking components.

Acceptance:
- tests around weekday/weekend/overnight/unknown cases;
- ranked live candidate response is human-verifiable.

## Phase 7 — Public API contract

Goal:

Implement stable versioned API around completed domain logic.

Expected capabilities include:

- route;
- route CNG candidates;
- station detail;
- route recalculation with chosen stop;
- health/readiness/data freshness.

Generate/maintain OpenAPI.

Acceptance:
- contract tests;
- error model;
- example requests/responses;
- live human curl tests.

## Phase 8 — Android client foundation

Goal:

- Kotlin/Compose app skeleton;
- MapLibre map;
- API client;
- route preview;
- state/domain separation;
- display a backend route and basic maneuvers/data.

Acceptance:
- local Android tests/build where agent environment permits;
- human builds/installs on device;
- route preview renders against test server.

## Phase 9 — Android CNG Add-Stop workflow

Goal:

Implement the exact product workflow:

- Add stop;
- Metano/CNG;
- max detour prompt;
- loading/error states;
- map + ranked list;
- station detail fields;
- call phone action;
- choose station;
- recalculate route with waypoint.

Acceptance:
- device test by human;
- station values match API;
- route changes correctly after selection.

## Phase 10 — Traffic provider framework + traffic-aware routing

Goal:

- implement traffic-provider abstraction;
- deterministic fixture provider;
- traffic freshness state;
- Valhalla traffic ingestion/update path;
- select and integrate a real provider only after licensing/data availability is explicitly evaluated;
- use traffic-aware ETA and detour when valid live data exists;
- fallback when absent/stale.

Acceptance:
- deterministic congestion test changes route cost predictably;
- stale data fallback test;
- human validates live provider if configured.

## Phase 11 — Predictive CNG refuelling

Goal:

- vehicle profile;
- nominal and effective range;
- remaining range state;
- safety reserve;
- reachable route segment;
- proactive refuel trigger;
- candidate selection within safe reachability;
- uncertainty handling.

Reuse the same candidate/ranking API rather than building a separate incompatible system.

Acceptance:
- synthetic trip tests;
- never proposes unreachable stop under modeled assumptions;
- mobile can surface proactive recommendation.

## Phase 12 — Turn-by-turn navigation and rerouting

Goal:

- maneuver consumption;
- navigation state;
- location progress;
- off-route detection;
- rerouting;
- selected fuel waypoint persistence;
- background/mobile lifecycle behavior.

Keep routing intelligence server-side unless an offline mode explicitly requires local routing.

## Phase 13 — Offline/degraded mode

Goal:

Define realistic behavior when:

- API unavailable;
- traffic unavailable;
- price data stale;
- OSM enrichment missing;
- mobile connectivity intermittent.
- cached route;
- cached CNG stations;
- cached vector tiles;
- non-traffic routing fallback;
- explicit freshness UX.

## Phase 14 — Hardening, observability and performance

Goal:

- profiling;
- DB/routing query optimization;
- candidate and matrix batching;
- rate limiting where needed;
- structured logs;
- health/readiness;
- backup/restore docs;
- update strategy for MIMIT, OSM and Valhalla tiles;
- security review;
- load tests.

---

# Rules for every phase

For the requested/current phase, provide and implement:

1. objective;
2. files/components affected;
3. data flow;
4. domain decisions;
5. implementation;
6. tests;
7. migrations/configuration;
8. documentation;
9. Docker changes;
10. acceptance criteria;
11. exact human live-test handoff.

When code is needed, modify the repository rather than merely describing code.

Do not output giant hypothetical code dumps instead of making repository changes.

Do not proceed beyond the phase requested.

If a design issue from a future phase must be decided now to avoid a bad interface, create the smallest necessary abstraction or ADR and stop there.

---

# Start instruction

First inspect the repository and `AGENTS.md`.

Then implement the minimum repository foundation required.

At the end, stop and give the human the exact Docker/bootstrap validation commands to run on the live test server and the outputs that should be returned to you.

Be VERY descriptive on what need to be done on the server to test the hand-off.
