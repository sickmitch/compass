from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


def _load_validator_module():
    path = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "validate-traffic-cng-routing-live.py"
    )
    spec = importlib.util.spec_from_file_location(
        "validate_traffic_cng_routing_live", path
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["validate_traffic_cng_routing_live"] = module
    spec.loader.exec_module(module)
    return module


def _payloads():
    departure_at = "2026-09-01T10:00:00+02:00"
    schemas = {
        name: {"properties": {"departure_at": {"type": ["string", "null"]}}}
        for name in (
            "BaseRouteRequest",
            "RouteWithCngStopRequest",
            "RouteWithCngItineraryRequest",
        )
    }
    cost_basis = {
        "provider": "valhalla",
        "traffic_state": "fresh",
        "traffic_aware": True,
        "duration_model": "valhalla_time_dependent_traffic",
        "distance_model": "road_network",
    }
    ranking = {
        "total_score": 0.8,
        "detour_contribution": 0.4,
        "opening_contribution": 0.25,
        "price_contribution": 0.1,
        "price_freshness_contribution": 0.05,
        "availability_multiplier": 1.0,
    }
    return {
        "departure_at": departure_at,
        "openapi": {"components": {"schemas": schemas}},
        "base": {"distance_meters": 210_000, "duration_seconds": 7_000},
        "ranked": {
            "departure_at": departure_at,
            "cost_basis": cost_basis,
            "candidates": [
                {
                    "mimit_station_id": "43690",
                    "station_eta": "2026-09-01T10:20:00+02:00",
                    "ranking": ranking,
                }
            ],
        },
        "selected": {
            "provider": "valhalla",
            "duration_seconds": 7_100,
            "selected_stop": {"mimit_station_id": "43690"},
            "legs": [
                {"duration_seconds": 1_100},
                {"duration_seconds": 6_000},
            ],
        },
        "predictive": {
            "departure_at": departure_at,
            "cost_basis": cost_basis,
            "suggestion_state": "suggested",
            "reachability_evaluation": {"pairwise_matrix_calls": 1},
        },
        "logs": (
            "POST /route HTTP/1.1\n"
            "algorithm::time_dependent_forward_a*\n"
            "POST /sources_to_targets HTTP/1.1\n"
        ),
    }


def test_cng_traffic_validator_accepts_explainable_time_dependent_contract(capsys) -> None:
    module = _load_validator_module()
    values = _payloads()

    module.validate(
        departure_at=values["departure_at"],
        openapi=values["openapi"],
        base=values["base"],
        ranked=values["ranked"],
        selected=values["selected"],
        predictive=values["predictive"],
        valhalla_logs=values["logs"],
    )

    assert "traffic-aware" in capsys.readouterr().out.lower()


def test_cng_traffic_validator_rejects_unexplained_ranking_penalty() -> None:
    module = _load_validator_module()
    values = _payloads()
    values["ranked"]["candidates"][0]["ranking"]["total_score"] = 0.7

    with pytest.raises(AssertionError, match="unexplained traffic penalty"):
        module.validate(
            departure_at=values["departure_at"],
            openapi=values["openapi"],
            base=values["base"],
            ranked=values["ranked"],
            selected=values["selected"],
            predictive=values["predictive"],
            valhalla_logs=values["logs"],
        )


def test_cng_traffic_runner_is_isolated_and_read_only() -> None:
    runner = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "run-traffic-cng-routing-live.sh"
    ).read_text(encoding="utf-8")

    assert "run --no-deps -d" in runner
    assert "compass-traffic-cng-api.log" in runner
    assert "traffic-aware CNG gate failed" in runner
    assert '"$base_path" "$ranked_path" "$selected_path" "$predictive_path"' in runner
    assert "docker inspect --format '{{.State.Running}}'" in runner
    assert 'docker rm --force "$api_container_id"' in runner
    assert "sha256sum /custom_files/traffic.tar" in runner
    assert "traffic_tar_sha_before" in runner
    assert "traffic_tar_sha_after" in runner
    assert "compass-traffic apply-once" not in runner
    assert "compass-valhalla-traffic-tool" not in runner


def test_api_service_does_not_receive_tomtom_feed_credentials() -> None:
    compose = (
        Path(__file__).resolve().parents[1] / "compose.yaml"
    ).read_text(encoding="utf-8")
    api_service = compose.split("  api:\n", 1)[1].split("  valhalla-tiles:\n", 1)[0]

    assert "TOMTOM_API_KEY" not in api_service
    assert "TOMTOM_FLOW_SEGMENT_POINTS" not in api_service
