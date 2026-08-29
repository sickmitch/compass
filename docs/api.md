# Public API v1

The runtime contract is [openapi.json](openapi.json). All distances are metres, durations seconds,
timestamps ISO 8601, and CNG prices explicit unit prices (normally EUR/kg). Unknown request fields
are rejected. Errors use `{"code":"...","message":"..."}` except dependency-state health responses.

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
