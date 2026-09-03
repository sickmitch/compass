#!/usr/bin/env python3
"""Prepare requests and validate Phase 12 live API artifacts."""

from __future__ import annotations

import argparse
import json
import math
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

ORIGIN = {"latitude": 45.4642, "longitude": 9.19}
DESTINATION = {"latitude": 44.4949, "longitude": 11.3426}
PROFILES = {
    "one": {"effective": 300.0, "remaining": 120.0, "reserve": 30.0},
    "multi": {"effective": 100.0, "remaining": 65.0, "reserve": 30.0},
}
TOLERANCE_SECONDS = 2.0


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def number(value: Any, field: str, *, positive: bool = False) -> float:
    require(isinstance(value, int | float), f"{field} must be numeric")
    result = float(value)
    require(math.isfinite(result), f"{field} must be finite")
    require(result > 0 if positive else result >= 0, f"{field} is outside its range")
    return result


def timestamp(value: Any, field: str) -> datetime:
    require(isinstance(value, str), f"{field} must be an ISO 8601 timestamp")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.utcoffset() is not None, f"{field} must include a UTC offset")
    return parsed


def validate_openapi(path: Path) -> None:
    value = read_json(path)
    paths = value.get("paths")
    require(isinstance(paths, dict), "OpenAPI paths are missing")
    for endpoint in (
        "/api/v1/places/search",
        "/api/v1/routes",
        "/api/v1/cng/predictive-candidates",
        "/api/v1/routes/with-cng-itinerary",
    ):
        require(endpoint in paths, f"deployed OpenAPI is missing {endpoint}")
    schemas = value.get("components", {}).get("schemas", {})
    require(isinstance(schemas, dict), "OpenAPI schemas are missing")
    search_fields = schemas.get("PlaceSearchResultResponse", {}).get("properties", {})
    for field in (
        "display_name",
        "address",
        "location",
        "kind",
        "category",
        "poi_name",
        "provider",
    ):
        require(field in search_fields, f"normalized search field {field} is missing")
    timing_fields = schemas.get("NavigationTimingResponse", {}).get("properties", {})
    for field in (
        "driving_duration_seconds",
        "traffic_delay_seconds",
        "traffic_delay_state",
        "dwell_seconds_per_refueling_stop",
        "total_refueling_dwell_seconds",
        "total_trip_duration_seconds",
        "trip_arrival_at",
    ):
        require(field in timing_fields, f"navigation timing field {field} is missing")
    itinerary_fields = schemas.get("PredictiveItineraryResponse", {}).get("properties", {})
    for field in ("total_refueling_dwell_seconds", "total_trip_duration_seconds"):
        require(field in itinerary_fields, f"predictive timing field {field} is missing")
    print("OpenAPI exposes Phase 12 search and journey-time contracts.")


def _search_results(path: Path, label: str) -> list[dict[str, Any]]:
    value = read_json(path)
    results = value.get("results")
    require(isinstance(results, list) and results, f"{label} search returned no results")
    for index, result in enumerate(results):
        field = f"{label}.results[{index}]"
        require(isinstance(result, dict), f"{field} must be an object")
        require(bool(result.get("display_name")), f"{field}.display_name is missing")
        require(
            result.get("kind") in {"address", "locality", "poi", "coordinate", "unknown"},
            f"{field}.kind is invalid",
        )
        location = result.get("location")
        require(isinstance(location, dict), f"{field}.location is missing")
        latitude = float(location.get("latitude"))
        longitude = float(location.get("longitude"))
        require(-90 <= latitude <= 90 and -180 <= longitude <= 180, f"{field}.location is invalid")
        require(bool(result.get("provider")), f"{field}.provider is missing")
    return results


def validate_search(address: Path, locality: Path, poi: Path, coordinate: Path) -> None:
    address_results = _search_results(address, "address")
    locality_results = _search_results(locality, "locality")
    poi_results = _search_results(poi, "poi")
    coordinate_results = _search_results(coordinate, "coordinate")
    require(
        any(item.get("kind") == "address" and item.get("address") for item in address_results),
        "address search did not expose a normalized address result",
    )
    require(
        any(item.get("kind") == "locality" for item in locality_results),
        "city search did not expose a locality result",
    )
    require(
        any(item.get("kind") == "poi" for item in poi_results),
        "POI search did not expose POI/category metadata",
    )
    first_coordinate = coordinate_results[0]
    require(first_coordinate.get("kind") == "coordinate", "coordinate search was not local")
    require(first_coordinate.get("provider") == "coordinate", "coordinate search called a provider")
    location = first_coordinate["location"]
    require(
        math.isclose(float(location["latitude"]), ORIGIN["latitude"]), "coordinate latitude changed"
    )
    require(
        math.isclose(float(location["longitude"]), ORIGIN["longitude"]),
        "coordinate longitude changed",
    )
    print("Address, locality, POI and coordinate searches returned normalized results.")


def prepare_predictive(profile_name: str, output: Path) -> None:
    profile = PROFILES[profile_name]
    write_json(
        output,
        {
            "origin": ORIGIN,
            "destination": DESTINATION,
            "effective_cng_range_km": profile["effective"],
            "estimated_remaining_cng_range_km": profile["remaining"],
            "reserve_cng_range_km": profile["reserve"],
            "maximum_detour_minutes": 30,
            "departure_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        },
    )


def prepare_base_route(output: Path) -> None:
    write_json(
        output,
        {
            "origin": ORIGIN,
            "destination": DESTINATION,
            "departure_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        },
    )


def validate_base_route(path: Path) -> None:
    value = read_json(path)
    number(value.get("distance_meters"), "base route distance", positive=True)
    number(value.get("duration_seconds"), "base route duration", positive=True)
    geometry = value.get("geometry")
    require(
        isinstance(geometry, dict) and bool(geometry.get("encoded_polyline")),
        "base route geometry is missing",
    )
    maneuvers = value.get("maneuvers")
    require(isinstance(maneuvers, list) and maneuvers, "base route maneuvers are missing")
    _validate_navigation_timing(value, 0, "base route")
    print("Compass returned a Valhalla A-to-B route with geometry and maneuvers.")


def prepare_itinerary_route(profile_name: str, predictive_path: Path, output: Path) -> None:
    value = read_json(predictive_path)
    require(value.get("suggestion_state") == "suggested", f"{profile_name} plan is not suggested")
    itinerary = value.get("itinerary")
    require(isinstance(itinerary, dict), f"{profile_name} itinerary is missing")
    stops = itinerary.get("stops")
    require(isinstance(stops, list) and stops, f"{profile_name} itinerary has no stops")
    profile = PROFILES[profile_name]
    write_json(
        output,
        {
            "origin": ORIGIN,
            "destination": DESTINATION,
            "mimit_station_ids": [item["mimit_station_id"] for item in stops],
            "effective_cng_range_km": profile["effective"],
            "estimated_remaining_cng_range_km": profile["remaining"],
            "reserve_cng_range_km": profile["reserve"],
        },
    )
    print(f"Prepared {profile_name} route through {len(stops)} CNG stop(s).")


def _validate_navigation_timing(value: dict[str, Any], expected_stops: int, label: str) -> None:
    timing = value.get("navigation")
    require(isinstance(timing, dict), f"{label}.navigation is missing")
    driving = number(timing.get("driving_duration_seconds"), f"{label}.driving", positive=True)
    require(
        math.isclose(
            driving,
            number(value.get("duration_seconds"), f"{label}.duration"),
            abs_tol=TOLERANCE_SECONDS,
        ),
        f"{label} changed road-routing duration",
    )
    require(
        timing.get("refueling_stop_count") == expected_stops,
        f"{label} refuelling-stop count is wrong",
    )
    require(
        timing.get("dwell_seconds_per_refueling_stop") == 1200,
        f"{label} does not use the configured 20-minute default",
    )
    dwell = number(timing.get("total_refueling_dwell_seconds"), f"{label}.dwell")
    require(
        math.isclose(dwell, expected_stops * 1200, abs_tol=TOLERANCE_SECONDS),
        f"{label} dwell does not accumulate",
    )
    total = number(timing.get("total_trip_duration_seconds"), f"{label}.total", positive=True)
    require(
        math.isclose(total, driving + dwell, abs_tol=TOLERANCE_SECONDS),
        f"{label} total is not driving plus dwell",
    )
    require(
        timing.get("traffic_delay_state") in {"unavailable", "estimated"},
        f"{label} traffic-delay state is not explicit",
    )
    if timing.get("traffic_delay_state") == "unavailable":
        require(
            timing.get("traffic_delay_seconds") is None, f"{label} invented a traffic-delay value"
        )
    departure = timestamp(timing.get("departure_at"), f"{label}.departure_at")
    trip_arrival = timestamp(timing.get("trip_arrival_at"), f"{label}.trip_arrival_at")
    require(
        abs((trip_arrival - departure).total_seconds() - total) <= TOLERANCE_SECONDS,
        f"{label} total ETA does not include dwell",
    )


def _validate_predictive_chronology(value: dict[str, Any], expected_stops: int, label: str) -> None:
    itinerary = value.get("itinerary")
    require(isinstance(itinerary, dict), f"{label}.itinerary is missing")
    stops = itinerary.get("stops")
    require(
        isinstance(stops, list) and len(stops) == expected_stops,
        f"{label} has an unexpected stop count",
    )
    driving = number(itinerary.get("total_duration_seconds"), f"{label}.driving", positive=True)
    dwell = number(itinerary.get("total_refueling_dwell_seconds"), f"{label}.dwell")
    total = number(itinerary.get("total_trip_duration_seconds"), f"{label}.total", positive=True)
    require(
        math.isclose(dwell, expected_stops * 1200, abs_tol=TOLERANCE_SECONDS),
        f"{label} predictive dwell does not accumulate",
    )
    require(
        math.isclose(total, driving + dwell, abs_tol=TOLERANCE_SECONDS),
        f"{label} predictive total is inconsistent",
    )
    for index, stop in enumerate(stops):
        require(
            stop.get("dwell_time_seconds") == 1200,
            f"{label}.stops[{index}] does not expose 20-minute dwell",
        )
        arrival = timestamp(stop.get("arrival_at"), f"{label}.stops[{index}].arrival_at")
        if index > 0:
            previous = timestamp(stops[index - 1].get("arrival_at"), "previous arrival")
            leg_duration = number(stop.get("leg_duration_seconds"), "later leg duration")
            expected = previous + timedelta(seconds=1200 + leg_duration)
            require(
                abs((arrival - expected).total_seconds()) <= TOLERANCE_SECONDS,
                f"{label}.stops[{index}] ETA omitted prior dwell",
            )
    destination = itinerary.get("destination_leg")
    require(isinstance(destination, dict), f"{label}.destination_leg is missing")
    final_eta = timestamp(destination.get("destination_eta"), f"{label}.destination_eta")
    final_leg_duration = number(destination.get("duration_seconds"), "final leg duration")
    expected_final = timestamp(stops[-1].get("arrival_at"), "last stop arrival") + timedelta(
        seconds=1200 + final_leg_duration
    )
    require(
        abs((final_eta - expected_final).total_seconds()) <= TOLERANCE_SECONDS,
        f"{label} destination ETA omitted final dwell",
    )


def validate_timing(
    one_predictive: Path,
    one_route: Path,
    multi_predictive: Path,
    multi_route: Path,
) -> None:
    one_plan = read_json(one_predictive)
    multi_plan = read_json(multi_predictive)
    one_itinerary = one_plan.get("itinerary")
    multi_itinerary = multi_plan.get("itinerary")
    require(isinstance(one_itinerary, dict), "one-stop predictive itinerary is missing")
    require(isinstance(multi_itinerary, dict), "multi-stop predictive itinerary is missing")
    one_count = len(one_itinerary.get("stops", []))
    multi_count = len(multi_itinerary.get("stops", []))
    require(one_count == 1, f"expected one CNG stop, received {one_count}")
    require(multi_count >= 2, f"expected multiple CNG stops, received {multi_count}")
    _validate_predictive_chronology(one_plan, one_count, "one-stop predictive")
    _validate_predictive_chronology(multi_plan, multi_count, "multi-stop predictive")
    _validate_navigation_timing(read_json(one_route), one_count, "one-stop route")
    _validate_navigation_timing(read_json(multi_route), multi_count, "multi-stop route")
    print(
        "Live timing separates driving/traffic/dwell/total and applies 20 minutes "
        f"to one stop and cumulatively to {multi_count} stops."
    )


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    openapi = commands.add_parser("validate-openapi")
    openapi.add_argument("--openapi", type=Path, required=True)
    search = commands.add_parser("validate-search")
    for name in ("address", "locality", "poi", "coordinate"):
        search.add_argument(f"--{name}", type=Path, required=True)
    base = commands.add_parser("prepare-base-route")
    base.add_argument("--output", type=Path, required=True)
    base_validation = commands.add_parser("validate-base-route")
    base_validation.add_argument("--route", type=Path, required=True)
    predictive = commands.add_parser("prepare-predictive")
    predictive.add_argument("--profile", choices=PROFILES, required=True)
    predictive.add_argument("--output", type=Path, required=True)
    itinerary = commands.add_parser("prepare-itinerary-route")
    itinerary.add_argument("--profile", choices=PROFILES, required=True)
    itinerary.add_argument("--predictive", type=Path, required=True)
    itinerary.add_argument("--output", type=Path, required=True)
    timing = commands.add_parser("validate-timing")
    timing.add_argument("--one-predictive", type=Path, required=True)
    timing.add_argument("--one-route", type=Path, required=True)
    timing.add_argument("--multi-predictive", type=Path, required=True)
    timing.add_argument("--multi-route", type=Path, required=True)
    return root


def main() -> None:
    args = parser().parse_args()
    if args.command == "validate-openapi":
        validate_openapi(args.openapi)
    elif args.command == "validate-search":
        validate_search(args.address, args.locality, args.poi, args.coordinate)
    elif args.command == "prepare-base-route":
        prepare_base_route(args.output)
    elif args.command == "validate-base-route":
        validate_base_route(args.route)
    elif args.command == "prepare-predictive":
        prepare_predictive(args.profile, args.output)
    elif args.command == "prepare-itinerary-route":
        prepare_itinerary_route(args.profile, args.predictive, args.output)
    else:
        validate_timing(
            args.one_predictive,
            args.one_route,
            args.multi_predictive,
            args.multi_route,
        )


if __name__ == "__main__":
    main()
