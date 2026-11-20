#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--inspect-set", type=Path, required=True)
    parser.add_argument("--clear", type=Path, required=True)
    parser.add_argument("--cleared-state", type=Path, required=True)
    parser.add_argument("--inspect-reset", type=Path, required=True)
    args = parser.parse_args()
    validate(
        applied=_read(args.apply),
        state=_read(args.state),
        inspect_set=_read(args.inspect_set),
        cleared=_read(args.clear),
        cleared_state=_read(args.cleared_state),
        inspect_reset=_read(args.inspect_reset),
    )
    return 0


def validate(
    *,
    applied: object,
    state: object,
    inspect_set: object,
    cleared: object,
    cleared_state: object,
    inspect_reset: object,
) -> None:
    for name, value in (
        ("apply", applied),
        ("state", state),
        ("inspect-set", inspect_set),
        ("clear", cleared),
        ("cleared-state", cleared_state),
        ("inspect-reset", inspect_reset),
    ):
        _require(isinstance(value, dict), f"{name} result must be an object")
    assert isinstance(applied, dict)
    assert isinstance(state, dict)
    assert isinstance(inspect_set, dict)
    assert isinstance(cleared, dict)
    assert isinstance(cleared_state, dict)
    assert isinstance(inspect_reset, dict)

    identity = applied.get("configured_tileset_identity")
    _require(isinstance(identity, str) and identity, "apply tileset identity missing")
    _require(applied.get("provider") == "tomtom", "apply provider must be TomTom")
    _require(applied.get("segments_considered") == 1, "gate must use one probe")
    _require(applied.get("segments_accepted") == 1, "probe must be accepted")
    edges_set = applied.get("edges_set")
    _require(isinstance(edges_set, int) and edges_set > 0, "no edges were set")
    _require(applied.get("edges_reset") == 0, "apply unexpectedly reset edges")
    _require(applied.get("managed_edge_count") == edges_set, "managed count mismatch")
    _require(applied.get("state_persisted") is True, "applied state was not persisted")
    _require(
        applied.get("provider_overlay_write_enabled") is True,
        "writer gate did not explicitly enable overlay mutation",
    )
    _require(applied.get("rejected_segments") == [], "probe was partially rejected")

    _require(state.get("schema_version") == 1, "unexpected state schema")
    _require(state.get("tileset_identity") == identity, "state tileset mismatch")
    state_edges = state.get("edges")
    _require(isinstance(state_edges, list), "managed state edges missing")
    assert isinstance(state_edges, list)
    _require(len(state_edges) == edges_set, "persisted managed edge count mismatch")
    selected = state_edges[0]
    _require(isinstance(selected, dict), "managed edge must be an object")
    assert isinstance(selected, dict)

    _require(inspect_set.get("graph_id") == selected.get("graph_id"), "wrong edge inspected")
    _require(inspect_set.get("speed_valid") is True, "written speed is not valid")
    _require(
        inspect_set.get("closed") == selected.get("closed"),
        "written closure flag differs from managed state",
    )
    encoded_speed = inspect_set.get("overall_speed_kph")
    state_speed = selected.get("speed_kph")
    _require(
        isinstance(encoded_speed, int | float)
        and isinstance(state_speed, int | float)
        and abs(float(encoded_speed) - float(state_speed)) <= 1.0,
        "Valhalla encoded speed differs from the normalized provider speed",
    )

    _require(cleared.get("configured_tileset_identity") == identity, "clear tileset mismatch")
    _require(cleared.get("edges_reset") == edges_set, "not all managed edges were reset")
    _require(cleared.get("managed_edge_count") == 0, "clear retained managed edges")
    _require(cleared.get("state_persisted") is True, "cleared state was not persisted")
    _require(cleared_state.get("tileset_identity") == identity, "cleared-state tileset mismatch")
    _require(cleared_state.get("edges") == [], "cleared state is not empty")
    _require(inspect_reset.get("graph_id") == selected.get("graph_id"), "wrong reset inspected")
    _require(inspect_reset.get("speed_valid") is False, "reset edge still has live speed")
    _require(inspect_reset.get("closed") is False, "reset edge remained closed")

    print(
        json.dumps(
            {
                "provider": "tomtom",
                "tileset_identity": identity,
                "segments_accepted": 1,
                "edges_set": edges_set,
                "inspected_graph_id": selected.get("graph_id"),
                "provider_speed_kph": state_speed,
                "valhalla_encoded_speed_kph": encoded_speed,
                "edges_reset_to_unknown": cleared.get("edges_reset"),
                "managed_edge_count_after": 0,
            },
            indent=2,
            sort_keys=True,
        )
    )
    print("Controlled TomTom traffic write and reset accepted.")


def _read(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def _require(condition: object, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
