# ADR 0006: Autonomy-aware spatial candidate pruning

- Status: accepted for Phase 4
- Date: 2026-08-28

## Context

A full-Italy station inventory is too large to send through expensive station-by-station road
routing. Compass first needs a cheap, deterministic stage that preserves plausible CNG stops near a
base route and exposes how much work it removed. The stage must respond to usable vehicle range,
reuse PostGIS spatial indexing and remain distinct from road detour evaluation.

## Decision

Compass decodes the provider's polyline6 base-route shape into validated WGS84 coordinates and
passes a transient `LINESTRING` to PostGIS. It does not persist request routes in Phase 4.

The default corridor radius is:

```text
uncapped_radius_km = 0.20 * effective_cng_range_km
radius_km = clamp(uncapped_radius_km, 5 km, 50 km)
```

`effective_cng_range_km` is already reduced by any caller-owned reserve or safety allowance. The
fraction, caps, returned-candidate limit and maximum decoded point count are configuration values.
The response reports the uncapped and applied radius plus which cap was used.

The station query uses the existing `stations.location` GiST index through `ST_DWithin` on
geography. Only active stations with valid locations enter the corridor. It returns cheap
straight-line distance to the route and an approximate line projection fraction for deterministic
pre-ranking. Those values are explicitly not road distance, travel time or detour.

The stage makes exactly one routing call: the A-to-B base route. It reports active counts, missing
location exclusions, corridor count before the response limit, returned count, pruned count,
reduction ratio and whether the limit applied.

## Consequences

- Candidate volume falls before any matrix or full detour computation.
- Range changes corridor width in an explainable, testable way without hard-coding the 20% policy.
- Existing normalized geography and GiST schema remain sufficient; no route-history table or
  migration is added.
- A station spatially near the route is only a possible candidate. Phase 4 does not prove road
  reachability, detour eligibility, opening status or ranking.
- Phase 5 may add batched network-cost evaluation over this reduced set without changing the
  meaning of the spatial metrics.
