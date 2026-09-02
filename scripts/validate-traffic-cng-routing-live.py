#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--departure-at", required=True)
    parser.add_argument("--openapi", type=Path, required=True)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--ranked", type=Path, required=True)
    parser.add_argument("--selected", type=Path, required=True)
    parser.add_argument("--predictive", type=Path, required=True)
    parser.add_argument("--valhalla-logs", type=Path, required=True)
    args = parser.parse_args()
    validate(
        departure_at=args.departure_at,
        openapi=_read(args.openapi),
        base=_read(args.base),
        ranked=_read(args.ranked),
        selected=_read(args.selected),
        predictive=_read(args.predictive),
        valhalla_logs=args.valhalla_logs.read_text(encoding="utf-8"),
    )
    return 0


def validate(
    *,
    departure_at: str,
    openapi: object,
    base: object,
    ranked: object,
    selected: object,
    predictive: object,
    valhalla_logs: str,
) -> None:
    for name, value in (
        ("openapi", openapi),
        ("base", base),
        ("ranked", ranked),
        ("selected", selected),
        ("predictive", predictive),
    ):
        _require(isinstance(value, dict), f"{name} must be a JSON object")
    assert isinstance(openapi, dict)
    assert isinstance(base, dict)
    assert isinstance(ranked, dict)
    assert isinstance(selected, dict)
    assert isinstance(predictive, dict)

    schemas = openapi["components"]["schemas"]
    for schema_name in (
        "BaseRouteRequest",
        "RouteWithCngStopRequest",
        "RouteWithCngItineraryRequest",
    ):
        schema = schemas[schema_name]
        _require(
            "departure_at" in schema["properties"],
            f"{schema_name} does not expose departure_at",
        )
        _require(
            "departure_at" not in schema.get("required", []),
            f"{schema_name}.departure_at must remain optional",
        )

    _positive_number(base.get("distance_meters"), "base.distance_meters")
    _positive_number(base.get("duration_seconds"), "base.duration_seconds")
    _require(ranked.get("departure_at") == departure_at, "ranked departure changed")
    _traffic_cost_basis(ranked.get("cost_basis"), "ranked")
    candidates = ranked.get("candidates")
    _require(isinstance(candidates, list) and candidates, "ranked candidates are empty")
    assert isinstance(candidates, list)
    for candidate in candidates:
        _require(isinstance(candidate, dict), "ranked candidate is malformed")
        assert isinstance(candidate, dict)
        _positive_number(candidate.get("station_eta"), "candidate.station_eta", timestamp=True)
        ranking = candidate.get("ranking")
        _require(isinstance(ranking, dict), "candidate ranking is missing")
        assert isinstance(ranking, dict)
        contributions = sum(
            float(ranking[field])
            for field in (
                "detour_contribution",
                "opening_contribution",
                "price_contribution",
                "price_freshness_contribution",
            )
        )
        multiplier = float(ranking["availability_multiplier"])
        _require(
            abs(float(ranking["total_score"]) - contributions * multiplier) <= 0.00001,
            "ranking contains an unexplained traffic penalty",
        )

    _require(selected.get("provider") == "valhalla", "selected route provider mismatch")
    legs = selected.get("legs")
    _require(isinstance(legs, list) and len(legs) == 2, "selected route must have two legs")
    assert isinstance(legs, list)
    leg_duration = sum(float(leg["duration_seconds"]) for leg in legs)
    _require(
        abs(float(selected["duration_seconds"]) - leg_duration) <= 10,
        "selected route duration differs from its leg sum",
    )

    _require(predictive.get("departure_at") == departure_at, "predictive departure changed")
    _traffic_cost_basis(predictive.get("cost_basis"), "predictive")
    reachability = predictive.get("reachability_evaluation")
    _require(isinstance(reachability, dict), "predictive reachability is missing")
    assert isinstance(reachability, dict)
    _require(
        isinstance(reachability.get("pairwise_matrix_calls"), int),
        "predictive pairwise matrix metric is not numeric",
    )

    _require("POST /route" in valhalla_logs, "Valhalla route evidence missing")
    _require(
        "POST /sources_to_targets" in valhalla_logs,
        "Valhalla matrix evidence missing",
    )
    _require(
        "algorithm::time_dependent_forward_a*" in valhalla_logs,
        "Valhalla did not use time-dependent route search",
    )

    print(
        json.dumps(
            {
                "departure_at": departure_at,
                "ranked_candidate_count": len(candidates),
                "selected_mimit_station_id": selected["selected_stop"][
                    "mimit_station_id"
                ],
                "selected_route_leg_count": len(legs),
                "predictive_state": predictive.get("suggestion_state"),
                "predictive_pairwise_matrix_calls": reachability.get(
                    "pairwise_matrix_calls"
                ),
                "duration_model": ranked["cost_basis"]["duration_model"],
                "ranking_traffic_penalty": "not_present",
            },
            indent=2,
            sort_keys=True,
        )
    )
    print("Traffic-aware Compass CNG routing contract accepted.")


def _traffic_cost_basis(value: object, name: str) -> None:
    _require(isinstance(value, dict), f"{name} cost basis is missing")
    assert isinstance(value, dict)
    _require(value.get("provider") == "valhalla", f"{name} provider mismatch")
    _require(value.get("traffic_aware") is True, f"{name} is not traffic-aware")
    _require(
        value.get("duration_model") == "valhalla_time_dependent_traffic",
        f"{name} duration model is not time-dependent traffic",
    )


def _positive_number(value: object, field: str, *, timestamp: bool = False) -> None:
    if timestamp:
        _require(isinstance(value, str) and value, f"{field} is missing")
        return
    _require(
        isinstance(value, int | float) and not isinstance(value, bool) and value > 0,
        f"{field} must be positive",
    )


def _read(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def _require(condition: object, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
