from __future__ import annotations

import importlib.util
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest


def _load_validator_module():
    path = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "validate-traffic-plan-live.py"
    )
    spec = importlib.util.spec_from_file_location("validate_traffic_plan_live", path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["validate_traffic_plan_live"] = module
    spec.loader.exec_module(module)
    return module


def _valid_payload() -> dict[str, object]:
    return {
        "provider": "tomtom",
        "configured_tileset_identity": "valhalla-3.8.3:123",
        "segments_considered": 1,
        "segments_accepted": 1,
        "planned_edge_update_count": 1,
        "planned_edge_reset_count": 0,
        "active_edge_count_after": 1,
        "mapping_versions": ["valhalla-openlr-geometry-v1"],
        "provider_overlay_write_enabled": False,
        "state_persisted": False,
        "set_updates": [
            {
                "graph_id": "0/3017/196748",
                "speed_kph": 35,
                "congestion": 0.5,
                "closed": False,
                "has_incidents": False,
                "expires_at": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
            }
        ],
        "reset_graph_ids": [],
        "rejected_segments": [],
    }


def test_plan_live_validator_accepts_safe_dry_run(capsys: pytest.CaptureFixture[str]) -> None:
    module = _load_validator_module()

    module.validate(_valid_payload())

    assert "dry-run plan accepted" in capsys.readouterr().out


def test_plan_live_validator_rejects_zero_speed_without_closure() -> None:
    module = _load_validator_module()
    payload = _valid_payload()
    payload["set_updates"][0]["speed_kph"] = 0  # type: ignore[index]

    with pytest.raises(AssertionError, match="explicit closures"):
        module.validate(payload)


def test_plan_runner_forces_read_only_and_checks_overlay_hash() -> None:
    runner = (
        Path(__file__).resolve().parents[1] / "scripts" / "run-traffic-plan-live.sh"
    ).read_text(encoding="utf-8")

    assert "compass-traffic plan-once" in runner
    assert "TRAFFIC_VALHALLA_OVERLAY_ENABLED=false" in runner
    assert runner.count("sha256sum /custom_files/traffic.tar") == 2
    assert 'test "$traffic_hash_before" = "$traffic_hash_after"' in runner
