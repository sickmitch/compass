# Explicit OSM topology → versioned Valhalla associations

## Implementation and limits

The import now also requests highway ways containing relation member nodes and stores their
node order/geometry as source evidence. It does not search roads by camera proximity.
Standalone camera-facing direction is NOT treated as vehicle travel direction.

The association job supports relations with exactly one from and one to node, present once
on exactly one common highway way. Their order determines forward/reverse traversal. Missing
or multiple endpoints, ambiguous shared ways, closed-way ambiguity, missing geometry and
multi-way sections remain explicitly unmatched/ambiguous. Standalone nodes remain
unknown_direction; direction tags are preserved for display/provenance only.

Valhalla edge_walk must accept the actual OSM shape, with no nearest-road fallback. Every
returned edge must reference the expected OSM way and forward flag. Graph IDs, network lengths
and geometry must be valid; matched endpoints must be within 10 m of source endpoints.
A matched result stores the directed edge sequence and network distance, not Euclidean section
length. Source geometry is capped at 512 points, output at 512 edges/5000 shape points and 50 km.
This establishes a conservative association for the supported subset, not universal coverage.

Migration 0005 stores import ID, Valhalla version/tileset timestamp, state, reason, evidence
and match time. The job checks graph identity before and after processing, aborting publication
if it changed. It also locks/checks each source row before writing so concurrent reimports
cannot publish evidence against a different source version. One failed provider call aborts
the batch; no partial batch is published. Non-walkable geometry is recorded as unmatched.
The default batch limit is 100 (maximum 1000); requests are sequential with a reused client.
Relations run before standalone nodes. Repeated jobs skip records current for source+graph.

GET /api/v1/enforcement/association?source_key=osm:relation:ID checks the serving graph and
source import before exposing evidence. Changed versions return stale with empty evidence.
The ordinary inventory endpoint still reports unmatched: it has not yet been wired to warnings.
All responses retain alert_eligible=false. Physical device aliases must NOT merge opposite
from/to associations: future warning deduplication needs direction as well as device identity.

Still pending: associations spanning multiple ways, more complete Tutor relations, Android
map markers/voice/visual warning integration, offline road packages, free-follow continuous
matching, average-speed tracking and scheduled acquisition/association. No APK in this increment.

## Local validation

    .venv/bin/ruff check src tests scripts migrations
    .venv/bin/pytest -q -m 'not integration'
    .venv/bin/python scripts/export-openapi.py --check
    .venv/bin/alembic upgrade head --sql
    docker compose --profile jobs config --quiet

Tests exercise topology direction independent of member order, parallel/common-way ambiguity,
missing geometry/direction, graph ID/direction mismatches, invalid distances, endpoint mismatch,
exact edge walking, graph identity and graph replacement during a batch. Database transactions
and the enriched Overpass query require server validation; no live success is claimed locally.

Local result: 408 non-integration tests passed; Ruff, OpenAPI consistency, offline migration
SQL generation, Compose validation and diff checks passed. Six database integration tests
were deselected; no live association/import or Android device test was run by the agent.

## Operator handoff

Working directory ~/docker/compass, existing .env credentials and database backup available.
Sync, rebuild shared API/ETL image, apply migration, and reimport once to acquire road membership:

    git pull --ff-only
    docker compose build api
    docker compose run --rm migrate
    docker compose up -d --no-deps api
    docker compose --profile jobs run --rm etl enforcement
    docker compose --profile jobs run --rm etl enforcement-associate --limit 100
    docker compose exec -T api python3 < scripts/check-enforcement-associations.py

The import contacts public Overpass. Association uses only internal Valhalla/PostGIS.
Return both ETL JSON results and the diagnostic output. Expect graph_identity, processed,
states and matched_sample from association. matched entries must have edges and positive
network length in the read API. unmatched/ambiguous/unknown_direction are valid outcomes;
zero matches needs investigation of reasons, not relaxed carriageway checks.

If it fails, return the full job traceback plus:

    docker compose logs --since 10m --tail 100 api valhalla

After a map/source update, the evidence endpoint must report stale until the job reruns.
The next integration step depends on real association results, especially reversed roads and
Tutor geometry; standalone nodes are intentionally not considered safe for directional alerts.

References: [OSM enforcement member roles](https://wiki.openstreetmap.org/wiki/Relation:enforcement),
[camera-facing direction](https://wiki.openstreetmap.org/wiki/Tag:highway%3Dspeed_camera),
[Valhalla map matching](https://valhalla.github.io/valhalla/api/map-matching/),
[Valhalla graph status](https://valhalla.github.io/valhalla/api/status/).
