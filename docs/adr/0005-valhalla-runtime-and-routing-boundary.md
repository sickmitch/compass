# ADR 0005: Valhalla runtime and routing boundary

- Status: Accepted
- Date: 2026-08-27

## Context

Compass needs reproducible road-routing data for Italy, but application code must not leak
Valhalla-specific HTTP fields into the mobile contract. Tile construction is large, changes on a
different cadence from application images and must survive ordinary container rebuilds. Phase 3
requires only a normal A-to-B route; corridor searches, station detours and traffic are later work.

## Decision

Use the official `valhalla-scripted` 3.8.3 multi-architecture image, pinned by manifest digest. A
profile-gated `valhalla-tiles` one-shot workload downloads and builds an operator-configurable
Italy or regional OSM PBF into the `valhalla_data` named volume. A separate profile-gated
`valhalla` service reads the same volume and exposes port 8002 only to the Compose network.

The one-shot builder always sets `use_tiles_ignore_pbf=False`. It therefore checks the scripted
image's retained PBF registration and cannot mistake the observed non-empty partial tile directory
for a valid completed graph. The persistent server sets it to `True` because it only serves a graph
produced by the successful build gate and should not rebuild during service startup.

Valhalla 3.8.3's `file_hashes.txt` values identify PBF paths, not file content. Compass therefore
records a separately computed content SHA-256 during live acceptance. Refreshes use a fresh physical
volume because replacing bytes at the same `*-latest.osm.pbf` path is not a reliable rebuild signal.

The default input is Geofabrik's Italy extract. `VALHALLA_TILE_URLS` may select a smaller regional
extract or an operator-hosted immutable PBF. The build retains source/configuration files and hashes
in the named volume. An exact historical rebuild therefore requires retaining or serving the exact
PBF, because a `latest` URL intentionally advances. Tile reuse is the default; a rebuild must be an
explicit operator choice.

The actual Docker volume name is configurable. A data refresh builds into a new named volume rather
than overwriting the active graph. After the new graph passes its smoke test, the operator activates
that volume through `.env` and recreates Valhalla. Keeping the previous volume provides a direct
rollback path; volume retirement is a separate explicit operator action.

Application code depends on the async `RoutingProvider` protocol. The Valhalla adapter owns request
translation, timeouts, status checks, provider-error classification and response validation. The
versioned API exposes metres, seconds, explicit polyline6 geometry and normalized maneuver fields.
Only automobile costing and exactly two endpoints are public in Phase 3.

API liveness remains process-only. Readiness checks both PostgreSQL and Valhalla and returns an
explicit state for each. Provider failures use stable machine error codes and never expose raw HTTP
or internal exception details.

## Consequences

- Application and tile images can be rebuilt independently without losing routing data.
- Graph updates can be built and rolled back without destructively mutating the active volume.
- Interrupted or partial builds are not silently accepted by subsequent builder runs.
- Valhalla is internal and cannot be exposed accidentally through a default host port.
- Fixture tests exercise routing behavior without downloading maps or contacting public services.
- Mobile clients do not depend on Valhalla's `trip`, `legs`, kilometre or error response shapes.
- The changing default Italy extract is operationally refreshable, while exact replay requires an
  immutable input URL or retained PBF and hash.
- Multi-waypoint routing, candidate matrices, traffic, corridor pruning and station ranking remain
  deliberately deferred.

## References

- Valhalla 3.8.3 scripted-image instructions:
  <https://github.com/valhalla/valhalla/blob/3.8.3/docker/README.md>
- Official Valhalla scripted container package:
  <https://github.com/valhalla/valhalla/pkgs/container/valhalla%2Fvalhalla-scripted>
- Valhalla 3.8.3 OpenAPI route/status contract:
  <https://github.com/valhalla/valhalla/blob/3.8.3/docs/docs/api/openapi.yaml>
