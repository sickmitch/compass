# Phase 5 acceptance criteria

Phase 5 adds batched road-network detour eligibility over Phase 4's spatially pruned CNG stations.
It does not evaluate opening hours, rank by price or station quality, ingest traffic, calculate fuel
reachability, or recompute a selected multi-waypoint route.

## Implemented gate

- The provider-neutral routing boundary supports validated time/distance matrices.
- The Valhalla adapter uses one-to-many and many-to-one `/sources_to_targets` calls, converts
  kilometres to metres, validates dimensions/indices and preserves unreachable pairs.
- Only the candidates returned by indexed Phase 4 pruning enter matrix evaluation.
- A configurable batch size defaults to 40; the service makes two matrix calls per non-empty batch
  and zero full route calls per candidate.
- A Valhalla location-correlation failure is isolated through bounded binary batch splitting;
  unaffected candidates remain evaluated and fallback calls are observable.
- Detour and extra-distance math uses road-network legs and an inclusive user maximum.
- The response exposes road distance/duration from the previous waypoint, the onward leg, total
  route-via-station cost, extra cost and timezone-aware station/destination ETAs.
- Metrics reconcile spatial, evaluated, reachable, unreachable, eligible and excluded counts.
- Cost metadata explicitly states that external traffic is not configured and durations are not
  traffic-aware.
- `POST /api/v1/cng/detour-candidates` is strict and requires a timezone-aware `departure_at`.

## Local validation required

```bash
.venv/bin/ruff check .
.venv/bin/pytest
TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@HOST:5432/compass_test \
  .venv/bin/pytest -q tests/test_phase5_postgis.py
docker compose --profile routing config --quiet
docker compose build api
```

The database name guard requires the integration target to end in `_test`; the integration test
resets that database. Normal tests remain network-free and skip PostGIS tests when the URL is absent.

## Local validation result

Validated on 2026-08-28:

- Ruff passed;
- the network-free suite reported 79 passed and three opt-in PostGIS tests skipped;
- all three Phase 2/4/5 PostGIS integration tests passed against a disposable PostGIS 16 / PostGIS
  3.5 database;
- the Phase 5 fixture reduced four active stations to three geocoded and then two corridor
  candidates, and only those two entered two matrix calls with zero per-candidate route calls;
- deterministic fallback tests prove one uncorrelatable station does not discard valid siblings;
- malformed Valhalla matrix costs, including non-finite numeric values, are rejected at the
  provider boundary;
- Compose validated with the routing profile and retained its pinned Python, PostGIS and Valhalla
  images;
- `compass-app:0.1.0` built successfully from the pinned Python 3.12.11 base;
- built-image OpenAPI contains the strict Phase 5 endpoint with all five request fields required,
  and the image resolves the default matrix batch size to 40;
- the rebuilt distribution retained `GPL-3.0-only` and the installed `LICENSE` metadata.

No query against the operator's full Italy station dataset or live Valhalla matrix service was
attempted in the repository environment.

## Live gate

Follow the Phase 5 section of `docs/deployment.md`. Acceptance requires operator evidence that the
known Milan-to-Bologna route returns deterministic, internally consistent detour results; evaluates
only Phase 4's returned candidates; makes the expected number of matrix calls; makes no full route
call per station; respects the inclusive ten-minute maximum; independently confirms the known San
Zenone Ovest candidate with ordinary route calls; and labels traffic as not configured.

## Live validation result

Accepted on the operator's full-Italy test server on 2026-08-28:

- readiness and `POST /api/v1/cng/detour-candidates` returned HTTP 200;
- the accepted Phase 4 corridor reduced 1,512 active stations to 1,505 geocoded, 325 in-corridor
  and 200 returned candidates;
- the network stage evaluated exactly those 200 candidates: 183 were reachable, 17 unreachable,
  20 eligible within ten minutes and 163 excluded by the threshold;
- Valhalla logs showed one base `/route` call and ten `/sources_to_targets` calls, matching five
  batches of 40 with two asymmetric matrices per batch and no fallback split;
- response metrics reported one base route, zero per-candidate route calls, ten matrix calls, zero
  fallback splits and zero isolated location failures;
- all response count, leg-sum, maximum-detour, deterministic-order and offset-aware ETA assertions
  passed;
- an independent three-route validation of MIMIT station `43690` (San Zenone Ovest) agreed with
  the batched result within 1 metre and approximately 1.2 seconds, comfortably inside the stated
  tolerances;
- cost metadata reported graph speeds with traffic not configured and not traffic-aware;
- Alembic reported `0002 (head)`.

All Phase 5 acceptance criteria are satisfied. Opening-hours evaluation, multi-factor ranking,
selected-stop route recomputation, traffic ingestion and predictive refuelling remain later gates.
