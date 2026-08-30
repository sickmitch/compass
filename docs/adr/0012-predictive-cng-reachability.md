# ADR 0012: Complete predictive CNG refuelling itineraries

## Status

Accepted design for Phase 10; corrected live/device gate accepted on 2026-08-30.

## Context

The manual flow ranks stations near a route, but it does not prove that a vehicle can reach a station
before reserve or complete the trip afterward. Android has no trusted telemetry, consumption model,
navigation session or current-location integration, so Phase 10 must use an explicit driver estimate.

The first physical-device gate exposed an important flaw in the initial design. With 65 km remaining,
a 30 km reserve and 100 km effective full range, Compass showed first stations reachable within 35 km
and then calculated a route through only one of them. That route could not reach Bologna while
preserving reserve. The evidence was useful, but the gate was rejected: a safe first stop is not a
safe itinerary.

## Decision

`POST /api/v1/cng/predictive-candidates` keeps the existing range inputs and first computes:

```text
first usable leg = estimated remaining range - reserve
later usable leg = effective full range - reserve
```

If the destination fits in the first usable leg, the result is `not_needed` and candidate work is
skipped. Otherwise Compass runs the accepted corridor and detour pipeline. A candidate is eligible
as the first stop only when its origin-to-station road distance fits the first usable leg. Candidate
enrichment is loaded once.

The planner then searches for a complete, forward-progressing chain. Candidate-to-candidate road
costs come from bounded Valhalla matrices. Every later station leg and the final destination leg must
fit the later usable range. Forward progress is determined by decreasing road-network distance to
the destination, not by Euclidean distance or projected route fraction. Closed stations are excluded
at their predicted arrival time unless the request explicitly permits them.

The response has five states:

- `not_needed`: destination is reachable with reserve;
- `suggested`: a complete reserve-preserving itinerary exists;
- `no_reachable_station`: no safe first stop exists;
- `no_eligible_station`: first stops exist, but none survives arrival-time availability policy;
- `no_complete_itinerary`: a safe first stop exists, but no complete chain reaches the destination.

`suggested` includes ordered stops, each preceding road leg, ETA, opening/price metadata, remaining
range and reserve margin, followed by the destination leg. It explicitly declares
`full_effective_range_after_each_stop` and `road_network`. The compatibility `candidates` array
contains only the ranked first stop; it is not a menu of independently safe complete trips.

Execution uses `POST /api/v1/routes/with-cng-itinerary`. It accepts the ordered official MIMIT IDs,
resolves them in one database query, calls Valhalla once with all stops, and revalidates every actual
route leg with the same range arithmetic. A provider route that violates reserve returns HTTP 409
`cng_itinerary_out_of_range` and is never displayed as valid.

Android presents the complete plan and its assumptions before route calculation. The selected route
draws all stop markers and separates maneuvers by leg. The manual single-stop flow remains unchanged.

## Consequences

- A first reachable station is never sufficient to return `suggested`.
- The reported 65/30/100 profile requires a multi-stop chain on Milan–Bologna; the live validator
  requires at least three refuelling stops and checks every arithmetic margin twice.
- The plan assumes a full refill at each station. Partial fills, refill duration, queues, variable
  consumption, traffic-adjusted consumption and telemetry remain future work.
- The detour threshold remains a per-station eligibility rule, not a global sum over all stops.
- Search is bounded by the already-pruned candidate set and batched matrices; it never performs one
  route request per station.

## References

- [Phase 10 acceptance criteria](../phases/phase-10-acceptance.md)
- [ADR 0006: autonomy-aware spatial candidate pruning](0006-autonomy-aware-spatial-candidate-pruning.md)
- [ADR 0007: batched network detour evaluation](0007-batched-network-detour-evaluation.md)
- [ADR 0011: Android manual CNG-stop workflow](0011-android-manual-cng-stop-workflow.md)
