# Explicit OSM topology → versioned Valhalla associations

## Implementation and limits

The import first requests controls and their members, then requests highway ways containing
explicit from/to nodes in sequential batches of at most 200 node IDs. It stores their node
order/geometry as source evidence. It does not search roads by camera proximity.
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

## Acquisition failure and legacy data

Only transient HTTP 429/502/503/504 and selected transport failures are retried, at most three
attempts per request with 10/30-second backoff. Retry-After is respected up to 120 seconds;
a longer or invalid server delay aborts instead of retrying too early. No parallel requests
or automatic rotation across public instances is used. The configured OVERPASS_URL,
HTTP_TIMEOUT_SECONDS and OVERPASS_MAX_DOWNLOAD_BYTES remain applicable; the byte budget
covers all successful batch responses together and the combined catalog.

A failed batch, invalid JSON or Overpass remark aborts acquisition before opening the importer
transaction. Previously imported controls and associations remain in the database. Smaller
queries/retries mitigate transient failures; they do not guarantee public provider availability.
The final fetched_at is acquisition completion time; runtime timestamps are excluded from the
combined content hash so identical inventories remain idempotent.

Relations from legacy imports without the road_ways key are reported as missing_source_geometry
with reason enriched_import_required, even if an old stored association said ambiguous.
The association job returns status=source_geometry_required, processed=0 and a missing count
without writing any associations until those legacy records are refreshed. A present but empty
road_ways list means enrichment was attempted but no supported topology was found; it is distinct
from a legacy import. Reimport changes source versions, allowing old associations to be processed
again automatically. No deletion or additional migration is needed for this repair.

Run association only after successful acquisition. Return the import JSON, association JSON and
diagnostic source_geometry/stored_states/API output. missing_source_geometry should be zero after
a complete reimport; matched is not guaranteed for unsupported or genuinely ambiguous topology.
If missing records persist, return those counts before proceeding to Android warnings.

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

Local validation for the acquisition repair: 422 tests passed, six database integration tests
deselected; Ruff and OpenAPI consistency checks passed. No live association/import or Android
device test was executed by the agent.
The operator reported HTTP 502 before import, followed by 100 ambiguous associations.
These results do not establish road ambiguity: older imports can lack road membership.
The diagnostic script now reports missing_source_geometry explicitly.

## Operator handoff

Working directory ~/docker/compass, existing .env credentials and database backup available.
Sync, rebuild shared API/ETL image, apply migration, and reimport once to acquire road membership:

    git pull --ff-only
    docker compose build api
    docker compose run --rm migrate
    docker compose up -d --no-deps api
    docker compose --profile jobs run --rm etl enforcement && \
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
