# Phase 6 acceptance criteria

Phase 6 adds station-ETA opening evaluation and an explainable ranking stage over Phase 5 eligible
candidates. It does not add traffic, station selection/route recomputation, the Phase 7 public mobile
contract, predictive reachability or Android UI work.

## Implemented gate

- OSM `opening_hours` uses a pinned standards-oriented parser and explicit Italy/timezone context.
- Every expression is evaluated at the road-network station ETA converted to `Europe/Rome`.
- States are explicitly `open`, `closed` or `unknown`; validation separately reports valid, missing
  or invalid syntax.
- Weekday, weekend, overnight, fixed-offset conversion, valid-unknown, missing and invalid cases have
  deterministic tests.
- Real-world whitespace in comma-separated weekday/holiday selectors is canonicalized only for the
  parser, so `Su, PH off` is closed on Sunday while the original OSM value remains visible.
- Only Phase 5 eligible station IDs are enriched, using one joined query rather than N+1 lookups;
  an empty eligible tuple issues no enrichment query.
- Price selection, source, service mode, observation/ingestion timestamps, age and freshness are
  explicit; missing price does not exclude a candidate.
- The ranking policy combines detour, opening, CNG price and freshness through validated fixed
  weights and returns every component/contribution.
- Closed stations are excluded by default. An explicit opt-in retains them with a strong documented
  penalty; unknown stations remain distinct and included.
- Deterministic tie-breakers and count reconciliation are covered by domain and strict API tests.
- No database migration is required; accepted Phase 2 normalized/link/current-price tables supply
  the enrichment fields.

## Local validation required

```bash
.venv/bin/ruff check .
.venv/bin/pytest
TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@HOST:5432/compass_test \
  .venv/bin/pytest -q tests/test_phase6_postgis.py
docker compose --profile routing config --quiet
docker compose build api
```

The database name guard requires the integration target to end in `_test`; the integration test
resets that database. Normal tests remain network-free and skip PostGIS tests when the URL is absent.

## Local validation result

Validated on 2026-08-29:

- Ruff passed over the complete repository;
- the network-free suite reported 106 passed and four opt-in PostGIS tests skipped;
- all four Phase 2/4/5/6 PostGIS integration tests passed against a disposable PostGIS 16 / PostGIS
  3.5 `compass_test` database;
- the Phase 6 fixture reduced four active stations to two road-eligible candidates, evaluated one as
  open and one as closed on a Friday evening, enriched both with current CNG prices in one query and
  returned only the open candidate under the default policy;
- unit tests cover weekday, Saturday, Sunday, overnight carry, timezone conversion, valid unknown,
  missing/invalid syntax, fresh/stale/future prices, closed opt-in penalty and the zero-candidate
  no-query path; the exact live `Su, PH off` whitespace regression is also covered;
- strict API tests validate availability, provenance, price freshness, component scores and request
  rejection without relying on an external router;
- the checked-in live verifier replaces error-prone inline Python, validates saved API/OpenAPI
  artifacts with standard-library Python and emits a compact operator handoff;
- Compose validated with the routing profile;
- `compass-app:0.1.0` built successfully from the pinned Python 3.12.11 base with
  `opening-hours-py==2.1.4` and `tzdata==2026.3`;
- built-image OpenAPI exposes the five required routing/ranking inputs, optional
  `include_closed=false`, and retains `GPL-3.0-only` package metadata.

No query against the operator's full Italy station dataset or live Valhalla service was attempted
in the repository environment.

## Live gate

Follow the Phase 6 section of `docs/deployment.md`. Acceptance requires operator evidence that the
full-Italy ranked response evaluates availability at offset-aware station ETAs, keeps unknown
distinct, excludes closed candidates by default, exposes price freshness and score components,
sorts deterministically, reconciles all metrics and still uses the bounded Phase 5 route/matrix
pipeline.

## Live validation result

Accepted from the operator's corrected full-Italy rerun on 2026-08-29:

- both default and `include_closed=true` ranked requests returned HTTP 200 and the checked-in live
  verifier completed without an assertion failure;
- 20 network-eligible candidates reconciled to four open, two closed and 14 unknown states; the
  default excluded both closed candidates and returned 18, while the opt-in response returned all
  20;
- every eligible candidate had an explicit MIMIT CNG price, and enrichment used one joined query per
  request;
- MIMIT station `51740` with `Mo-Sa 06:30-12:30, 14:30-19:00; Su, PH off` evaluated `closed` at its
  Sunday `2026-08-30T11:48:13+02:00` ETA, exposed the next opening at 06:30 Monday, carried the
  configured `0.25` closed multiplier in the opt-in response and was absent from the default;
- the bounded network stage retained 325 corridor candidates, sent 200 to matrices, found 183
  reachable and 20 detour-eligible, and made one base-route plus ten matrix calls per request with no
  fallback splits, matrix location failures or per-candidate route calls;
- filtered Valhalla logs showed two `/route` and twenty `/sources_to_targets` requests for the two
  ranked calls; API logs showed two HTTP 200 responses;
- OpenAPI retained the five required inputs and `include_closed=false`, while Alembic remained at
  `0002 (head)`.

The first live attempt had exposed the whitespace-sensitive upstream parsing error for `Su, PH off`;
that contradictory response was rejected, corrected and rerun rather than accepted. The rerun above
proves the regression fix against the live normalized station and completes the Phase 6 gate.
