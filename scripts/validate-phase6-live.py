#!/usr/bin/env python3
"""Validate and summarize the operator-run Compass Phase 6 live responses."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


class ValidationFailure(RuntimeError):
    """A human-readable Phase 6 acceptance failure."""


_FINAL_SUNDAY_HOLIDAY_OFF = re.compile(
    r"(?:^|;)\s*(?:Su\s*,\s*PH|PH\s*,\s*Su)\s+(?:off|closed)\s*$"
)


def check(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationFailure(message)


def aware_datetime(value: str, field: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    check(parsed.utcoffset() is not None, f"{field} must include a UTC offset")
    return parsed


def close(left: float, right: float, tolerance: float = 0.000005) -> bool:
    return abs(left - right) < tolerance


def validate_payload(payload: dict[str, Any]) -> None:
    check(payload.get("stage") == "ranking", "stage must be ranking")
    cost_basis = payload["cost_basis"]
    check(cost_basis["traffic_state"] == "not_configured", "traffic state must be explicit")
    check(cost_basis["traffic_aware"] is False, "traffic_aware must be false")
    check(cost_basis["distance_model"] == "road_network", "distance model must be road_network")

    policy = payload["ranking_policy"]
    check(
        policy["opening_hours_timezone"] == "Europe/Rome",
        "opening-hours timezone must be Europe/Rome",
    )
    weight_sum = sum(
        policy[field]
        for field in (
            "detour_weight",
            "opening_weight",
            "price_weight",
            "price_freshness_weight",
        )
    )
    check(close(weight_sum, 1.0, 1e-9), "ranking weights must sum to one")

    spatial = payload["spatial_pruning"]
    network = payload["network_evaluation"]
    ranking = payload["ranking_evaluation"]
    candidates = payload["candidates"]
    eligible = network["eligible_candidate_count"]
    check(
        network["spatial_candidate_count"] == spatial["corridor_candidate_count"],
        "network input count must match the pre-limit spatial corridor count",
    )
    check(
        network["matrix_candidate_count"] == spatial["returned_candidate_count"],
        "matrix count must match the spatially returned count",
    )
    check(
        network["reachable_candidate_count"] + network["unreachable_candidate_count"]
        == network["matrix_candidate_count"],
        "reachable and unreachable counts must reconcile",
    )
    check(
        eligible + network["excluded_by_detour_count"]
        == network["reachable_candidate_count"],
        "eligible and detour-excluded counts must reconcile",
    )
    check(network["base_route_calls"] == 1, "exactly one base route call is required")
    check(
        network["per_candidate_route_calls"] == 0,
        "no per-candidate full route calls are allowed",
    )
    batch_size = network["matrix_batch_size"]
    matrix_count = network["matrix_candidate_count"]
    minimum_calls = 0 if matrix_count == 0 else 2 * ((matrix_count + batch_size - 1) // batch_size)
    expected_calls = minimum_calls + 4 * network["matrix_fallback_splits"]
    check(network["matrix_calls"] == expected_calls, "matrix call count is inconsistent")

    check(
        ranking["detour_eligible_candidate_count"] == eligible,
        "ranking input must equal detour eligibility",
    )
    check(
        ranking["opening_open_count"]
        + ranking["opening_closed_count"]
        + ranking["opening_unknown_count"]
        == eligible,
        "opening-state counts must reconcile",
    )
    check(
        ranking["opening_valid_count"]
        + ranking["opening_missing_count"]
        + ranking["opening_invalid_count"]
        == eligible,
        "opening-validation counts must reconcile",
    )
    check(
        ranking["price_available_count"] + ranking["price_missing_count"] == eligible,
        "price counts must reconcile",
    )
    check(ranking["ranked_candidate_count"] == len(candidates), "ranked count must match array")
    expected_enrichment_queries = 1 if eligible else 0
    check(
        ranking["enrichment_queries"] == expected_enrichment_queries,
        "enrichment must use one query for a non-empty tuple and zero otherwise",
    )

    ranks = [candidate["ranking"]["rank"] for candidate in candidates]
    check(ranks == list(range(1, len(candidates) + 1)), "ranks must be contiguous")
    totals = [candidate["ranking"]["total_score"] for candidate in candidates]
    check(totals == sorted(totals, reverse=True), "total scores must descend")

    maximum_detour_seconds = payload["maximum_detour_minutes"] * 60
    for candidate in candidates:
        station_id = candidate["mimit_station_id"]
        prefix = f"candidate {station_id}"
        check(
            candidate["detour_duration_seconds"] <= maximum_detour_seconds + 0.000001,
            f"{prefix} exceeds maximum detour",
        )
        check(
            close(
                candidate["detour_minutes"] * 60,
                candidate["detour_duration_seconds"],
                0.001,
            ),
            f"{prefix} detour units do not agree",
        )
        check(
            close(
                candidate["route_via_station_distance_meters"],
                candidate["distance_from_previous_waypoint_meters"]
                + candidate["station_to_destination_distance_meters"],
                0.001,
            ),
            f"{prefix} road-distance legs do not sum",
        )
        check(
            close(
                candidate["route_via_station_duration_seconds"],
                candidate["duration_from_previous_waypoint_seconds"]
                + candidate["station_to_destination_duration_seconds"],
                0.001,
            ),
            f"{prefix} road-duration legs do not sum",
        )
        station_eta = aware_datetime(candidate["station_eta"], f"{prefix}.station_eta")
        aware_datetime(candidate["destination_eta"], f"{prefix}.destination_eta")

        opening = candidate["opening"]
        state = opening["state"]
        validation = opening["validation"]
        check(state in {"open", "closed", "unknown"}, f"{prefix} has invalid opening state")
        check(
            validation in {"valid", "missing", "invalid"},
            f"{prefix} has invalid opening validation",
        )
        check(opening["timezone"] == "Europe/Rome", f"{prefix} has wrong timezone")
        evaluated_at = aware_datetime(opening["evaluated_at"], f"{prefix}.opening.evaluated_at")
        check(evaluated_at == station_eta, f"{prefix} opening state was not evaluated at ETA")
        if opening["next_change_at"] is not None:
            aware_datetime(opening["next_change_at"], f"{prefix}.opening.next_change_at")
        expression = opening["opening_hours"]
        if (
            isinstance(expression, str)
            and station_eta.weekday() == 6
            and _FINAL_SUNDAY_HOLIDAY_OFF.search(expression)
        ):
            check(state == "closed", f"{prefix} has a final Sunday/holiday off rule but is open")
        if validation == "missing":
            check(state == "unknown", f"{prefix} missing hours must be unknown")
            check(expression is None, f"{prefix} missing expression must be null")
            check(opening["source"] is None, f"{prefix} missing expression must have no source")
        elif validation == "invalid":
            check(state == "unknown", f"{prefix} invalid hours must be unknown")
            check(opening["source"] == "osm", f"{prefix} invalid expression source must be OSM")
        elif state in {"open", "closed"}:
            check(opening["source"] == "osm", f"{prefix} availability source must be OSM")
            check(bool(expression), f"{prefix} valid expression must be visible")

        score = candidate["ranking"]
        contributions = sum(
            score[field]
            for field in (
                "detour_contribution",
                "opening_contribution",
                "price_contribution",
                "price_freshness_contribution",
            )
        )
        check(
            close(score["total_score"], contributions * score["availability_multiplier"]),
            f"{prefix} score contributions do not reproduce total",
        )
        for name in ("detour", "opening", "price", "price_freshness"):
            check(
                close(
                    score[f"{name}_contribution"],
                    score[f"{name}_score"] * policy[f"{name}_weight"],
                    0.000002,
                ),
                f"{prefix} {name} contribution is inconsistent",
            )
        expected_opening_score = {
            "open": 1.0,
            "closed": 0.0,
            "unknown": policy["unknown_opening_score"],
        }[state]
        check(
            score["opening_score"] == expected_opening_score,
            f"{prefix} opening score is inconsistent",
        )
        expected_multiplier = policy["closed_score_multiplier"] if state == "closed" else 1.0
        check(
            score["availability_multiplier"] == expected_multiplier,
            f"{prefix} availability multiplier is inconsistent",
        )

        price = candidate["price"]
        if price is not None:
            check(price["currency"] == "EUR", f"{prefix} price currency must be EUR")
            check(price["unit"] == "kg", f"{prefix} price unit must be kg")
            check(price["unit_price"] > 0, f"{prefix} price must be positive")
            observed_at = aware_datetime(price["observed_at"], f"{prefix}.price.observed_at")
            aware_datetime(price["ingested_at"], f"{prefix}.price.ingested_at")
            freshness = price["freshness_state"]
            check(
                freshness in {"fresh", "stale", "future_observation", "unknown"},
                f"{prefix} has invalid price freshness",
            )
            if price["age_seconds"] is not None:
                expected_age = (station_eta - observed_at).total_seconds()
                check(
                    close(price["age_seconds"], expected_age, 0.001),
                    f"{prefix} price age is not relative to station ETA",
                )
            horizon = policy["price_freshness_hours"] * 3600
            if freshness == "fresh":
                check(
                    price["age_seconds"] is not None and 0 <= price["age_seconds"] <= horizon,
                    f"{prefix} fresh price age is outside the horizon",
                )
            elif freshness == "stale":
                check(
                    price["age_seconds"] is not None and price["age_seconds"] > horizon,
                    f"{prefix} stale price age is inside the horizon",
                )
            elif freshness == "future_observation":
                check(
                    price["age_seconds"] is not None and price["age_seconds"] < 0,
                    f"{prefix} future price age must be negative",
                )
            else:
                check(price["age_seconds"] is None, f"{prefix} unknown price age must be null")


def validate_pair(
    default: dict[str, Any],
    with_closed: dict[str, Any],
    *,
    require_closed: bool,
) -> None:
    validate_payload(default)
    validate_payload(with_closed)
    default_metrics = default["ranking_evaluation"]
    all_metrics = with_closed["ranking_evaluation"]
    check(
        default["ranking_policy"]["closed_candidate_policy"] == "exclude",
        "default response must exclude closed candidates",
    )
    check(
        with_closed["ranking_policy"]["closed_candidate_policy"]
        == "include_with_zero_opening_score",
        "opt-in response must expose its closed-candidate policy",
    )
    check(
        default_metrics["ranked_candidate_count"] + default_metrics["excluded_closed_count"]
        == default_metrics["detour_eligible_candidate_count"],
        "default closed exclusion count must reconcile",
    )
    check(
        all_metrics["ranked_candidate_count"] == all_metrics["detour_eligible_candidate_count"],
        "opt-in response must retain every detour-eligible candidate",
    )
    check(all_metrics["excluded_closed_count"] == 0, "opt-in response cannot exclude closed")
    check(
        all(candidate["opening"]["state"] != "closed" for candidate in default["candidates"]),
        "default response returned a closed candidate",
    )
    if require_closed:
        check(all_metrics["opening_closed_count"] > 0, "expected at least one live closed sample")

    default_ids = {candidate["station_id"] for candidate in default["candidates"]}
    included_non_closed_ids = {
        candidate["station_id"]
        for candidate in with_closed["candidates"]
        if candidate["opening"]["state"] != "closed"
    }
    check(
        default_ids == included_non_closed_ids,
        "default candidates must equal the opt-in response's non-closed candidates",
    )


def validate_openapi(document: dict[str, Any]) -> dict[str, Any]:
    operation = document["paths"]["/api/v1/cng/ranked-candidates"]["post"]
    reference = operation["requestBody"]["content"]["application/json"]["schema"]["$ref"]
    schema = document["components"]["schemas"][reference.rsplit("/", 1)[-1]]
    required = set(schema["required"])
    expected = {
        "origin",
        "destination",
        "effective_cng_range_km",
        "maximum_detour_minutes",
        "departure_at",
    }
    check(expected <= required, "OpenAPI is missing required ranked-candidate inputs")
    check("include_closed" not in required, "include_closed must remain optional")
    check(
        schema["properties"]["include_closed"]["default"] is False,
        "include_closed OpenAPI default must be false",
    )
    return {
        "required_request_fields": sorted(required),
        "include_closed_default": False,
    }


def compact(candidate: dict[str, Any] | None) -> dict[str, Any] | None:
    if candidate is None:
        return None
    return {
        "rank": candidate["ranking"]["rank"],
        "mimit_station_id": candidate["mimit_station_id"],
        "name": candidate["name"],
        "station_eta": candidate["station_eta"],
        "opening": candidate["opening"],
        "detour_minutes": candidate["detour_minutes"],
        "price": candidate["price"],
        "ranking": candidate["ranking"],
    }


def summarize(
    default: dict[str, Any],
    with_closed: dict[str, Any],
    openapi_summary: dict[str, Any] | None,
) -> dict[str, Any]:
    samples_by_state: dict[str, dict[str, Any]] = {}
    for candidate in with_closed["candidates"]:
        samples_by_state.setdefault(candidate["opening"]["state"], candidate)
    summary = {
        "default_counts": default["ranking_evaluation"],
        "include_closed_counts": with_closed["ranking_evaluation"],
        "ranking_policy": default["ranking_policy"],
        "network_evaluation": default["network_evaluation"],
        "top_default": [compact(candidate) for candidate in default["candidates"][:5]],
        "sample_open": compact(samples_by_state.get("open")),
        "sample_closed": compact(samples_by_state.get("closed")),
        "sample_unknown": compact(samples_by_state.get("unknown")),
    }
    if openapi_summary is not None:
        summary["openapi"] = openapi_summary
    return summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate and summarize Compass Phase 6 live JSON artifacts."
    )
    parser.add_argument("--default", type=Path, required=True, help="default ranked response JSON")
    parser.add_argument(
        "--with-closed", type=Path, required=True, help="include_closed ranked response JSON"
    )
    parser.add_argument("--openapi", type=Path, help="optional live OpenAPI document JSON")
    parser.add_argument(
        "--require-closed",
        action="store_true",
        help="require at least one closed-at-ETA candidate in the opt-in response",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    check(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def main() -> int:
    args = parse_args()
    try:
        default = load_json(args.default)
        with_closed = load_json(args.with_closed)
        validate_pair(default, with_closed, require_closed=args.require_closed)
        openapi_summary = validate_openapi(load_json(args.openapi)) if args.openapi else None
    except (
        OSError,
        json.JSONDecodeError,
        KeyError,
        TypeError,
        ValueError,
        ValidationFailure,
    ) as error:
        print(f"Phase 6 validation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(summarize(default, with_closed, openapi_summary), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
