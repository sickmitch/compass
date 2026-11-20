#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from datetime import UTC, datetime
from pathlib import Path

GRAPH_ID = re.compile(r"^[0-9]+/[0-9]+/[0-9]+$")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--response", type=Path, required=True)
    args = parser.parse_args()
    validate(json.loads(args.response.read_text(encoding="utf-8")))
    return 0


def validate(payload: object) -> None:
    _require(isinstance(payload, dict), "traffic plan must be an object")
    assert isinstance(payload, dict)
    _require(payload.get("provider") == "tomtom", "traffic plan provider must be TomTom")
    identity = payload.get("configured_tileset_identity")
    _require(isinstance(identity, str) and identity, "tileset identity must be present")
    considered = payload.get("segments_considered")
    accepted = payload.get("segments_accepted")
    _require(isinstance(considered, int) and considered > 0, "segments must be considered")
    _require(accepted == considered, "every live probe must be accepted")
    _require(
        payload.get("provider_overlay_write_enabled") is False,
        "provider overlay writes must remain disabled",
    )
    _require(payload.get("state_persisted") is False, "dry-run must not persist state")
    _require(payload.get("rejected_segments") == [], "no live probe may be rejected")
    _require(payload.get("reset_graph_ids") == [], "empty dry-run state must need no reset")
    _require(
        payload.get("mapping_versions") == ["valhalla-openlr-geometry-v1"],
        "unexpected traffic mapping version",
    )
    updates = payload.get("set_updates")
    _require(isinstance(updates, list) and updates, "dry-run must plan edge updates")
    assert isinstance(updates, list)
    _require(
        payload.get("planned_edge_update_count") == len(updates),
        "planned update count differs from update records",
    )
    _require(
        payload.get("active_edge_count_after") == len(updates),
        "active state count differs from a fresh dry-run plan",
    )
    graph_ids: set[str] = set()
    speeds: list[float] = []
    closed_count = 0
    now = datetime.now(UTC)
    for update in updates:
        _require(isinstance(update, dict), "edge update must be an object")
        assert isinstance(update, dict)
        graph_id = update.get("graph_id")
        _require(
            isinstance(graph_id, str) and GRAPH_ID.fullmatch(graph_id),
            "edge update contains an invalid GraphId",
        )
        assert isinstance(graph_id, str)
        _require(graph_id not in graph_ids, "edge plan contains duplicate GraphIds")
        graph_ids.add(graph_id)
        speed = update.get("speed_kph")
        _require(
            isinstance(speed, int | float) and not isinstance(speed, bool),
            "edge speed must be numeric",
        )
        speed_value = float(speed)
        _require(0 <= speed_value <= 180, "edge speed is outside configured safety bounds")
        speeds.append(speed_value)
        closed = update.get("closed")
        _require(isinstance(closed, bool), "edge closure flag must be boolean")
        if speed_value == 0:
            _require(closed is True, "zero speed is reserved for explicit closures")
        if closed:
            closed_count += 1
        expires_at = update.get("expires_at")
        _require(isinstance(expires_at, str), "edge expiry must be serialized")
        assert isinstance(expires_at, str)
        expiry = datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
        _require(expiry.tzinfo is not None and expiry > now, "edge update is already expired")

    summary = {
        "provider": "tomtom",
        "configured_tileset_identity": identity,
        "segments_considered": considered,
        "segments_accepted": accepted,
        "planned_edge_update_count": len(updates),
        "planned_edge_reset_count": payload.get("planned_edge_reset_count"),
        "minimum_speed_kph": min(speeds),
        "maximum_speed_kph": max(speeds),
        "explicitly_closed_edge_count": closed_count,
        "mapping_versions": payload.get("mapping_versions"),
        "provider_overlay_write_enabled": False,
        "state_persisted": False,
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    print("TomTom edge update dry-run plan accepted.")


def _require(condition: object, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
