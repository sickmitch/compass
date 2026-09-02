# ADR 0014: Live traffic provider boundary and Valhalla overlay

## Status

Accepted for implementation.

## Context

Compass must use live traffic for ETA, detour filtering and CNG ranking without replacing Valhalla
or making TomTom objects part of the routing/ranking domain. Valhalla already owns route search and
edge costing. Its live-traffic mechanism uses a `traffic.tar` overlay configured by
`mjolnir.traffic_extract`; the overlay is a matched companion to the routing tiles.

The project currently has access to the base TomTom Traffic API. Therefore the first concrete
TomTom adapter targets Traffic Flow API `flowSegmentData` with `openLr=true` probe requests. This
adapter is useful for live development and corridor probing, but it is not equivalent to a
nationwide bulk traffic feed.

TomTom Intermediate Traffic / Orbis remains the preferred future production bulk-feed adapter
because it exposes server-side traffic feeds with segment speeds, travel time, confidence,
congestion information, closures and OpenLR/OSM referencing. Other providers, including HERE
Traffic API v7 or Italian DATEX II/CCISS incident feeds, must remain possible through additional
adapters.

## Decision

Compass introduces a provider-independent `compass.traffic` subsystem:

- provider adapters normalize external feeds into `TrafficFlowSegment`;
- provider-specific payloads do not cross the traffic boundary;
- traffic quality policy rejects stale, low-confidence or physically invalid observations;
- segment-to-Valhalla matching is abstracted behind `TrafficEdgeMatcher`;
- traffic edge updates target Valhalla directed edge IDs, never OSM way IDs directly;
- Valhalla remains the only component that computes traffic-aware routes;
- API/domain code reports traffic state and whether durations are traffic-aware, but does not apply
  traffic penalties after routing.

The implementation includes the boundary, mock fixtures, TomTom adapter, dynamic traffic health,
time-dependent Valhalla requests, Docker service placement and a native transactional writer.
Provider-driven writes require confidence, verified direction and an exact tileset identity.

The operator accepted the synthetic live gate on 2026-08-31: current routing increased by 630.644
seconds after injecting 5 km/h on twelve directed edges, while the same request without `date_time`
remained unchanged. The original overlay was restored after the test.

Valhalla 3.8.3 parses and serializes OpenLR but exposes no stock OpenLR-to-GraphId CLI/API. Compass
therefore decodes ordered LRPs with a native helper linked against that exact Valhalla build,
verifies provider-geometry direction against the OpenLR endpoints, and obtains directed GraphIds
through read-only `/trace_attributes` geometry matching. OSM Way IDs remain hints only.
Direction-unverified results are never eligible for overlay writes.

The operator accepted the standalone native direction gate on 2026-08-31: all three TomTom base
Flow Segment geometries aligned to their first/last OpenLR LRPs within one metre, while reversed
alignment differed by kilometres. This evidence permits integrating the same check into the
matcher; it does not enable provider overlay writes by itself.

The integrated matcher gate was subsequently accepted for the same three records. All matches had
verified direction, met the configured quality and match thresholds and were write-eligible for the
active tileset. The gate forced overlay writes off and proved `traffic.tar` remained byte-for-byte
unchanged.

Before a provider writer is enabled, Compass creates a deterministic `TrafficOverlayPlan`. The
planner expands accepted observations to whole-edge updates, retains omitted observations only
until expiry, resets expired edges to Valhalla's unknown live speed and reserves zero exclusively
for explicit closures. Its durable state schema includes tileset and mapping identities plus
provider provenance. Loading state from another tileset fails closed.

Provider plans are applied as one native batch. All targets are validated before mutation and
original TrafficSpeed/header values are retained for in-process rollback. Durable state is replaced
atomically only after the batch succeeds; a state-save failure triggers an inverse overlay plan.
The controlled writer and periodic updater gates passed on 2026-09-01. One TomTom probe populated
six matched edges at 123 km/h (124 km/h after Valhalla encoding); both gates then reset every owned
edge to `UNKNOWN`, left durable state empty and restarted Valhalla on the clean overlay. The
periodic gate additionally proved that provider credentials were absent from logs.

Updater runtime health is a separate atomic, API-readable JSON snapshot in the private Valhalla
volume. The API mounts that volume read-only and derives `fresh`, `stale` or `unavailable` without
fetching the provider. Health records carry provider and tileset identities; mismatches or malformed
files fail closed. Health reporting failure never mutates an already committed overlay transaction.

## Routing semantics

When `TRAFFIC_ENABLED=true` and `TRAFFIC_VALHALLA_OVERLAY_ENABLED=true`, the Valhalla adapter sends
time-dependent auto requests with:

```json
{
  "date_time": {
    "type": 0
  },
  "costing_options": {
    "auto": {
      "speed_types": ["current", "predicted", "constrained", "freeflow"]
    }
  }
}
```

For routes with an explicit scheduled departure, the adapter uses Valhalla departure-time semantics
with `date_time.type=1`. The public Compass timestamp remains an offset-aware instant; the adapter
converts it to the configured routing timezone and removes the offset because Valhalla requires a
local `YYYY-MM-DDTHH:MM` value. When traffic is disabled or the overlay is not enabled, Compass
keeps the previous request shape and reports graph-speed durations as non-traffic-aware.

## Tile and mapping invariant

Valhalla directed edge IDs are not stable across arbitrary tile rebuilds. Therefore these artifacts
are a matched set:

- routing tiles / tile extract;
- `traffic.tar`;
- provider-segment to directed-edge mapping/index;
- configured `TRAFFIC_VALHALLA_TILESET_VERSION`;
- configured `TRAFFIC_MAPPING_VERSION`.

After a Valhalla graph rebuild, old traffic mappings must be treated as invalid. Live traffic must
remain disabled until the overlay and mappings are rebuilt for the new tileset identity.

## Consequences

- Compass can run with `TRAFFIC_ENABLED=false` and preserve current behavior.
- `TRAFFIC_PROVIDER=mock` enables deterministic offline traffic development.
- TomTom can be added without leaking provider objects into ranking/routing contracts.
- Bad or unavailable traffic data degrades to normal Valhalla speeds instead of taking routing
  offline.
- `traffic-updater` has a dedicated image based on the same pinned Valhalla image as routing, so
  native OpenLR and traffic definitions cannot silently drift from the router ABI.
- Durable managed-edge state lives in the same private volume lifecycle as the matching tiles and
  `traffic.tar`; replacing that volume cannot leave a detached GraphId state behind.
- The dedicated updater commits tileset-bound batch plans only after route-scoped internal
  requests; a provider-free sweep resets expired records;
  provider failures retain valid observations and reset expired owned edges to `UNKNOWN`.
- Base TomTom Flow Segment probes provide configured point coverage, not a nationwide feed.
