# Public API v1

The runtime contract is [openapi.json](openapi.json). All distances are metres, durations seconds,
timestamps ISO 8601, and CNG prices explicit unit prices (normally EUR/kg). Unknown request fields
are rejected. Errors use `{"code":"...","message":"..."}` except dependency-state health responses.

## Authentication

When `API_AUTH_ENABLED=true`, every `/api/v1` resource requires HTTP Basic authentication. Send the
configured username and password on every request; use HTTPS so those credentials and route data are
encrypted in transit. Missing or incorrect credentials return HTTP `401` with code
`invalid_credentials` and a `WWW-Authenticate: Basic` challenge.

```http
GET /api/v1/auth/check
Authorization: Basic <credentials>
```

The check response confirms whether authentication is enabled without echoing either credential.
`/health/live` and `/health/ready` remain unauthenticated so Docker and a reverse proxy can monitor
the service. Their payloads contain operational status only.

## Place search

```http
GET /api/v1/places/search?q=Duomo%20di%20Milano&limit=8&language=it
```

The endpoint accepts addresses, cities/localities, POI/business/place names and decimal coordinate
pairs. Text is resolved by the configured server-side `PlaceSearchProvider`; Android never talks to
that provider directly. Coordinate input such as `45.4642, 9.19` is normalized locally even when
external geocoding is disabled. For a street address, put the civic in its own comma-separated
segment: `Via Cappafredda, 12, Roverchiara`. Only that explicit segment is interpreted as a civic,
so route numbers such as `SS 434` are not mistaken for house numbers.

Each result contains `display_name`, nullable normalized `address`, `location`, `kind` (`address`,
`locality`, `poi`, `coordinate` or `unknown`), optional `category`/`poi_name`, and provider identity.
The top-level `cacheable` flag tells clients whether that result ordering may be persisted.
Provider outages return `503 search_unavailable`; invalid upstream data returns
`502 search_provider_error` without raw provider details.

With `GEOCODING_PROVIDER=nominatim_google`, all returned records and coordinates still come from
Nominatim. Google Places API (New) is queried concurrently and used only during the request to
corroborate POI name/proximity or exact civic/street/locality/proximity. Explicitly conflicting
civics are omitted. Google failures degrade to query-ranked Nominatim output and emit a bounded
warning without credentials. Because Google can influence ordering, these responses have
`cacheable=false`; Google text, coordinates and identifiers are never returned or stored.

## Station detail

```http
GET /api/v1/cng/stations/43690?arrival_at=2026-08-30T10%3A19%3A11%2B02%3A00
```

Representative response (values are illustrative; source timestamps remain live data):

```json
{
  "mimit_station_id": "43690",
  "name": "S.ZENONE OVEST",
  "address": "A1 km 15",
  "municipality": "SAN ZENONE AL LAMBRO",
  "province": "MI",
  "brand": "Enilive",
  "manager": null,
  "station_type": "Autostradale",
  "is_active": true,
  "location": {"latitude": 45.321004, "longitude": 9.376063, "source": "mimit"},
  "source_observed_at": "2026-08-29T06:00:00Z",
  "updated_at": "2026-08-29T06:10:00Z",
  "price_reference_at": "2026-08-30T10:19:11+02:00",
  "current_cng_prices": [{"fuel_type": "cng", "unit_price": 1.599, "currency": "EUR", "unit": "kg", "service_mode": "served", "observed_at": "2026-08-29T04:00:00Z", "ingested_at": "2026-08-29T06:00:00Z", "source_name": "mimit", "age_seconds": 101951, "freshness_state": "fresh"}],
  "osm": {"osm_type": "node", "osm_id": 123, "name": "San Zenone Ovest", "opening_hours": "24/7", "phone": null, "brand": "Enilive", "operator": null, "source_observed_at": "2026-08-29T05:00:00Z", "match_method": "proximity_v1", "confidence": 0.95, "distance_meters": 4.2, "is_manual": false},
  "opening_at_arrival": {"status": "evaluated", "evaluation": {"state": "open", "validation": "valid", "opening_hours": "24/7", "source": "osm", "source_confidence": 0.95, "evaluated_at": "2026-08-30T10:19:11+02:00", "timezone": "Europe/Rome", "next_change_at": null, "comment": null, "warnings": []}}
}
```
Without `arrival_at`, `opening_at_arrival.status` is `not_requested`; missing hours evaluate
`unknown`, never open.

## Route through a selected CNG stop

```http
POST /api/v1/routes/with-cng-stop
Content-Type: application/json

{"origin":{"latitude":45.4642,"longitude":9.19},"destination":{"latitude":44.4949,"longitude":11.3426},"mimit_station_id":"43690"}
```

Representative response shape:

```json
{
  "selected_stop": {"mimit_station_id": "43690", "name": "S.ZENONE OVEST", "municipality": "SAN ZENONE AL LAMBRO", "province": "MI", "location": {"latitude": 45.321004, "longitude": 9.376063}},
  "distance_meters": 210930,
  "duration_seconds": 6839,
  "legs": [
    {"kind": "origin_to_cng_station", "origin": {"latitude": 45.4642, "longitude": 9.19}, "destination": {"latitude": 45.321004, "longitude": 9.376063}, "distance_meters": 23106, "duration_seconds": 1151, "geometry": {"format": "polyline6", "encoded_polyline": "..."}, "maneuvers": []},
    {"kind": "cng_station_to_destination", "origin": {"latitude": 45.321004, "longitude": 9.376063}, "destination": {"latitude": 44.4949, "longitude": 11.3426}, "distance_meters": 187824, "duration_seconds": 5688, "geometry": {"format": "polyline6", "encoded_polyline": "..."}, "maneuvers": []}
  ],
  "provider": "valhalla"
}
```

Real successful legs contain one or more maneuvers. The abbreviated empty arrays above keep the
example readable; OpenAPI defines every maneuver field.

Maneuvers preserve provider-backed junction guidance instead of requiring clients to parse
localized instructions. Nullable `sign` contains `exit_number_elements`, `exit_branch_elements`,
`exit_toward_elements` and `exit_name_elements`; every element has `text` plus nullable
`consecutive_count`. Nullable `roundabout_exit_count` carries Valhalla's structured exit ordinal.
Absent data remains null. These fields are identical on base routes and every CNG route leg.

Every route response also contains `navigation`. `duration_seconds` remains driving time for
backwards compatibility and for Valhalla cost comparisons. `navigation.total_trip_duration_seconds`
adds `CNG_REFUEL_DWELL_SECONDS` for every CNG stop (1,200 seconds by default), while
`navigation.total_refueling_dwell_seconds` exposes that non-driving component explicitly. Route
IDs are derived from the returned geometry and ordered fuel-stop IDs; they change when the route or
fuel plan changes. Selected stops expose `expected_arrival_at` and `dwell_time_seconds`.
`traffic_delay_state=unavailable` pairs with a null delay when no defensible separate live-delay
estimate exists; the client must not turn that into zero delay.

When a current provider snapshot and native Valhalla overlay are both usable, `navigation` also
contains `traffic_state=fresh`, `traffic_aware=true`, `traffic_observed_at` and
`traffic_delay_state=estimated`. The numeric `traffic_delay_seconds` is the non-negative difference
between the successful Valhalla traffic duration and a separate Valhalla graph-speed route for the
same ordered locations and costing. The driving duration already includes traffic; clients must not
add the delay to it again. A fresh provider combined with an error-442 graph-speed fallback remains
`traffic_state=fresh` but reports `traffic_aware=false`, a null delay and no observation timestamp.

## Route through a predictive CNG itinerary

`POST /api/v1/routes/with-cng-itinerary` accepts the ordered official MIMIT IDs produced by the
predictive planner plus the same full, remaining and reserve ranges. It resolves all stops in one
database query, asks Valhalla for one multi-waypoint route, and independently rechecks every actual
road leg before returning it. An itinerary contains between 1 and 32 unique official station IDs.

```json
{
  "origin": {"latitude": 45.4642, "longitude": 9.19},
  "destination": {"latitude": 44.4949, "longitude": 11.3426},
  "mimit_station_ids": ["43690", "3473", "3618"],
  "effective_cng_range_km": 100,
  "estimated_remaining_cng_range_km": 65,
  "reserve_cng_range_km": 30
}
```

The first leg may consume at most `remaining - reserve`; every leg after a refuelling stop may
consume at most `effective - reserve`. The response contains `selected_stops`, one more `legs` item
than stops, range arithmetic on each leg and
`range_validation=all_legs_preserve_reserve`. If Valhalla's actual multi-waypoint route violates
the reserve, the API returns HTTP 409 with `code=cng_itinerary_out_of_range` instead of presenting
an unsafe route. The response total is the sum of those validated legs, avoiding cumulative
rounding drift between Valhalla's independently serialized trip and leg summaries.

## Predictive CNG candidates

```http
POST /api/v1/cng/predictive-candidates
Content-Type: application/json

{
  "origin": {"latitude": 45.4642, "longitude": 9.19},
  "destination": {"latitude": 44.4949, "longitude": 11.3426},
  "effective_cng_range_km": 300,
  "estimated_remaining_cng_range_km": 120,
  "reserve_cng_range_km": 30,
  "estimated_remaining_gasoline_range_km": 200,
  "reserve_gasoline_range_km": 30,
  "maximum_detour_minutes": 10,
  "departure_at": "2026-08-30T10:00:00+02:00",
  "excluded_mimit_station_ids": []
}
```

The remaining range is a caller estimate at `origin`, not vehicle telemetry. Usable range is
`estimated_remaining_cng_range_km - reserve_cng_range_km`. Candidate reachability uses road distance
from that origin; straight-line corridor distance is only a prefilter.

The response stage is `predictive_ranking` and `suggestion_state` is one of:

- `not_needed`: destination is within usable range; station/matrix/enrichment work is skipped;
- `suggested`: `itinerary` contains a complete ordered reserve-preserving chain from origin to
  destination; `candidates` exposes exactly its ranked first stop for compatibility/explanation;
- `gasoline_fallback`: no complete CNG itinerary exists, but the direct route is reachable by
  consuming CNG only down to its reserve and then gasoline only down to its separate reserve;
- `no_reachable_station`: no eligible station is reachable before reserve;
- `no_eligible_station`: reachable stations exist, but none survives opening/availability policy;
- `no_complete_itinerary`: a safe first stop exists, but no complete chain reaches the destination.

The itinerary assumes `full_effective_range_after_each_stop`. Its first leg starts with the supplied
remaining range; subsequent legs start with the effective full range. Each stop and the destination
leg expose road distance, ETA, remaining range and non-negative reserve margin. Stops also expose
opening state at their own ETA, price/freshness and contact enrichment. `range_basis` exposes the
input arithmetic, remaining road distance, shortfall, `remaining_route_origin=request_origin`,
`consumption_model=caller_estimated_remaining_range`, and explicit non-traffic-adjusted state.
`reachability_evaluation` and the existing spatial/network/ranking metrics make all pruning stages
auditable. The exact required fields and nested schemas are authoritative in
[openapi.json](openapi.json).

During an explicit in-navigation replacement, Android sends the unavailable station and all prior
session exclusions in `excluded_mimit_station_ids`. The list accepts at most 32 distinct numeric
official IDs. Compass removes them in the PostGIS corridor query before applying the candidate
limit or calling Valhalla matrices, and echoes the applied list in the response. A strict client
must verify that echo before accepting the replacement plan.

Each itinerary stop also exposes its configured `dwell_time_seconds`. Later station ETAs and their
opening-hours evaluations include dwell at all previous stops. The itinerary's
`total_duration_seconds` remains driving time; `total_refueling_dwell_seconds` is the stop sum and
`total_trip_duration_seconds` is their total.

The two gasoline fields are optional but must be supplied together. They are caller estimates, not
telemetry. `gasoline_fallback` never outranks an available complete CNG itinerary and does not assume
a gasoline refuelling stop. Its metrics expose usable gasoline range, estimated gasoline required on
the direct route and the remaining margin at destination. If gasoline is absent or insufficient,
the original CNG failure state is preserved.

## Data freshness

```http
GET /api/v1/data-freshness
```

```json
{
  "evaluated_at": "2026-08-29T06:00:00Z",
  "overall_state": "degraded",
  "sources": [
    {"source_name": "mimit_cng", "state": "stale", "source_observed_at": "2026-08-25T06:00:00Z", "completed_at": "2026-08-27T05:39:20Z", "age_seconds": 345600, "freshness_threshold_seconds": 172800},
    {"source_name": "osm_cng", "state": "fresh", "source_observed_at": "2026-08-27T05:43:42Z", "completed_at": "2026-08-27T05:46:20Z", "age_seconds": 173778, "freshness_threshold_seconds": 604800},
    {"source_name": "reconciliation", "state": "fresh", "source_observed_at": "2026-08-27T06:40:00Z", "completed_at": "2026-08-27T06:40:00Z", "age_seconds": 170400, "freshness_threshold_seconds": 172800}
  ],
  "traffic_state": "not_configured"
}
```

## Error examples

```json
{"code":"station_not_found","message":"The CNG station was not found."}
```

```json
{"code":"station_location_unavailable","message":"The CNG station has no usable location."}
```

```json
{"code":"invalid_request","message":"The request payload is invalid."}
```

### Route speed-limit profile

Every base route response includes `speed_limits` and nullable `speed_limit_source`. Each profile
entry contains `begin_shape_index`, exclusive `end_shape_index`, and `speed_limit_kph`. A source of
`valhalla_graph` means Compass successfully walked the returned polyline through Valhalla
`/trace_attributes`; an empty profile can still mean the graph has no numeric limit on those edges.
If enrichment is disabled, unavailable or malformed, the source is `null`, the list is empty, and
the otherwise valid route remains available. The same fields exist on every selected-stop and
predictive-itinerary leg. Values `0` (unknown) and `255` (unlimited) are never serialized as numeric
limits.
