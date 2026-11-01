# Enforcement inventory — integration gate 1

Delivered: PostGIS migrations 0003–0004, source-preserving OSM importer, authenticated spatial
inventory API. NOT delivered: Android alerts/map markers, Valhalla directed-road association,
continuous free-follow matching, offline packages, Tutor journey tracking, scheduled import.
This is not the complete requested feature. No new APK is published at this gate.

The importer reuses the existing bounded Overpass downloader, country configuration,
timeouts and Docker ETL image. Fixed camera nodes and maxspeed/average_speed relations
retain original tags and resolved members. Source-qualified identities permit a future MIT
adapter; MIT schema, licensing and availability are not yet verified. Nearby features are
not merged by distance; node/relation duplicates representing one device remain distinct
source records until semantic association, and must not produce duplicate driving alerts.
Unknown direction/speed stays null. Numeric direction is source metadata, not a verified
Valhalla travel direction. A Tutor relation is not converted to a straight-line section.

Imports are transactional, same-scope locked and content-hashed. Empty/explicitly partial
Overpass responses fail without modifying inventory. Complete changed snapshots retire
absent records in the imported scope. --input-file MUST be a complete extraction for the
configured country, never an arbitrary subset. Identical content reuses its import ID and
original fetch timestamp. Fetch time is not an observation of camera operational status.

GET /api/v1/enforcement accepts latitude, longitude and radius_meters (100–10000), returns
at most 500 records plus a truncation flag and OSM attribution. Geography/GiST supports
bounded spatial queries. Every item remains association_state=unmatched and
alert_eligible=false; inventory proximity must not be used to issue directional warnings.

## Local checks

    .venv/bin/ruff check src tests migrations
    .venv/bin/pytest -q -m 'not integration'
    .venv/bin/python scripts/export-openapi.py --check

Optional dedicated PostGIS test (never production credentials; database name must contain
"test"). Applies migrations and removes only its synthetic TEST-scope inventory afterwards:

    TEST_DATABASE_URL='postgresql+psycopg://USER:PASSWORD@HOST/compass_test' .venv/bin/pytest -q tests/test_enforcement_postgis.py

Local result: 361 non-integration tests passed, Ruff and OpenAPI consistency passed;
Alembic generated the complete migration SQL offline. The PostGIS test was skipped because
TEST_DATABASE_URL is not configured. No live import or database migration was executed.

## Operator gate

Working directory: ~/docker/compass on the operator's server. Existing .env database/API
credentials and a database backup are required. Sync and redeploy backend:

    git pull --ff-only
    docker compose build api
    docker compose run --rm migrate
    docker compose up -d --no-deps api
    docker compose --profile jobs run --rm etl enforcement
    docker compose --profile jobs run --rm etl enforcement

These imports contact the public configured Overpass endpoint; the agent has not run them.
Return import statuses/counts and the dedicated PostGIS test output when available.
Identical snapshots must not duplicate records; changed OSM snapshots may create new imports.
To query without exposing credentials:

```bash
docker compose exec -T api python3 - <<'PY'
import base64, json, os, urllib.request
headers = {}
if os.environ.get('API_AUTH_ENABLED', 'true').lower() == 'true':
    token = base64.b64encode((os.environ['API_AUTH_USERNAME'] + ':' + os.environ['API_AUTH_PASSWORD']).encode()).decode()
    headers['Authorization'] = 'Basic ' + token
request = urllib.request.Request('http://127.0.0.1:8000/api/v1/enforcement?latitude=45&longitude=9&radius_meters=10000', headers=headers)
with urllib.request.urlopen(request, timeout=15) as response:
    print(json.dumps(json.load(response), indent=2))
PY
docker compose logs --since 10m --tail 100 api
```

Expected: no migration/query errors, attribution, nullable unknown direction, and all records
ineligible for alerts. Empty regional results do not prove absence of controls. Return this
output and the import results. Real data coverage and server behavior remain unverified.

## Remaining increments

- Directed Valhalla associations based on member sequences/topology, explicit ambiguity and
  graph-version invalidation after nightly rebuilds. Parallel/opposite roads must not match.
- Bounded road packages shared by route and free-follow modes; local continuous matching
  reuses existing filtered GPS with no extra subscription. Reject uncertain junction matches.
- Persistent offline cache, deduplicated speed-based voice/visual warnings and MapLibre
  layers in BOTH modes. Explicit missing/stale coverage; do not promise unknown coverage.
- Tutor entry/exit state machine, monotonic time and matched distance, invalidation on GPS
  gaps/deviation. Never replace road-section length with straight-line distance.
- Scheduled updates using the existing container scheduler after ingestion is validated;
  device/replay acceptance for opposite roads, ramps, tunnels and offline operation.

## Licensing

[OSM camera semantics](https://wiki.openstreetmap.org/wiki/Tag:highway%3Dspeed_camera),
[enforcement relations](https://wiki.openstreetmap.org/wiki/Relation:enforcement).
OSM provenance and contributor attribution are preserved. Offline distribution must retain
[ODbL attribution and derived-database obligations](https://www.openstreetmap.org/copyright).
MIT must remain a separately licensed source; no MIT compliance is claimed.

## Fix for unresolved OSM relations (0004)

A single enforcement relation with no directly resolved device/from/to point no longer
aborts an otherwise valid extraction. These records (and invalid coordinates) are stored
in enforcement_imports.rejected with source key, reason and original/resolved membership.
No centroid, nearby camera or invented directed geometry is substituted. Existing valid
controls with rejected source keys retain their prior location and fetched timestamp;
missing records elsewhere still retire normally. Missing/invalid records are not new alerts.
All-unusable, empty and explicitly partial responses still fail before database writes.

CLI output includes rejected_count and rejected_sample (maximum 20 entries); full rejection
records are in the import row. An identical snapshot returns the same diagnostics. Synthetic
regressions cover unresolved relation 8826292 without claiming its actual live contents
were inspected. PostGIS integration tests additionally check preservation of old coordinates
and persistence of rejection metadata.

From ~/docker/compass on the server, with existing .env credentials:

    git pull --ff-only
    docker compose build api
    docker compose run --rm migrate
    docker compose up -d --no-deps api
    docker compose --profile jobs run --rm etl enforcement

Expected: imported (or unchanged), a positive count, rejected_count and rejected_sample;
unresolved relations must not abort the entire import. Return the final JSON and rerun the
API query above. That API queries only within 10 km of 45,9: an empty regional result alone
does not prove the national catalog is empty. On failure return the complete ETL traceback
and migration output. No Android update is required for this parser/database correction.
