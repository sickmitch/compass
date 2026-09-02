#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--running-tileset-identity", required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--health", type=Path, required=True)
    parser.add_argument("--first-route", type=Path, required=True)
    parser.add_argument("--second-route", type=Path, required=True)
    parser.add_argument("--updater-logs", type=Path, required=True)
    parser.add_argument("--valhalla-logs", type=Path, required=True)
    args = parser.parse_args()
    validate(
        running_tileset_identity=args.running_tileset_identity,
        config=_read(args.config),
        state=_read(args.state),
        ledger=_read(args.ledger),
        health=_read(args.health),
        first_route=_read(args.first_route),
        second_route=_read(args.second_route),
        updater_logs=args.updater_logs.read_text(encoding="utf-8"),
        valhalla_logs=args.valhalla_logs.read_text(encoding="utf-8"),
    )
    return 0


def validate(
    *,
    running_tileset_identity: str,
    config: object,
    state: object,
    ledger: object,
    health: object,
    first_route: object,
    second_route: object,
    updater_logs: str,
    valhalla_logs: str,
) -> None:
    values = {
        "config": config,
        "state": state,
        "ledger": ledger,
        "health": health,
        "first_route": first_route,
        "second_route": second_route,
    }
    for name, value in values.items():
        _require(isinstance(value, dict), f"{name} must be a JSON object")
    assert isinstance(config, dict)
    assert isinstance(state, dict)
    assert isinstance(ledger, dict)
    assert isinstance(health, dict)
    assert isinstance(first_route, dict)
    assert isinstance(second_route, dict)

    _require(config.get("enabled") is True, "traffic is disabled")
    _require(config.get("provider") == "tomtom", "production provider is not TomTom")
    _require(config.get("provider_configuration_valid") is True, "provider is invalid")
    _require(config.get("provider_credentials_configured") is True, "credentials missing")
    _require(config.get("overlay_enabled") is True, "Valhalla overlay is disabled")
    _require(config.get("refresh_mode") == "on_demand", "refresh mode is not on-demand")
    _require(
        config.get("route_refresh_min_interval_seconds") == 300,
        "route refresh interval is not five minutes",
    )
    _require(config.get("traffic_extract_present") is True, "traffic.tar is missing")
    _require(config.get("native_helper_present") is True, "native helper is missing")
    _require(
        config.get("configured_tileset_identity") == running_tileset_identity,
        "configured and running Valhalla tilesets differ",
    )
    mapping_version = config.get("mapping_version")
    _require(
        mapping_version == "valhalla-openlr-geometry-v1",
        "production mapping version is not direction-verified OpenLR geometry",
    )

    _require(
        state.get("tileset_identity") == running_tileset_identity,
        "durable state tileset mismatch",
    )
    edges = state.get("edges")
    _require(isinstance(edges, list) and edges, "route refresh committed no managed edges")
    assert isinstance(edges, list)
    now = datetime.now(UTC)
    for edge in edges:
        _require(isinstance(edge, dict), "managed edge is malformed")
        assert isinstance(edge, dict)
        _require(edge.get("mapping_version") == mapping_version, "edge mapping mismatch")
        expires_at = datetime.fromisoformat(str(edge.get("expires_at")))
        _require(expires_at.tzinfo is not None and expires_at > now, "managed edge is stale")

    _require(
        ledger.get("tileset_identity") == running_tileset_identity,
        "refresh ledger tileset mismatch",
    )
    successes = ledger.get("successes")
    _require(
        isinstance(successes, dict) and len(successes) == 1,
        "the repeated route did not produce exactly one deduplicated scope",
    )

    _require(health.get("provider") == "tomtom", "health provider mismatch")
    _require(health.get("provider_status") == "fresh", "traffic health is not fresh")
    _require(health.get("traffic_aware_routing") is True, "routing is not traffic-aware")
    _require(health.get("managed_edge_count") == len(edges), "health edge count mismatch")
    _require(
        health.get("valhalla_tileset_version") == running_tileset_identity,
        "health tileset mismatch",
    )

    for name, route in (("first", first_route), ("second", second_route)):
        _require(route.get("provider") == "valhalla", f"{name} route provider mismatch")
        _positive_number(route.get("distance_meters"), f"{name} route distance")
        _positive_number(route.get("duration_seconds"), f"{name} route duration")

    _require(
        updater_logs.count("route-scoped traffic overlay update committed") == 1,
        "expected exactly one committed route-scoped provider refresh",
    )
    _require(
        "route-scoped traffic refresh skipped by minimum interval" in updater_logs,
        "the immediate repeat was not deduplicated",
    )
    _require("TOMTOM_API_KEY" not in updater_logs, "credential name leaked")
    _require("key=" not in updater_logs, "provider credential leaked")
    _require(
        valhalla_logs.count("algorithm::time_dependent_forward_a*") >= 3,
        "expected initial, refreshed and repeated time-dependent routes",
    )

    print(
        json.dumps(
            {
                "activation": "accepted",
                "provider": "tomtom",
                "provider_api_mode": config.get("provider_api_mode"),
                "refresh_mode": "on_demand",
                "minimum_interval_seconds": 300,
                "route_scope_count": 1,
                "managed_edge_count": len(edges),
                "tileset_identity": running_tileset_identity,
                "traffic_health": "fresh",
                "immediate_repeat": "skipped_recent",
                "provider_polling": "disabled",
                "credential_logging": "not_detected",
            },
            indent=2,
            sort_keys=True,
        )
    )
    print("On-demand TomTom traffic activation accepted.")


def _positive_number(value: object, field: str) -> None:
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
