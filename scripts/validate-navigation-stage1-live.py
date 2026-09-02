#!/usr/bin/env python3
import argparse
import json
import math
from datetime import datetime
from pathlib import Path
from typing import Any

ORIGIN = {"latitude": 45.4642, "longitude": 9.19}
DESTINATION = {"latitude": 44.4949, "longitude": 11.3426}


def _read(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text())


def _write(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def prepare(base_path: Path, ranked_path: Path) -> None:
    departure = datetime.now().astimezone().isoformat(timespec="seconds")
    common = {
        "origin": ORIGIN,
        "destination": DESTINATION,
        "costing": "auto",
        "language": "it-IT",
        "departure_at": departure,
    }
    _write(base_path, common)
    _write(
        ranked_path,
        {
            **common,
            "effective_cng_range_km": 300,
            "maximum_detour_minutes": 10,
        },
    )


def prepare_selected(ranked_path: Path, output: Path) -> None:
    ranked = _read(ranked_path)
    candidates = ranked.get("candidates")
    _require(isinstance(candidates, list) and candidates, "ranked response has no candidate")
    station_id = candidates[0].get("mimit_station_id")
    _require(isinstance(station_id, str) and station_id.isdigit(), "invalid station ID")
    _write(
        output,
        {
            "origin": ORIGIN,
            "destination": DESTINATION,
            "costing": "auto",
            "language": "it-IT",
            "departure_at": ranked["departure_at"],
            "mimit_station_id": station_id,
        },
    )
    print(f"selected_mimit_station_id={station_id}")


def validate(base_path: Path, selected_path: Path, openapi_path: Path) -> None:
    base = _read(base_path)
    selected = _read(selected_path)
    openapi = _read(openapi_path)
    base_navigation = _validate_navigation(base, expected_stops=0)
    selected_navigation = _validate_navigation(selected, expected_stops=1)

    stop = selected.get("selected_stop")
    _require(isinstance(stop, dict), "selected stop is missing")
    _require(stop.get("dwell_time_seconds") == 1_200, "CNG dwell must be 20 minutes")
    _require(isinstance(stop.get("expected_arrival_at"), str), "stop ETA is missing")
    legs = selected.get("legs")
    _require(isinstance(legs, list) and len(legs) == 2, "selected route needs two legs")
    for leg in legs:
        geometry = leg.get("geometry")
        _require(
            isinstance(geometry, dict) and geometry.get("format") == "polyline6",
            "navigation leg geometry is invalid",
        )
        maneuvers = leg.get("maneuvers")
        _require(isinstance(maneuvers, list) and maneuvers, "navigation leg maneuvers missing")
        for maneuver in maneuvers:
            _require(
                isinstance(maneuver.get("begin_shape_index"), int)
                and isinstance(maneuver.get("end_shape_index"), int),
                "maneuver shape indexes missing",
            )

    schemas = openapi.get("components", {}).get("schemas", {})
    navigation_schema = schemas.get("NavigationTimingResponse")
    _require(isinstance(navigation_schema, dict), "OpenAPI navigation schema missing")
    required = set(navigation_schema.get("required", []))
    _require(
        {
            "route_id",
            "driving_duration_seconds",
            "total_refueling_dwell_seconds",
            "total_trip_duration_seconds",
        }.issubset(required),
        "OpenAPI navigation timing fields are incomplete",
    )

    summary = {
        "base_route_id": base_navigation["route_id"],
        "base_total_trip_seconds": base_navigation["total_trip_duration_seconds"],
        "selected_route_id": selected_navigation["route_id"],
        "selected_driving_seconds": selected_navigation["driving_duration_seconds"],
        "selected_refueling_dwell_seconds": selected_navigation[
            "total_refueling_dwell_seconds"
        ],
        "selected_total_trip_seconds": selected_navigation["total_trip_duration_seconds"],
        "selected_stop_eta": stop["expected_arrival_at"],
        "selected_stop_count": selected_navigation["refueling_stop_count"],
        "maneuver_shape_indexes": "present",
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    print("Navigation Stage 1 live API contract accepted.")


def _validate_navigation(value: dict[str, Any], *, expected_stops: int) -> dict[str, Any]:
    navigation = value.get("navigation")
    _require(isinstance(navigation, dict), "navigation metadata is missing")
    route_id = navigation.get("route_id")
    _require(
        isinstance(route_id, str)
        and route_id.startswith("route_")
        and len(route_id) == 38,
        "route ID is invalid",
    )
    driving = navigation.get("driving_duration_seconds")
    dwell = navigation.get("total_refueling_dwell_seconds")
    total = navigation.get("total_trip_duration_seconds")
    _require(
        all(isinstance(item, int | float) for item in (driving, dwell, total)),
        "timing invalid",
    )
    _require(navigation.get("refueling_stop_count") == expected_stops, "stop count mismatch")
    _require(
        math.isclose(float(dwell), expected_stops * 1_200, abs_tol=0.001),
        "refuelling dwell total mismatch",
    )
    _require(
        math.isclose(float(total), float(driving) + float(dwell), abs_tol=0.001),
        "trip time does not reconcile",
    )
    return navigation


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--base", type=Path, required=True)
    prepare_parser.add_argument("--ranked", type=Path, required=True)
    selected_parser = subparsers.add_parser("prepare-selected")
    selected_parser.add_argument("--ranked", type=Path, required=True)
    selected_parser.add_argument("--output", type=Path, required=True)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--base", type=Path, required=True)
    validate_parser.add_argument("--selected", type=Path, required=True)
    validate_parser.add_argument("--openapi", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.base, args.ranked)
    elif args.command == "prepare-selected":
        prepare_selected(args.ranked, args.output)
    else:
        validate(args.base, args.selected, args.openapi)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
