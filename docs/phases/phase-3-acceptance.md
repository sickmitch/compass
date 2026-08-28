# Phase 3 acceptance criteria

Phase 3 adds reproducible Valhalla bootstrap and normal A-to-B routing. It does not add CNG station
candidate selection, detour calculations, opening-hours evaluation, ranking or traffic.

## Implemented gate

- The official Valhalla 3.8.3 scripted image is pinned by manifest digest.
- One-shot tile construction and the persistent router share a named volume and have separate
  Compose profiles.
- The physical graph volume is configurable so refreshes can be built and rolled back without
  overwriting the active graph.
- The build workload checks PBF registration and rejects the observed partial graph artifacts; only
  the serving workload uses fast graph reuse. Acceptance separately captures a content SHA-256.
- Italy is the default extract; a regional or immutable PBF URL is configurable.
- The routing service is internal-only and has a `/status` healthcheck.
- The backend uses an async provider protocol isolated from Valhalla HTTP details.
- Provider requests have explicit connection/read timeouts and request automobile, kilometre,
  Italian-instruction, polyline6 responses.
- The strict `POST /api/v1/routes` contract returns metres, seconds, geometry and maneuvers.
- Invalid input and provider failures return stable machine-readable error codes.
- Readiness distinguishes database and routing state; liveness claims neither dependency.
- Checked-in fixtures and mocked transports cover request translation, successful normalization,
  malformed responses, no-route, provider outage and API contracts.

## Local validation required

```bash
.venv/bin/ruff check .
.venv/bin/pytest
docker compose --profile routing --profile routing-build config --quiet
docker compose build api
```

The normal automated suite must remain network-free. Building the full Italy graph is intentionally
an operator live test rather than a unit-test prerequisite.

## Local validation result

Validated on 2026-08-27:

- `ruff check .` passed;
- the network-free suite reported 46 passed and one skipped; the skip is the opt-in Phase 2 isolated
  PostGIS test because `TEST_DATABASE_URL` was not configured;
- Compose configuration validated with both routing profiles enabled, including resolution of an
  operator-supplied physical graph volume name;
- `compass-app:0.1.0` built successfully from the pinned Python 3.12.11 base;
- an ephemeral run of the built image generated OpenAPI containing
  `base_route_api_v1_routes_post` at `/api/v1/routes`.

No full Italy PBF download or live route was attempted in the repository environment.

## Live gate

Follow the Phase 3 section of `docs/deployment.md`. Phase 3 is accepted only after the operator
returns evidence that the tile build exits successfully, Valhalla becomes healthy, API readiness
reports both dependencies ready, and a representative Italian A-to-B request returns non-empty
polyline6 geometry and maneuvers with positive distance and duration.

## Live validation result

First operator attempt on 2026-08-28 exposed a partial graph volume: the PBF was present without a
recorded hash, `use_tiles_ignore_pbf=True` skipped reconstruction, and extract creation failed with
`Couldn't find usable tiles`. API liveness remained correct while readiness and routing returned
503. The builder policy was corrected to inspect PBF registration.

The corrected retry completed the full Italy graph: 10,111,264 graph nodes, 25,452,074 directed
edges, 1,597 archived tiles, successful enhancement/validation and a clean one-shot exit. The
scripted image's printed `d684...f996` value is the SHA-256 of the retained path, not PBF content;
the explicit content identity was captured separately.

Final operator validation passed on 2026-08-28:

- `db`, `api`, and the digest-pinned Valhalla 3.8.3 service were healthy; migration `0002` exited
  zero.
- Valhalla loaded all 1,597 archived tiles and `/status` returned version `3.8.3` with `route`
  available.
- The retained Italy PBF was 2.1 GB with content SHA-256
  `f37599322676f4367059360886cf028a4ef6d1fb9d42c5eedf6bb562297ac409`; the tile archive was
  2.3 GB.
- API readiness returned HTTP 200 with both `database=ready` and `routing=ready`.
- The Milan A-to-B API request returned `provider=valhalla`, 4,289 metres, 565.035 seconds, a
  non-empty polyline6 geometry and 16 Italian maneuver objects with shape indexes.
- Missing `traffic.tar` warnings were expected: Phase 3 intentionally configures no traffic input
  and makes no traffic-aware routing claim.

These results satisfy every Phase 3 acceptance criterion. Phase 4 may begin only when explicitly
requested.
