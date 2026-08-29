# ADR 0008: Arrival-time station availability and explainable ranking

- Status: accepted
- Date: 2026-08-28

## Context

Phase 5 produces stations that satisfy a road-network detour maximum, but its order does not answer
whether a station is open when the vehicle arrives or whether a price is attractive and current.
OSM hours may be absent, invalid or complex (including overnight ranges), while MIMIT can expose
more than one current CNG service-mode price. The mobile client must be able to explain any order and
must never conflate unavailable data with an open station.

## Decision

Compass adds `POST /api/v1/cng/ranked-candidates` as a staged composition over the Phase 5 domain
service. It enriches only eligible station IDs with one database query, or zero queries for an empty
eligible tuple. The accepted OSM link supplies hours, phone, brand, operator and match confidence.
The lowest current CNG unit price across service modes is selected; equal prices prefer the newest
observation and then service mode. MIMIT remains authoritative for price and station identity.

OSM hours are parsed by pinned `opening-hours-py` 2.1.4. Every road-network station ETA is converted
to the configured IANA timezone (`Europe/Rome`) before evaluation with country `IT` and station
coordinates. Results separate:

- `open`, `closed` and `unknown` state;
- `valid`, `missing` and `invalid` expression validation;
- original expression, source/confidence, evaluation instant, next change, parser comment/warnings.

Missing and invalid expressions produce `unknown`; a valid expression can also explicitly evaluate
to `unknown`. Closed stations are excluded by default. `include_closed=true` is an explicit
diagnostic/operator option that retains them with a zero opening component and the configured 0.25
availability multiplier.

The baseline weights are configuration, must each be in `[0, 1]`, and must sum to one:

```text
detour             0.50
opening state      0.25
CNG unit price     0.15
price freshness    0.10
```

Component semantics are:

```text
detour_score = 1 - detour_seconds / maximum_detour_seconds
opening_score = 1 open, 0 closed, 0.25 unknown
price_score = inverse min/max normalization over comparable returned prices
freshness_score = 1 - price_age / 168 hours while age is within that horizon
total = sum(component_score * weight) * availability_multiplier
```

Scores are clamped by their domain rules; a zero detour maximum assigns 1 to candidates already
proven eligible. Missing prices, stale prices and future-at-ETA observations receive zero for the
applicable price component but do not make a station unusable. Price value, source, service mode,
observation/ingestion times, age and freshness state remain visible.

Ordering is descending total score, then ascending detour duration, missing price after present
price, ascending unit price and station ID. The response returns every raw score, weighted
contribution and the multiplier, so no UI needs to reverse-engineer an opaque value.

## Consequences

- Weekday, weekend, overnight and unknown semantics are fixture-testable without a live service.
- Availability is correct for station arrival rather than request time and remains DST-aware through
  IANA timezone rules.
- Database work is bounded by Phase 5 eligibility and avoids N+1 enrichment queries.
- An unmatched or incomplete OSM record remains a usable station with explicit unknown availability.
- Missing price does not exclude a station, while stale/future data cannot gain freshness value.
- Changes to weights or freshness horizon are deployment policy changes visible in every response.
- Traffic ingestion, personalization, selected-stop route recomputation and predictive fuel
  reachability remain later phases.

## References

- [opening-hours-py 2.1.4 API](https://remi-dupre.github.io/opening-hours-rs/opening_hours.html)
- [OpenStreetMap opening_hours specification](https://wiki.openstreetmap.org/wiki/Key:opening_hours)
- [ADR 0007: batched network detour evaluation](0007-batched-network-detour-evaluation.md)
