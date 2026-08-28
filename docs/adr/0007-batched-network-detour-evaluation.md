# ADR 0007: Batched network detour evaluation

- Status: accepted
- Date: 2026-08-28

## Context

Phase 4 deliberately returns only a cheap spatial candidate set. Its distance-to-route value cannot
answer whether a station is reachable by road, how far it is from the previous waypoint, or whether
visiting it satisfies a caller's maximum detour. Calling the full route endpoint once per Italian
station would make request cost proportional to the national inventory and discard Valhalla's
matrix capabilities.

The current routing graph has no historical, predicted or live traffic overlay. Phase 5 must not
describe graph-speed estimates as traffic-aware or silently invent traffic freshness.

## Decision

Compass evaluates only the candidate tuple already returned by Phase 4's indexed corridor query.
The default upper bound remains 200 candidates. For each configurable batch of 40 candidates it
sends two asymmetric Valhalla `/sources_to_targets` requests:

```text
previous waypoint -> candidate stations     one-to-many
candidate stations -> destination           many-to-one
```

The clean-path minimum is `2 * ceil(candidate_count / batch_size)` matrix calls and zero full route
calls per candidate. Valhalla's verbose matrix response is used so every source and target index can
be validated. Distances are converted from kilometres to metres at the provider boundary. A pair
with both time and distance set to `null` is unreachable; a partial or malformed pair rejects the
provider response.

Valhalla rejects an entire matrix with error 171 if any input cannot correlate to a suitable edge.
For that specific failure, Compass binary-splits the affected batch until it isolates each bad
candidate. Valid siblings retain their costs and each isolated bad coordinate becomes unreachable.
Every split adds four matrix attempts, and response metrics expose split and location-failure
counts. Other provider failures remain request failures and are not masked by retries.

For each candidate with both legs:

```text
via_duration = previous_to_station_duration + station_to_destination_duration
detour_duration = max(0, via_duration - base_route_duration)

via_distance = previous_to_station_distance + station_to_destination_distance
extra_distance = max(0, via_distance - base_route_distance)
```

Clamping only protects the public extra-cost fields from small negative differences caused by
independent route/matrix snapping. The component and total road costs remain visible. A candidate is
eligible when its detour duration is less than or equal to the caller's maximum; equality is
intentional. No refuelling dwell time is added in this phase.

The caller must supply a timezone-aware `departure_at`. Compass derives station and destination
ETAs by adding graph travel durations while preserving the supplied offset. This is elapsed-time
arithmetic, not opening-hours evaluation.

The response reports `traffic_state=not_configured`, `traffic_aware=false`, graph-speed duration
semantics, matrix counts, unreachable and threshold-excluded counts, one base-route call and zero
per-candidate route calls. Eligible candidates are ordered deterministically by detour duration,
extra distance, road distance from the previous waypoint and station ID. This is not the
multi-factor station ranking planned for a later phase.

## Consequences

- Expensive network evaluation is bounded by the post-corridor candidate limit rather than the
  all-Italy station count.
- The mobile contract receives road distance from the previous waypoint, both route legs, total
  via-station cost, extra cost and ETAs without depending on Valhalla response fields.
- A Phase 4 response limit can omit a spatial candidate before network evaluation. The response
  keeps `candidate_limit_applied` and pre-limit counts visible rather than claiming exhaustive
  eligibility.
- Matrix batch size can be tuned to the deployed Valhalla service limits without changing detour
  semantics.
- An uncorrelatable station cannot discard valid results from the same initial batch; fallback work
  remains bounded by the already-pruned candidate tuple and is observable.
- Opening status, price, multi-factor ranking, route recomputation with a selected stop and traffic
  ingestion remain outside Phase 5.

## References

- [Valhalla time-distance matrix API](https://valhalla.github.io/valhalla/api/matrix/)
- [Valhalla 3.8.3 OpenAPI contract](https://github.com/valhalla/valhalla/blob/3.8.3/docs/docs/api/openapi.yaml)
