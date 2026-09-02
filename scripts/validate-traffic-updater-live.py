#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--health-fresh", type=Path, required=True)
    parser.add_argument("--inspect-set", type=Path, required=True)
    parser.add_argument("--logs", type=Path, required=True)
    parser.add_argument("--clear", type=Path, required=True)
    parser.add_argument("--cleared-state", type=Path, required=True)
    parser.add_argument("--health-cleared", type=Path, required=True)
    parser.add_argument("--inspect-reset", type=Path, required=True)
    args = parser.parse_args()
    validate(
        state=_read(args.state),
        health_fresh=_read(args.health_fresh),
        inspect_set=_read(args.inspect_set),
        logs=args.logs.read_text(encoding="utf-8"),
        cleared=_read(args.clear),
        cleared_state=_read(args.cleared_state),
        health_cleared=_read(args.health_cleared),
        inspect_reset=_read(args.inspect_reset),
    )
    return 0


def validate(
    *,
    state: object,
    health_fresh: object,
    inspect_set: object,
    logs: str,
    cleared: object,
    cleared_state: object,
    health_cleared: object,
    inspect_reset: object,
) -> None:
    for name, value in (
        ("state", state),
        ("health-fresh", health_fresh),
        ("inspect-set", inspect_set),
        ("clear", cleared),
        ("cleared-state", cleared_state),
        ("health-cleared", health_cleared),
        ("inspect-reset", inspect_reset),
    ):
        _require(isinstance(value, dict), f"{name} must be a JSON object")
    assert isinstance(state, dict)
    assert isinstance(health_fresh, dict)
    assert isinstance(inspect_set, dict)
    assert isinstance(cleared, dict)
    assert isinstance(cleared_state, dict)
    assert isinstance(health_cleared, dict)
    assert isinstance(inspect_reset, dict)

    identity = state.get("tileset_identity")
    _require(isinstance(identity, str) and identity, "state tileset identity missing")
    edges = state.get("edges")
    _require(isinstance(edges, list) and len(edges) > 0, "periodic updater set no edges")
    assert isinstance(edges, list)
    selected = edges[0]
    _require(isinstance(selected, dict), "managed edge is malformed")
    assert isinstance(selected, dict)
    graph_id = selected.get("graph_id")
    _require(inspect_set.get("graph_id") == graph_id, "wrong managed edge inspected")
    _require(inspect_set.get("speed_valid") is True, "periodic speed is not valid")
    encoded_speed = inspect_set.get("overall_speed_kph")
    provider_speed = selected.get("speed_kph")
    _require(
        isinstance(encoded_speed, int | float)
        and isinstance(provider_speed, int | float)
        and abs(float(encoded_speed) - float(provider_speed)) <= 1.0,
        "periodic encoded speed differs from managed state",
    )
    _require("traffic overlay update committed" in logs, "successful cycle log missing")
    _require("TOMTOM_API_KEY" not in logs, "credential name leaked into updater logs")
    _require("key=" not in logs, "provider query credential leaked into updater logs")

    _require(health_fresh.get("enabled") is True, "fresh health disabled traffic")
    _require(health_fresh.get("provider") == "tomtom", "fresh health provider mismatch")
    _require(health_fresh.get("provider_status") == "fresh", "provider is not fresh")
    _require(
        health_fresh.get("traffic_aware_routing") is True,
        "fresh health did not enable traffic-aware routing",
    )
    _require(
        health_fresh.get("valhalla_tileset_version") == identity,
        "fresh health tileset mismatch",
    )
    _require(
        health_fresh.get("managed_edge_count") == len(edges),
        "fresh health managed-edge count mismatch",
    )
    _require(
        health_fresh.get("edges_updated") == len(edges),
        "fresh health edge-update count mismatch",
    )
    _require(
        isinstance(health_fresh.get("last_success_at"), str),
        "fresh health last-success timestamp missing",
    )

    _require(cleared.get("configured_tileset_identity") == identity, "clear tileset mismatch")
    _require(cleared.get("edges_reset") == len(edges), "not all periodic edges were reset")
    _require(cleared.get("managed_edge_count") == 0, "managed state was not cleared")
    _require(cleared_state.get("edges") == [], "durable state remains populated")
    _require(inspect_reset.get("graph_id") == graph_id, "wrong reset edge inspected")
    _require(inspect_reset.get("speed_valid") is False, "reset edge still has live speed")
    _require(inspect_reset.get("closed") is False, "reset edge remained closed")
    _require(
        health_cleared.get("provider_status") == "unavailable",
        "cleared health did not expose Valhalla fallback",
    )
    _require(
        health_cleared.get("managed_edge_count") == 0,
        "cleared health retained managed edges",
    )
    _require(
        health_cleared.get("edges_expired") == len(edges),
        "cleared health reset count mismatch",
    )
    _require(
        health_cleared.get("valhalla_tileset_version") == identity,
        "cleared health tileset mismatch",
    )
    _require(
        "fallback" in str(health_cleared.get("message", "")).lower(),
        "cleared health does not explain fallback",
    )

    print(
        json.dumps(
            {
                "provider": "tomtom",
                "tileset_identity": identity,
                "periodic_managed_edge_count": len(edges),
                "inspected_graph_id": graph_id,
                "provider_speed_kph": provider_speed,
                "valhalla_encoded_speed_kph": encoded_speed,
                "edges_reset_to_unknown": cleared.get("edges_reset"),
                "managed_edge_count_after": 0,
                "fresh_health_state": "fresh",
                "cleared_health_state": "unavailable",
                "credential_logging": "not_detected",
            },
            indent=2,
            sort_keys=True,
        )
    )
    print("Periodic TomTom traffic updater write and cleanup accepted.")


def _read(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def _require(condition: object, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
