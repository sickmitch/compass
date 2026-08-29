#!/usr/bin/env python3
"""Validate saved Phase 7 live API artifacts without external dependencies."""

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text())
    assert isinstance(value, dict), f"{path} must contain a JSON object"
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    for name in (
        "health",
        "base_route",
        "ranked",
        "station",
        "selected_route",
        "freshness",
        "not_found",
        "invalid",
        "openapi",
    ):
        parser.add_argument(f"--{name.replace('_', '-')}", type=Path, required=True)
    args = parser.parse_args()

    health = load(args.health)
    base_route = load(args.base_route)
    ranked = load(args.ranked)
    station = load(args.station)
    selected_route = load(args.selected_route)
    freshness = load(args.freshness)
    not_found = load(args.not_found)
    invalid = load(args.invalid)
    openapi = load(args.openapi)

    assert health["status"] == "ok"
    assert health["database"] == "ready"
    assert health["routing"] == "ready"
    assert health["data"] in {"ready", "degraded"}
    assert health["traffic"] == "not_configured"

    assert base_route["provider"] == "valhalla"
    assert float(base_route["distance_meters"]) > 0
    assert base_route["geometry"]["format"] == "polyline6"
    assert base_route["maneuvers"]

    assert ranked["stage"] == "ranking"
    assert ranked["candidates"]
    assert ranked["network_evaluation"]["base_route_calls"] == 1
    assert ranked["network_evaluation"]["per_candidate_route_calls"] == 0

    station_id = station["mimit_station_id"]
    assert station_id == "43690"
    assert station["is_active"] is True
    assert station["location"] is not None
    assert station["current_cng_prices"]
    for price in station["current_cng_prices"]:
        assert price["fuel_type"] == "cng"
        assert price["currency"] == "EUR"
        assert price["unit"] == "kg"
        assert price["observed_at"]
        assert price["ingested_at"]
        assert price["freshness_state"] in {
            "fresh",
            "stale",
            "future_observation",
            "unknown",
        }
    assert station["opening_at_arrival"]["status"] == "evaluated"
    assert station["opening_at_arrival"]["evaluation"]["state"] in {
        "open",
        "closed",
        "unknown",
    }

    assert selected_route["selected_stop"]["mimit_station_id"] == station_id
    assert selected_route["provider"] == "valhalla"
    legs = selected_route["legs"]
    assert len(legs) == 2
    assert [leg["kind"] for leg in legs] == [
        "origin_to_cng_station",
        "cng_station_to_destination",
    ]
    assert (
        abs(
            sum(float(leg["distance_meters"]) for leg in legs)
            - float(selected_route["distance_meters"])
        )
        <= 2
    )
    assert (
        abs(
            sum(float(leg["duration_seconds"]) for leg in legs)
            - float(selected_route["duration_seconds"])
        )
        <= 2
    )
    for leg in legs:
        assert leg["geometry"]["format"] == "polyline6"
        assert leg["geometry"]["encoded_polyline"]
        assert leg["maneuvers"]

    assert freshness["overall_state"] in {"ready", "degraded"}
    assert freshness["traffic_state"] == "not_configured"
    sources = {item["source_name"]: item for item in freshness["sources"]}
    assert set(sources) == {"mimit_cng", "osm_cng", "reconciliation"}
    assert sources["mimit_cng"]["state"] != "missing"
    assert sources["reconciliation"]["state"] != "missing"

    assert not_found == {
        "code": "station_not_found",
        "message": "The CNG station was not found.",
    }
    assert invalid == {
        "code": "invalid_request",
        "message": "The request payload is invalid.",
    }

    paths = openapi["paths"]
    required_paths = {
        "/api/v1/routes",
        "/api/v1/cng/ranked-candidates",
        "/api/v1/cng/stations/{mimit_station_id}",
        "/api/v1/routes/with-cng-stop",
        "/api/v1/data-freshness",
        "/health/live",
        "/health/ready",
    }
    assert required_paths <= set(paths)
    assert "ErrorResponse" in openapi["components"]["schemas"]

    print(
        json.dumps(
            {
                "health": health,
                "base_route": {
                    "distance_meters": base_route["distance_meters"],
                    "duration_seconds": base_route["duration_seconds"],
                    "maneuver_count": len(base_route["maneuvers"]),
                },
                "ranked_candidate_count": len(ranked["candidates"]),
                "station": {
                    "mimit_station_id": station_id,
                    "is_active": station["is_active"],
                    "price_count": len(station["current_cng_prices"]),
                    "opening_at_arrival": station["opening_at_arrival"],
                },
                "selected_route": {
                    "distance_meters": selected_route["distance_meters"],
                    "duration_seconds": selected_route["duration_seconds"],
                    "legs": [
                        {
                            "kind": leg["kind"],
                            "distance_meters": leg["distance_meters"],
                            "duration_seconds": leg["duration_seconds"],
                            "maneuver_count": len(leg["maneuvers"]),
                        }
                        for leg in legs
                    ],
                },
                "freshness": freshness,
                "openapi_required_paths": sorted(required_paths),
                "error_codes": [not_found["code"], invalid["code"]],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
