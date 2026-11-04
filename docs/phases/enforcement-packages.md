# Versioned enforcement snapshots for client integration

## Evidence and scope

On 2026-11-02 the operator completed all 1,900 imported relations against graph
3.8.3:1793503103: 785 matched, 1,001 ambiguous, 114 unmatched. Source enrichment was present
for all relations. This validates the association pipeline on the supported subset; it does
not establish nationwide physical-camera coverage or Android warning correctness.

This increment adds GET /api/v1/enforcement/map-package. It prepares a bounded contract shared
by active-navigation and free-follow clients. Android consumption, persistent offline cache,
continuous local matching, visual/voice warnings and Tutor tracking are not implemented here.
No APK is built. alert_eligible remains false even for matched controls: matching a control to
an OSM/Valhalla road does not establish that a particular vehicle is approaching it.

## Operator acceptance

The operator returned HTTP 200 for the new endpoint: four controls, two matched and two unmatched,
truncated=false and any_alert_eligible=false. Both matched relations for physical device
osm:node:5739547477 retain opposite forward flags and distinct edge IDs. This accepts the live
snapshot contract on that sample. See [Android map consumption](android-0.28.53-enforcement-map.md)
for the subsequent client increment and its remaining limits.

## Contract

Authenticated query parameters are latitude, longitude, radius_meters (100–10000; default 2000).
The snapshot contains schema_version=1, graph_identity, generated_at, requested spatial extent,
controls, truncated and OSM/ODbL attribution. Existing inventory/association endpoints are unchanged.

Each control retains its source key, physical-device alias, source import ID, kind, location,
source-facing direction, optional limit, fetched_at and association state/reason. Different
relations sharing a device are not merged: opposite directed approaches must stay distinguishable.
`direction_degrees` describes source camera orientation, not a vehicle approach heading.

Only a matched association with the current source import AND graph identity contains a
directed_path: OSM way, from/to node IDs, forward flag, directed edge IDs, polyline6 geometry
and road-network length in meters. IDs are strings to preserve integer precision. Polylines
use 1e-6-degree latitude/longitude precision. The original raw OSM payload is not exposed.
Obsolete associations become stale with no path. Legacy geometry absence and malformed stored
proofs have explicit states; malformed evidence is not turned into a usable association.

One spatially indexed query joins controls to associations, outside the async event loop.
Results are ordered by distance/source key and capped at 200 records and 2 MB serialized JSON;
truncated=true means the returned set is incomplete. A package containing zero controls is not
proof that no enforcement exists in the area. There is no per-control Valhalla call.

Two internal Valhalla /status reads bracket the query/serialization. A graph change rejects the
entire response with 503 enforcement_graph_changed. Graph/DB failures return sanitized 503
errors, not an apparently empty successful snapshot. This endpoint never calls Overpass,
TomTom or another external provider and never writes database records.

The spatial basis is explicitly control_location: a long Tutor section intersecting the radius
may be omitted if its inventory anchor is outside. This is a local control snapshot, not a
complete routing graph or guaranteed route-wide warning package. Connecting sections to routes,
carriageway ambiguity resolution and graph-aware offline matching remain separate work.

Generated time is packaging time; fetched_at remains the source acquisition time. Neither
certifies that a camera is currently operational. Graph matching is verified at response time,
not indefinitely while offline. HTTP caching requires revalidation. A future offline client
must retain attribution, version/source metadata and explicit incomplete/unknown coverage;
it must not treat cached graph IDs as current after a different graph version is observed.

## Validation and handoff

Local checks:

    .venv/bin/ruff check src tests scripts migrations
    .venv/bin/pytest -q -m 'not integration'
    .venv/bin/python scripts/export-openapi.py --check

Unit/HTTP tests cover graph/source invalidation, malformed evidence, string ID precision,
opposite approaches sharing a physical alias, count/byte caps, provider failure, graph
replacement during a request and input bounds. The dedicated PostGIS test additionally checks
the real spatial join and stale-evidence exclusion; it requires TEST_DATABASE_URL to point to
an isolated database whose name contains test. Never use production credentials for that test.

Local result: 445 non-integration tests passed; six database integration tests deselected.
Ruff, OpenAPI consistency and diff checks passed. No live request or device test was run by
the agent.

Operator working directory ~/docker/compass, existing .env/API credentials retained:

    git pull --ff-only
    docker compose build api && docker compose up -d --no-deps api
    docker compose exec -T api python3 < scripts/check-enforcement-associations.py

No migration, reimport or additional association job is required. The diagnostic performs one
map-package request near a previously associated control and prints a compact summary.
Expected: map_package 200, current graph identity, matched records with directed paths,
any_alert_eligible=false. The diagnostic also prints forward flags and a bounded edge-ID sample;
equal physical aliases do not imply equal traversal direction. Return the full diagnostic output.

On failure return the HTTP result and:

    docker compose logs --since 10m --tail 100 api valhalla

This live gate checks the new spatial API against the operator's actual database and Valhalla.
It does not require another public Overpass acquisition. Once accepted, the next increment is
Android package consumption/cache and map presentation shared by navigation and free follow;
warning eligibility requires vehicle-road evidence in addition to this control-road evidence.
