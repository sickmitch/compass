#!/usr/bin/env python3
"""Prepare and validate the operator-run Phase 9 live API artifacts."""

from __future__ import annotations

import argparse
import json
import math
from datetime import datetime
from pathlib import Path
from typing import Any

ORIGIN = {"latitude": 45.4642, "longitude": 9.19}
DESTINATION = {"latitude": 44.4949, "longitude": 11.3426}
VALHALLA_DISTANCE_SUM_TOLERANCE_METERS = 2.0
VALHALLA_DURATION_SUM_TOLERANCE_SECONDS = 2.0


def _read(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    _require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def _write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _offset_datetime(value: Any, field: str) -> datetime:
    _require(isinstance(value, str), f"{field} must be an ISO 8601 string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    _require(parsed.utcoffset() is not None, f"{field} must include a UTC offset")
    return parsed


def _positive_number(value: Any, field: str) -> float:
    _require(isinstance(value, int | float), f"{field} must be numeric")
    number = float(value)
    _require(math.isfinite(number) and number > 0, f"{field} must be positive")
    return number


def _non_negative_number(value: Any, field: str) -> float:
    _require(isinstance(value, int | float), f"{field} must be numeric")
    number = float(value)
    _require(math.isfinite(number) and number >= 0, f"{field} must be non-negative")
    return number


def _coordinate(value: Any, field: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{field} must be an object")
    latitude = float(value.get("latitude"))
    longitude = float(value.get("longitude"))
    _require(-90 <= latitude <= 90, f"{field}.latitude is invalid")
    _require(-180 <= longitude <= 180, f"{field}.longitude is invalid")
    return value


def _validate_route(route: Any, field: str) -> dict[str, Any]:
    _require(isinstance(route, dict), f"{field} must be an object")
    _positive_number(route.get("distance_meters"), f"{field}.distance_meters")
    _positive_number(route.get("duration_seconds"), f"{field}.duration_seconds")
    _require(route.get("provider", "valhalla") == "valhalla", f"{field} provider must be valhalla")
    geometry = route.get("geometry")
    _require(isinstance(geometry, dict), f"{field}.geometry must be an object")
    _require(geometry.get("format") == "polyline6", f"{field} geometry must be polyline6")
    _require(bool(geometry.get("encoded_polyline")), f"{field} polyline must not be empty")
    maneuvers = route.get("maneuvers")
    _require(isinstance(maneuvers, list) and maneuvers, f"{field} must expose maneuvers")
    return route


def validate_ranked(value: dict[str, Any]) -> list[dict[str, Any]]:
    _require(value.get("stage") == "ranking", "ranked response stage must be ranking")
    _offset_datetime(value.get("departure_at"), "departure_at")
    maximum_detour = _positive_number(
        value.get("maximum_detour_minutes"),
        "maximum_detour_minutes",
    )
    _validate_route(value.get("base_route"), "base_route")

    cost_basis = value.get("cost_basis")
    _require(isinstance(cost_basis, dict), "cost_basis must be an object")
    _require(cost_basis.get("provider") == "valhalla", "cost basis must use valhalla")
    _require(cost_basis.get("traffic_state") == "not_configured", "traffic state must be explicit")
    _require(cost_basis.get("traffic_aware") is False, "traffic-aware must not be claimed")

    network = value.get("network_evaluation")
    _require(isinstance(network, dict), "network_evaluation must be an object")
    _require(network.get("base_route_calls") == 1, "ranking must use one base route call")
    _require(network.get("per_candidate_route_calls") == 0, "ranking must not route per candidate")
    _require(int(network.get("matrix_calls", 0)) > 0, "ranking must use bounded matrix calls")

    candidates = value.get("candidates")
    _require(isinstance(candidates, list) and candidates, "live ranking must return candidates")
    ranks: list[int] = []
    for index, candidate in enumerate(candidates):
        field = f"candidates[{index}]"
        _require(isinstance(candidate, dict), f"{field} must be an object")
        station_id = candidate.get("mimit_station_id")
        _require(isinstance(station_id, str) and station_id.isdigit(), f"{field} needs a MIMIT ID")
        _coordinate(candidate, field)
        detour = float(candidate.get("detour_minutes"))
        _require(0 <= detour <= maximum_detour + 1e-6, f"{field} exceeds the detour limit")
        _non_negative_number(
            candidate.get("distance_from_previous_waypoint_meters"),
            f"{field}.distance_from_previous_waypoint_meters",
        )
        _offset_datetime(candidate.get("station_eta"), f"{field}.station_eta")

        opening = candidate.get("opening")
        _require(isinstance(opening, dict), f"{field}.opening must be an object")
        _require(opening.get("state") in {"open", "unknown"}, f"{field} must not be closed")
        _require(
            opening.get("validation") in {"valid", "missing", "invalid"},
            f"{field} opening validation is invalid",
        )

        price = candidate.get("price")
        if price is not None:
            _require(isinstance(price, dict), f"{field}.price must be an object or null")
            _positive_number(price.get("unit_price"), f"{field}.price.unit_price")
            _offset_datetime(price.get("observed_at"), f"{field}.price.observed_at")
            _require(price.get("currency") == "EUR", f"{field} price currency must be EUR")
            _require(price.get("unit") == "kg", f"{field} price unit must be kg")

        ranking = candidate.get("ranking")
        _require(isinstance(ranking, dict), f"{field}.ranking must be an object")
        rank = int(ranking.get("rank"))
        ranks.append(rank)
        for component in (
            "total_score",
            "detour_score",
            "opening_score",
            "price_score",
            "price_freshness_score",
        ):
            score = float(ranking.get(component))
            _require(0 <= score <= 1, f"{field}.ranking.{component} is outside [0,1]")

    _require(ranks == list(range(1, len(candidates) + 1)), "candidate ranks must be contiguous")
    metrics = value.get("ranking_evaluation")
    _require(isinstance(metrics, dict), "ranking_evaluation must be an object")
    _require(
        metrics.get("ranked_candidate_count") == len(candidates),
        "ranked candidate count must reconcile",
    )
    return candidates


def validate_selected(
    value: dict[str, Any],
    expected_station_id: str,
) -> dict[str, Any]:
    selected = value.get("selected_stop")
    _require(isinstance(selected, dict), "selected_stop must be an object")
    _require(
        selected.get("mimit_station_id") == expected_station_id,
        "selected route returned a different MIMIT station",
    )
    stop_location = _coordinate(selected.get("location"), "selected_stop.location")
    _require(value.get("provider") == "valhalla", "selected route provider must be valhalla")

    distance = _positive_number(value.get("distance_meters"), "distance_meters")
    duration = _positive_number(value.get("duration_seconds"), "duration_seconds")
    legs = value.get("legs")
    _require(isinstance(legs, list) and len(legs) == 2, "selected route must contain two legs")
    _require(
        [leg.get("kind") for leg in legs]
        == ["origin_to_cng_station", "cng_station_to_destination"],
        "selected route leg order is invalid",
    )
    for index, leg in enumerate(legs):
        _require(isinstance(leg, dict), f"legs[{index}] must be an object")
        _coordinate(leg.get("origin"), f"legs[{index}].origin")
        _coordinate(leg.get("destination"), f"legs[{index}].destination")
        _validate_route(leg, f"legs[{index}]")

    _require(legs[0]["destination"] == stop_location, "first leg must end at the station")
    _require(legs[1]["origin"] == stop_location, "second leg must start at the station")
    leg_distance = sum(float(leg["distance_meters"]) for leg in legs)
    distance_delta = abs(leg_distance - distance)
    _require(
        distance_delta <= VALHALLA_DISTANCE_SUM_TOLERANCE_METERS,
        "selected route distance differs from its leg sum by "
        f"{distance_delta:g} m (maximum "
        f"{VALHALLA_DISTANCE_SUM_TOLERANCE_METERS:g} m)",
    )
    leg_duration = sum(float(leg["duration_seconds"]) for leg in legs)
    duration_delta = abs(leg_duration - duration)
    _require(
        duration_delta <= VALHALLA_DURATION_SUM_TOLERANCE_SECONDS,
        "selected route duration differs from its leg sum by "
        f"{duration_delta:g} s (maximum "
        f"{VALHALLA_DURATION_SUM_TOLERANCE_SECONDS:g} s)",
    )
    return selected


def prepare_ranked(output: Path) -> None:
    payload = {
        "origin": ORIGIN,
        "destination": DESTINATION,
        "effective_cng_range_km": 300,
        "maximum_detour_minutes": 10,
        "departure_at": datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    _write(output, payload)
    print(f"Ranked-candidate request written to {output}")


def prepare_selected(ranked_path: Path, output: Path) -> None:
    candidates = validate_ranked(_read(ranked_path))
    station_id = candidates[0]["mimit_station_id"]
    _write(
        output,
        {
            "origin": ORIGIN,
            "destination": DESTINATION,
            "mimit_station_id": station_id,
        },
    )
    print(f"Selected-route request for MIMIT {station_id} written to {output}")


def validate(ranked_path: Path, selected_path: Path) -> None:
    candidates = validate_ranked(_read(ranked_path))
    station_id = candidates[0]["mimit_station_id"]
    selected = validate_selected(_read(selected_path), station_id)
    summary = {
        "ranked_candidate_count": len(candidates),
        "selected_mimit_station_id": station_id,
        "selected_name": selected.get("name"),
        "top_candidate_detour_minutes": candidates[0]["detour_minutes"],
        "top_candidate_opening_state": candidates[0]["opening"]["state"],
        "top_candidate_price": candidates[0].get("price"),
        "selected_route_leg_count": 2,
    }
    print(json.dumps(summary, indent=2))
    print("Phase 9 live API contracts validated successfully.")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare_ranked_parser = subparsers.add_parser("prepare-ranked")
    prepare_ranked_parser.add_argument("--output", type=Path, required=True)

    prepare_selected_parser = subparsers.add_parser("prepare-selected")
    prepare_selected_parser.add_argument("--ranked", type=Path, required=True)
    prepare_selected_parser.add_argument("--output", type=Path, required=True)

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--ranked", type=Path, required=True)
    validate_parser.add_argument("--selected-route", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "prepare-ranked":
        prepare_ranked(args.output)
    elif args.command == "prepare-selected":
        prepare_selected(args.ranked, args.output)
    else:
        validate(args.ranked, args.selected_route)


if __name__ == "__main__":
    main()
