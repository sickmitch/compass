#!/usr/bin/env python3
"""Prepare and validate the operator-run Phase 10 live API artifacts."""

from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

ORIGIN = {"latitude": 45.4642, "longitude": 9.19}
DESTINATION = {"latitude": 44.4949, "longitude": 11.3426}
MAXIMUM_DETOUR_MINUTES = 10.0
PROFILES = {
    "standard": {"effective": 300.0, "remaining": 120.0, "reserve": 30.0},
    "not-needed": {"effective": 300.0, "remaining": 300.0, "reserve": 30.0},
    "unreachable": {"effective": 300.0, "remaining": 31.0, "reserve": 30.0},
    "multi-stop": {"effective": 100.0, "remaining": 65.0, "reserve": 30.0},
}
COST_TOLERANCE = 2.0


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _read(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    _require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def _write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def _number(value: Any, field: str, *, positive: bool = False) -> float:
    _require(value is not None, f"{field} is missing from the live response")
    _require(isinstance(value, int | float), f"{field} must be numeric")
    result = float(value)
    _require(math.isfinite(result), f"{field} must be finite")
    _require(result > 0 if positive else result >= 0, f"{field} is outside its range")
    return result


def _offset_datetime(value: Any, field: str) -> datetime:
    _require(isinstance(value, str), f"{field} must be an ISO 8601 string")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    _require(result.utcoffset() is not None, f"{field} must include a UTC offset")
    return result


def _coordinate(value: Any, field: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{field} must be an object")
    latitude = float(value.get("latitude"))
    longitude = float(value.get("longitude"))
    _require(-90 <= latitude <= 90, f"{field}.latitude is invalid")
    _require(-180 <= longitude <= 180, f"{field}.longitude is invalid")
    return value


def _route(value: Any, field: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{field} must be an object")
    _number(value.get("distance_meters"), f"{field}.distance_meters", positive=True)
    _number(value.get("duration_seconds"), f"{field}.duration_seconds", positive=True)
    geometry = value.get("geometry")
    _require(isinstance(geometry, dict), f"{field}.geometry must be an object")
    _require(geometry.get("format") == "polyline6", f"{field} must use polyline6")
    _require(bool(geometry.get("encoded_polyline")), f"{field} polyline must not be empty")
    maneuvers = value.get("maneuvers")
    _require(isinstance(maneuvers, list) and maneuvers, f"{field} must expose maneuvers")
    return value


def prepare(profile_name: str, output: Path) -> None:
    profile = PROFILES[profile_name]
    _write(
        output,
        {
            "origin": ORIGIN,
            "destination": DESTINATION,
            "effective_cng_range_km": profile["effective"],
            "estimated_remaining_cng_range_km": profile["remaining"],
            "reserve_cng_range_km": profile["reserve"],
            "maximum_detour_minutes": MAXIMUM_DETOUR_MINUTES,
            "departure_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        },
    )
    print(f"Predictive {profile_name} request written to {output}")


def validate_openapi(path: Path) -> None:
    value = _read(path)
    paths = value.get("paths")
    _require(isinstance(paths, dict), "openapi.paths must be an object")
    for endpoint in (
        "/api/v1/cng/predictive-candidates",
        "/api/v1/routes/with-cng-itinerary",
    ):
        _require(
            endpoint in paths,
            (
                f"OpenAPI is missing {endpoint}. The live API is not serving the "
                "corrected Phase 10 contract; rebuild and recreate the api service "
                "from the current repository before rerunning this live gate."
            ),
        )

    schemas = value.get("components", {}).get("schemas")
    _require(isinstance(schemas, dict), "openapi.components.schemas must be an object")
    response_schema = schemas.get("PredictiveCandidatesResponse")
    _require(isinstance(response_schema, dict), "PredictiveCandidatesResponse schema is missing")
    response_properties = response_schema.get("properties")
    _require(
        isinstance(response_properties, dict) and "itinerary" in response_properties,
        (
            "PredictiveCandidatesResponse.itinerary is missing. The live API is not "
            "serving the corrected Phase 10 contract."
        ),
    )

    reachability_schema = schemas.get("PredictiveReachabilityMetricsResponse")
    _require(
        isinstance(reachability_schema, dict),
        "PredictiveReachabilityMetricsResponse schema is missing",
    )
    reachability_properties = reachability_schema.get("properties")
    _require(isinstance(reachability_properties, dict), "reachability schema is invalid")
    for field in (
        "pairwise_matrix_calls",
        "pairwise_matrix_fallback_splits",
        "pairwise_matrix_location_failures",
        "itinerary_search_labels",
    ):
        _require(
            field in reachability_properties,
            (
                f"PredictiveReachabilityMetricsResponse.{field} is missing. The live "
                "API is not serving the corrected Phase 10 contract; rebuild and "
                "recreate the api service from the current repository before "
                "rerunning this live gate."
            ),
        )
    print("OpenAPI exposes the corrected Phase 10 predictive itinerary contract.")


def _validate_common(
    value: dict[str, Any],
    *,
    expected_state: str,
    profile_name: str,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    profile = PROFILES[profile_name]
    _require(value.get("stage") == "predictive_ranking", "stage must be predictive_ranking")
    _require(value.get("suggestion_state") == expected_state, "unexpected suggestion state")
    _offset_datetime(value.get("departure_at"), "departure_at")
    base_route = _route(value.get("base_route"), "base_route")

    cost_basis = value.get("cost_basis")
    _require(isinstance(cost_basis, dict), "cost_basis must be an object")
    _require(cost_basis.get("provider") == "valhalla", "provider must be valhalla")
    _require(cost_basis.get("traffic_state") == "not_configured", "traffic state must be explicit")
    _require(cost_basis.get("traffic_aware") is False, "live traffic must not be claimed")

    range_basis = value.get("range_basis")
    _require(isinstance(range_basis, dict), "range_basis must be an object")
    for field, expected in (
        ("effective_cng_range_km", profile["effective"]),
        ("estimated_remaining_cng_range_km", profile["remaining"]),
        ("reserve_cng_range_km", profile["reserve"]),
    ):
        _require(math.isclose(float(range_basis.get(field)), expected), f"{field} changed")
    usable_km = _number(
        range_basis.get("usable_range_before_reserve_km"),
        "usable_range_before_reserve_km",
        positive=True,
    )
    _require(
        math.isclose(usable_km, profile["remaining"] - profile["reserve"]),
        "usable range must equal remaining range minus reserve",
    )
    _require(
        math.isclose(
            float(range_basis.get("remaining_route_distance_km")),
            float(base_route["distance_meters"]) / 1_000,
            abs_tol=0.001,
        ),
        "remaining route distance does not match the base route",
    )
    _require(range_basis.get("remaining_route_origin") == "request_origin", "invalid range origin")
    _require(
        range_basis.get("consumption_model") == "caller_estimated_remaining_range",
        "consumption model is not explicit",
    )
    _require(range_basis.get("traffic_adjusted") is False, "range must not claim live traffic")

    network = value.get("network_evaluation")
    _require(isinstance(network, dict), "network_evaluation must be an object")
    _require(network.get("base_route_calls") == 1, "exactly one base route call is required")
    _require(network.get("per_candidate_route_calls") == 0, "per-candidate routes are forbidden")
    reachability = value.get("reachability_evaluation")
    _require(isinstance(reachability, dict), "reachability_evaluation must be an object")
    for field in (
        "pairwise_matrix_calls",
        "pairwise_matrix_fallback_splits",
        "pairwise_matrix_location_failures",
        "itinerary_search_labels",
    ):
        _require(
            field in reachability,
            (
                f"reachability_evaluation.{field} is missing. The API is not serving "
                "the corrected Phase 10 contract; rebuild and recreate the api service "
                "from the current repository before rerunning this live gate."
            ),
        )
        _number(reachability.get(field), f"reachability_evaluation.{field}")

    candidates = value.get("candidates")
    _require(isinstance(candidates, list), "candidates must be an array")
    ranking = value.get("ranking_evaluation")
    _require(isinstance(ranking, dict), "ranking_evaluation must be an object")
    _require(ranking.get("ranked_candidate_count") == len(candidates), "ranking count mismatch")
    _require(
        reachability.get("ranked_reachable_candidate_count") == len(candidates),
        "reachability count mismatch",
    )
    return range_basis, candidates


def _validate_itinerary(
    value: dict[str, Any],
    *,
    profile_name: str,
    minimum_stops: int,
) -> list[str]:
    profile = PROFILES[profile_name]
    itinerary = value.get("itinerary")
    _require(isinstance(itinerary, dict), "suggested response needs a complete itinerary")
    _require(itinerary.get("distance_model") == "road_network", "itinerary must use road distances")
    _require(
        itinerary.get("refuel_assumption") == "full_effective_range_after_each_stop",
        "full-refill assumption is missing",
    )
    stops = itinerary.get("stops")
    _require(isinstance(stops, list) and len(stops) >= minimum_stops, "too few itinerary stops")
    station_ids: list[str] = []
    leg_distances: list[float] = []
    leg_durations: list[float] = []
    for index, stop in enumerate(stops, start=1):
        field = f"itinerary.stops[{index - 1}]"
        _require(isinstance(stop, dict), f"{field} must be an object")
        _require(stop.get("sequence") == index, f"{field} sequence is not contiguous")
        station_id = stop.get("mimit_station_id")
        _require(isinstance(station_id, str) and station_id.isdigit(), f"{field} needs a MIMIT ID")
        station_ids.append(station_id)
        _coordinate(stop.get("location"), f"{field}.location")
        _offset_datetime(stop.get("arrival_at"), f"{field}.arrival_at")
        distance = _number(stop.get("leg_distance_meters"), f"{field}.leg_distance_meters")
        duration = _number(stop.get("leg_duration_seconds"), f"{field}.leg_duration_seconds")
        available = profile["remaining"] if index == 1 else profile["effective"]
        _require(
            math.isclose(float(stop.get("available_range_at_departure_km")), available),
            f"{field} available range is wrong",
        )
        remaining = available - distance / 1_000
        _require(
            math.isclose(
                float(stop.get("estimated_remaining_range_at_arrival_km")),
                remaining,
                abs_tol=0.001,
            ),
            f"{field} remaining range does not reconcile",
        )
        _require(
            math.isclose(
                float(stop.get("reserve_margin_at_arrival_km")),
                remaining - profile["reserve"],
                abs_tol=0.001,
            ),
            f"{field} reserve margin does not reconcile",
        )
        _require(remaining >= profile["reserve"] - 0.001, f"{field} consumes the reserve")
        opening = stop.get("opening")
        _require(isinstance(opening, dict), f"{field}.opening must be an object")
        _require(opening.get("state") in {"open", "unknown"}, f"{field} is closed at ETA")
        leg_distances.append(distance)
        leg_durations.append(duration)

    _require(len(station_ids) == len(set(station_ids)), "itinerary repeats a station")
    destination = itinerary.get("destination_leg")
    _require(isinstance(destination, dict), "itinerary.destination_leg must be an object")
    distance = _number(destination.get("distance_meters"), "destination_leg.distance_meters")
    duration = _number(destination.get("duration_seconds"), "destination_leg.duration_seconds")
    remaining = profile["effective"] - distance / 1_000
    _require(
        math.isclose(
            float(destination.get("available_range_at_departure_km")),
            profile["effective"],
        ),
        "destination leg must depart after a full refill",
    )
    _require(
        math.isclose(
            float(destination.get("estimated_remaining_range_at_arrival_km")),
            remaining,
            abs_tol=0.001,
        ),
        "destination remaining range does not reconcile",
    )
    _require(
        math.isclose(
            float(destination.get("reserve_margin_at_arrival_km")),
            remaining - profile["reserve"],
            abs_tol=0.001,
        ),
        "destination reserve margin does not reconcile",
    )
    _require(remaining >= profile["reserve"] - 0.001, "destination leg consumes the reserve")
    _offset_datetime(destination.get("destination_eta"), "destination_leg.destination_eta")
    leg_distances.append(distance)
    leg_durations.append(duration)
    _require(
        abs(sum(leg_distances) - float(itinerary.get("total_distance_meters"))) <= COST_TOLERANCE,
        "itinerary distance total does not reconcile",
    )
    _require(
        abs(sum(leg_durations) - float(itinerary.get("total_duration_seconds"))) <= COST_TOLERANCE,
        "itinerary duration total does not reconcile",
    )
    return station_ids


def validate_suggested(
    value: dict[str, Any],
    *,
    profile_name: str,
    minimum_stops: int,
) -> list[str]:
    range_basis, wrappers = _validate_common(
        value,
        expected_state="suggested",
        profile_name=profile_name,
    )
    _require(
        range_basis.get("destination_reachable_with_reserve") is False,
        "refill must be needed",
    )
    _require(len(wrappers) == 1, "suggested response must expose exactly its selected first stop")
    wrapper = wrappers[0]
    _require(isinstance(wrapper, dict), "candidates[0] must be an object")
    candidate = wrapper.get("candidate")
    _require(isinstance(candidate, dict), "candidates[0].candidate must be an object")
    station_id = candidate.get("mimit_station_id")
    _require(isinstance(station_id, str) and station_id.isdigit(), "first stop needs a MIMIT ID")
    road_distance = _number(
        candidate.get("distance_from_previous_waypoint_meters"),
        "first_stop.distance_from_previous_waypoint_meters",
    )
    usable_meters = float(range_basis["usable_range_before_reserve_km"]) * 1_000
    _require(road_distance <= usable_meters + 1, "first stop is unreachable before reserve")
    station_ids = _validate_itinerary(
        value,
        profile_name=profile_name,
        minimum_stops=minimum_stops,
    )
    _require(station_ids[0] == station_id, "exposed first stop and itinerary disagree")
    return station_ids


def validate_not_needed(value: dict[str, Any]) -> None:
    range_basis, candidates = _validate_common(
        value,
        expected_state="not_needed",
        profile_name="not-needed",
    )
    _require(not candidates and value.get("itinerary") is None, "not-needed must not plan stops")
    _require(
        range_basis.get("destination_reachable_with_reserve") is True,
        "destination unreachable",
    )
    _require(
        value["reachability_evaluation"].get("evaluation_skipped_destination_reachable") is True,
        "not-needed response did not skip candidate work",
    )
    _require(value["network_evaluation"].get("matrix_calls") == 0, "station matrix was not skipped")
    _require(
        value["ranking_evaluation"].get("enrichment_queries") == 0,
        "enrichment was not skipped",
    )


def validate_unreachable(value: dict[str, Any]) -> None:
    range_basis, candidates = _validate_common(
        value,
        expected_state="no_reachable_station",
        profile_name="unreachable",
    )
    _require(not candidates and value.get("itinerary") is None, "unreachable must not plan stops")
    _require(range_basis.get("destination_reachable_with_reserve") is False, "destination in range")
    _require(
        value["reachability_evaluation"].get("reachable_before_reserve_count") == 0,
        "a station was unexpectedly reachable",
    )


def prepare_itinerary_route(predictive_path: Path, output: Path) -> None:
    value = _read(predictive_path)
    station_ids = validate_suggested(value, profile_name="multi-stop", minimum_stops=3)
    profile = PROFILES["multi-stop"]
    _write(
        output,
        {
            "origin": ORIGIN,
            "destination": DESTINATION,
            "mimit_station_ids": station_ids,
            "effective_cng_range_km": profile["effective"],
            "estimated_remaining_cng_range_km": profile["remaining"],
            "reserve_cng_range_km": profile["reserve"],
        },
    )
    print(f"Multi-stop route request with {len(station_ids)} refuelling stops written to {output}")


def validate_selected_itinerary(
    value: dict[str, Any],
    predictive_value: dict[str, Any],
) -> None:
    expected_ids = validate_suggested(
        predictive_value,
        profile_name="multi-stop",
        minimum_stops=3,
    )
    selected = value.get("selected_stops")
    _require(isinstance(selected, list), "selected_stops must be an array")
    _require(
        [item.get("mimit_station_id") for item in selected] == expected_ids,
        "selected route changed the planned stop order",
    )
    _require(value.get("provider") == "valhalla", "selected route provider must be valhalla")
    _require(
        value.get("range_validation") == "all_legs_preserve_reserve",
        "selected route did not validate every leg",
    )
    distance = _number(value.get("distance_meters"), "distance_meters", positive=True)
    duration = _number(value.get("duration_seconds"), "duration_seconds", positive=True)
    legs = value.get("legs")
    _require(isinstance(legs, list) and len(legs) == len(selected) + 1, "route leg count mismatch")
    expected_kinds = ["origin_to_cng_station"] + [
        "cng_station_to_cng_station"
    ] * (len(selected) - 1) + ["cng_station_to_destination"]
    _require([leg.get("kind") for leg in legs] == expected_kinds, "route leg order is invalid")
    profile = PROFILES["multi-stop"]
    for index, leg in enumerate(legs, start=1):
        field = f"legs[{index - 1}]"
        _require(isinstance(leg, dict), f"{field} must be an object")
        _require(leg.get("sequence") == index, f"{field} sequence is invalid")
        _route(leg, field)
        available = profile["remaining"] if index == 1 else profile["effective"]
        remaining = available - float(leg["distance_meters"]) / 1_000
        _require(
            math.isclose(float(leg.get("available_range_at_departure_km")), available),
            f"{field} available range is wrong",
        )
        _require(
            math.isclose(
                float(leg.get("estimated_remaining_range_at_arrival_km")),
                remaining,
                abs_tol=0.001,
            ),
            f"{field} remaining range does not reconcile",
        )
        _require(
            math.isclose(
                float(leg.get("reserve_margin_at_arrival_km")),
                remaining - profile["reserve"],
                abs_tol=0.001,
            ),
            f"{field} reserve margin does not reconcile",
        )
        _require(remaining >= profile["reserve"] - 0.001, f"{field} consumes the reserve")
    _require(
        abs(sum(float(leg["distance_meters"]) for leg in legs) - distance) <= COST_TOLERANCE,
        "selected route distance does not reconcile",
    )
    _require(
        abs(sum(float(leg["duration_seconds"]) for leg in legs) - duration) <= COST_TOLERANCE,
        "selected route duration does not reconcile",
    )


def validate_all(
    standard_path: Path,
    not_needed_path: Path,
    unreachable_path: Path,
    multi_stop_path: Path,
    selected_path: Path,
) -> None:
    standard_ids = validate_suggested(
        _read(standard_path),
        profile_name="standard",
        minimum_stops=1,
    )
    validate_not_needed(_read(not_needed_path))
    validate_unreachable(_read(unreachable_path))
    multi_value = _read(multi_stop_path)
    multi_ids = validate_suggested(
        multi_value,
        profile_name="multi-stop",
        minimum_stops=3,
    )
    validate_selected_itinerary(_read(selected_path), multi_value)
    print(
        json.dumps(
            {
                "standard_itinerary_stop_count": len(standard_ids),
                "multi_stop_edge_profile": "65 km remaining / 30 km reserve / 100 km full",
                "multi_stop_itinerary_station_ids": multi_ids,
                "multi_stop_route_leg_count": len(multi_ids) + 1,
                "range_validation": "all_legs_preserve_reserve",
                "not_needed_state": "not_needed",
                "unreachable_state": "no_reachable_station",
                "traffic_state": "not_configured",
            },
            indent=2,
        )
    )
    print("Phase 10 multi-refuelling live API contracts validated successfully.")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--profile", choices=tuple(PROFILES), required=True)
    prepare_parser.add_argument("--output", type=Path, required=True)

    openapi_parser = subparsers.add_parser("validate-openapi")
    openapi_parser.add_argument("--openapi", type=Path, required=True)

    route_parser = subparsers.add_parser("prepare-itinerary-route")
    route_parser.add_argument("--predictive", type=Path, required=True)
    route_parser.add_argument("--output", type=Path, required=True)

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--standard", type=Path, required=True)
    validate_parser.add_argument("--not-needed", type=Path, required=True)
    validate_parser.add_argument("--unreachable", type=Path, required=True)
    validate_parser.add_argument("--multi-stop", type=Path, required=True)
    validate_parser.add_argument("--selected-route", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.profile, args.output)
    elif args.command == "validate-openapi":
        validate_openapi(args.openapi)
    elif args.command == "prepare-itinerary-route":
        prepare_itinerary_route(args.predictive, args.output)
    else:
        validate_all(
            args.standard,
            args.not_needed,
            args.unreachable,
            args.multi_stop,
            args.selected_route,
        )


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from None
