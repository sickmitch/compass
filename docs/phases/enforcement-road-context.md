# Enforcement road context — bounded backend matching

## Evidence received

The operator confirmed import 1 on 2026-11-01: 7,103 OSM source records imported,
9 unresolved relations quarantined. Authenticated spatial API returned HTTP 200 with
nearby controls, unknown direction/limit fields preserved, and alerts disabled. These are
source records, not necessarily 7,103 distinct physical devices. The two pasted import logs
were identical (same timestamp/container); a second distinct import was not established.

## Implemented in this increment

POST /api/v1/enforcement/road-context accepts 3–12 GPS points, each with latitude,
longitude, accuracy_meters and elapsed_seconds relative to the trace start. Maximum window
60 seconds, consecutive gaps <=10 seconds, finite coordinates and plausible movement.
Accuracy above 25 m or displacement below 20 m returns an explicit state without calling
Valhalla. Otherwise one map_snap trace_attributes request uses the existing lifecycle HTTP
pool, bounded search radius, selected attributes and a 10-second request timeout.
No GPS or request-body logging, no database writes and no external data-provider requests.

Matched points must all be direct matches (not interpolated), have valid edge indexes,
be within min(25 m, reported accuracy), and have no route discontinuities. Malformed provider
responses/timeouts return a machine-readable 503. Valid but unreliable matches are uncertain.
A candidate returns directed graph IDs, OSM way IDs, headings, optional forward direction,
optional limit and the last snapped point. It does NOT prove unique carriageway identity.
Graph IDs are returned as strings to avoid precision loss. They are request-scoped:
persistable=false, Cache-Control=no-store. There is no stable graph-version association yet;
they must not be cached as long-lived road identities across nightly graph replacement.

Inventory now includes physical_control_key. A fixed enforcement relation with exactly one
explicit node/device member shares the device node's key. Coordinates alone, from/to members,
Tutor relations and multiple-device relations never cause such aliasing. Original records
remain intact; conflicting speed/direction observations are not silently overwritten.

## Remaining scope

This endpoint is backend infrastructure, not continuous Android free-follow matching.
Camera-to-directed-edge association, ambiguity resolution, graph-versioned offline road
packages, Android invocation/presentation/speech, Tutor tracking and periodic import remain
outstanding. All controls and road-context responses remain alert_eligible=false. No APK.
The implementation does not claim false positives on parallel/opposite roads are solved.

## Local checks

    .venv/bin/ruff check src tests scripts
    .venv/bin/pytest -q -m 'not integration'
    .venv/bin/python scripts/export-openapi.py --check

Tests cover limited traces, movement/accuracy gating without HTTP calls, jumps/time gaps,
missing IDs/headings, discontinuities, interpolated/unmatched points, provider failures,
one HTTP request, and explicit device aliases versus unrelated nearby controls.

Local result: 389 non-integration tests passed; Ruff, OpenAPI consistency and diff checks
passed. Six database integration tests were deselected. Runtime Valhalla/device validation
remains outstanding.

## Server handoff

In ~/docker/compass with existing .env/API credentials, after synchronization:

    git pull --ff-only
    docker compose build api
    docker compose up -d --no-deps api
    docker compose exec -T api python3 < scripts/check-enforcement-road-context.py

No new migration or OSM reimport is required. Return all three printed results:
- moving: HTTP 200, candidate or uncertain; 503 indicates provider integration needs investigation.
- stationary: HTTP 200, insufficient_movement, no Valhalla call.
- invalid_timestamps: HTTP 422.

The moving request is synthetic and tests integration, not real-world matching accuracy.
For failure return the result plus:

    docker compose logs --since 10m --tail 100 api valhalla

Next required validation: actual graph output compatibility before directed camera association;
subsequent real traces must include parallel/opposite roads, ramps and GPS gaps. No live
Valhalla call or physical-device result was produced by the agent.

Reference: [official Valhalla map matching API](https://valhalla.github.io/valhalla/api/map-matching/).
