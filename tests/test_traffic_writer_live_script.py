from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


def _load_validator_module():
    path = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "validate-traffic-writer-live.py"
    )
    spec = importlib.util.spec_from_file_location("validate_traffic_writer_live", path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["validate_traffic_writer_live"] = module
    spec.loader.exec_module(module)
    return module


def _payloads():
    identity = "valhalla-3.8.3:123"
    graph_id = "0/3017/196748"
    applied = {
        "provider": "tomtom",
        "configured_tileset_identity": identity,
        "segments_considered": 1,
        "segments_accepted": 1,
        "edges_set": 2,
        "edges_reset": 0,
        "managed_edge_count": 2,
        "state_persisted": True,
        "provider_overlay_write_enabled": True,
        "rejected_segments": [],
    }
    state = {
        "schema_version": 1,
        "tileset_identity": identity,
        "edges": [
            {"graph_id": graph_id, "speed_kph": 127, "closed": False},
            {"graph_id": "0/3017/196771", "speed_kph": 127, "closed": False},
        ],
    }
    inspect_set = {
        "graph_id": graph_id,
        "speed_valid": True,
        "overall_speed_kph": 128,
        "closed": False,
    }
    cleared = {
        "configured_tileset_identity": identity,
        "edges_reset": 2,
        "managed_edge_count": 0,
        "state_persisted": True,
    }
    cleared_state = {"schema_version": 1, "tileset_identity": identity, "edges": []}
    inspect_reset = {"graph_id": graph_id, "speed_valid": False, "closed": False}
    return applied, state, inspect_set, cleared, cleared_state, inspect_reset


def test_writer_live_validator_accepts_set_persist_and_reset(capsys) -> None:
    module = _load_validator_module()
    values = _payloads()

    module.validate(
        applied=values[0],
        state=values[1],
        inspect_set=values[2],
        cleared=values[3],
        cleared_state=values[4],
        inspect_reset=values[5],
    )

    assert "write and reset accepted" in capsys.readouterr().out


def test_writer_live_validator_rejects_a_speed_left_after_reset() -> None:
    module = _load_validator_module()
    values = list(_payloads())
    values[5]["speed_valid"] = True

    with pytest.raises(AssertionError, match="still has live speed"):
        module.validate(
            applied=values[0],
            state=values[1],
            inspect_set=values[2],
            cleared=values[3],
            cleared_state=values[4],
            inspect_reset=values[5],
        )


def test_writer_runner_has_emergency_cleanup_and_root_only_for_one_shots() -> None:
    runner = (
        Path(__file__).resolve().parents[1] / "scripts" / "run-traffic-writer-live.sh"
    ).read_text(encoding="utf-8")

    assert "apply-once --limit 1" in runner
    assert runner.count("compass-traffic clear-managed") >= 2
    assert "trap cleanup EXIT INT TERM" in runner
    assert "--user 0:0" in runner
    assert "TRAFFIC_VALHALLA_OVERLAY_ENABLED=true" in runner
