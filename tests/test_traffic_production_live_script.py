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
        / "validate-traffic-production-live.py"
    )
    spec = importlib.util.spec_from_file_location(
        "validate_traffic_production_live", path
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["validate_traffic_production_live"] = module
    spec.loader.exec_module(module)
    return module


def _payloads():
    identity = "valhalla-3.8.3:123"
    mapping = "valhalla-openlr-geometry-v1"
    expires_at = (datetime.now(UTC) + timedelta(minutes=5)).isoformat()
    config = {
        "enabled": True,
        "provider": "tomtom",
        "provider_configuration_valid": True,
        "provider_credentials_configured": True,
        "provider_api_mode": "flow_segment",
        "configured_probe_count": 3,
        "refresh_mode": "on_demand",
        "route_refresh_min_interval_seconds": 300,
        "overlay_enabled": True,
        "configured_tileset_identity": identity,
        "mapping_version": mapping,
        "traffic_extract_present": True,
        "native_helper_present": True,
        "managed_edge_count": 0,
    }
    state = {
        "tileset_identity": identity,
        "edges": [
            {
                "graph_id": "0/3017/196552",
                "mapping_version": mapping,
                "expires_at": expires_at,
            }
        ],
    }
    health = {
        "enabled": True,
        "provider": "tomtom",
        "provider_status": "fresh",
        "traffic_aware_routing": True,
        "valhalla_tileset_version": identity,
        "managed_edge_count": 1,
        "last_success_at": datetime.now(UTC).isoformat(),
    }
    route = {
        "provider": "valhalla",
        "distance_meters": 210_925,
        "duration_seconds": 6_800,
    }
    ledger = {"tileset_identity": identity, "successes": {"a" * 64: expires_at}}
    updater_logs = (
        '{"message":"route-scoped traffic overlay update committed"}\n'
        '{"message":"route-scoped traffic refresh skipped by minimum interval"}'
    )
    valhalla_logs = "\n".join(["algorithm::time_dependent_forward_a*"] * 3)
    return identity, config, state, ledger, health, route, updater_logs, valhalla_logs


def test_production_validator_accepts_persistent_fresh_activation(capsys) -> None:
    module = _load_validator_module()
    values = _payloads()

    module.validate(
        running_tileset_identity=values[0],
        config=values[1],
        state=values[2],
        ledger=values[3],
        health=values[4],
        first_route=values[5],
        second_route=values[5],
        updater_logs=values[6],
        valhalla_logs=values[7],
    )

    assert "on-demand" in capsys.readouterr().out.lower()


def test_production_validator_rejects_tileset_mismatch() -> None:
    module = _load_validator_module()
    values = _payloads()
    values[1]["configured_tileset_identity"] = "valhalla-3.8.3:other"

    with pytest.raises(AssertionError, match="tilesets differ"):
        module.validate(
            running_tileset_identity=values[0],
            config=values[1],
            state=values[2],
            ledger=values[3],
            health=values[4],
            first_route=values[5],
            second_route=values[5],
            updater_logs=values[6],
            valhalla_logs=values[7],
        )


def test_production_runner_has_preflight_rollback_and_leaves_services_running() -> None:
    runner = (
        Path(__file__).resolve().parents[1] / "scripts" / "deploy-traffic-live.sh"
    ).read_text(encoding="utf-8")

    assert "compass-traffic config-check" in runner
    assert "compass-traffic plan-once" not in runner
    assert "TRAFFIC_REFRESH_MODE=on_demand" in runner
    assert 'export TRAFFIC_VALHALLA_TILESET_VERSION="$running_tileset_identity"' in runner
    assert "Configured tileset identity is stale" in runner
    assert "trap rollback_failed_activation EXIT INT TERM" in runner
    assert "compass-traffic clear-managed" in runner
    assert "compass-traffic clear-route-refreshes" in runner
    assert "state_identity_matches_configured" in runner
    assert "valhalla-traffic-extract" in runner
    assert "reinitialize-state-after-traffic-rebuild" in runner
    assert "--previous-tileset" in runner
    assert runner.count('"${api_base_url}/api/v1/routes"') == 2
    assert "--force-recreate" in runner
    assert "/api/v1/traffic/health" in runner
    assert "algorithm::time_dependent_forward_a*" not in runner
    assert "activation_accepted=true" in runner
    assert "stop traffic-updater" not in runner.split(
        "ON-DEMAND TRAFFIC ACTIVATION COMPLETED", 1
    )[1]
