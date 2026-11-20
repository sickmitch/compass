from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


def _load_validator_module():
    path = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "validate-traffic-updater-live.py"
    )
    spec = importlib.util.spec_from_file_location("validate_traffic_updater_live", path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["validate_traffic_updater_live"] = module
    spec.loader.exec_module(module)
    return module


def _payloads():
    graph_id = "0/3017/196748"
    state = {
        "schema_version": 1,
        "tileset_identity": "valhalla-3.8.3:123",
        "edges": [{"graph_id": graph_id, "speed_kph": 123, "closed": False}],
    }
    inspect_set = {
        "graph_id": graph_id,
        "speed_valid": True,
        "overall_speed_kph": 124,
        "closed": False,
    }
    clear = {
        "configured_tileset_identity": "valhalla-3.8.3:123",
        "edges_reset": 1,
        "managed_edge_count": 0,
    }
    cleared_state = {
        "schema_version": 1,
        "tileset_identity": "valhalla-3.8.3:123",
        "edges": [],
    }
    inspect_reset = {"graph_id": graph_id, "speed_valid": False, "closed": False}
    health_fresh = {
        "enabled": True,
        "provider": "tomtom",
        "provider_status": "fresh",
        "traffic_aware_routing": True,
        "valhalla_tileset_version": "valhalla-3.8.3:123",
        "managed_edge_count": 1,
        "edges_updated": 1,
        "last_success_at": "2026-09-01T08:00:01+00:00",
    }
    health_cleared = {
        "provider_status": "unavailable",
        "valhalla_tileset_version": "valhalla-3.8.3:123",
        "managed_edge_count": 0,
        "edges_expired": 1,
        "message": "Valhalla fallback speeds are active",
    }
    return (
        state,
        inspect_set,
        clear,
        cleared_state,
        inspect_reset,
        health_fresh,
        health_cleared,
    )


def test_periodic_updater_validator_accepts_clean_transaction(capsys) -> None:
    module = _load_validator_module()
    values = _payloads()

    module.validate(
        state=values[0],
        health_fresh=values[5],
        inspect_set=values[1],
        logs='{"message":"traffic overlay update committed"}',
        cleared=values[2],
        cleared_state=values[3],
        health_cleared=values[6],
        inspect_reset=values[4],
    )

    assert "periodic" in capsys.readouterr().out.lower()


def test_periodic_updater_validator_rejects_credential_logging() -> None:
    module = _load_validator_module()
    values = _payloads()

    with pytest.raises(AssertionError, match="credential leaked"):
        module.validate(
            state=values[0],
            health_fresh=values[5],
            inspect_set=values[1],
            logs=(
                '{"message":"traffic overlay update committed"}\n'
                '{"message":"GET https://provider.test/?key=secret"}'
            ),
            cleared=values[2],
            cleared_state=values[3],
            health_cleared=values[6],
            inspect_reset=values[4],
        )


def test_periodic_runner_stops_and_cleans_on_exit() -> None:
    runner = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "run-traffic-updater-live.sh"
    ).read_text(encoding="utf-8")

    assert "up -d --no-deps traffic-updater" in runner
    assert runner.count("stop traffic-updater") >= 2
    assert runner.count("compass-traffic clear-managed") >= 2
    assert "trap cleanup EXIT INT TERM" in runner
    assert "TRAFFIC_UPDATE_SEGMENT_LIMIT=1" in runner
    assert "TRAFFIC_STATE_PATH=/custom_files/compass_traffic_state/state.json" in runner
    assert "TRAFFIC_HEALTH_PATH=/custom_files/compass_traffic_state/health.json" in runner
    assert "/api/v1/traffic/health" in runner
