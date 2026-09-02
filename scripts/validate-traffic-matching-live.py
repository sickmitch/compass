#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--response", type=Path, required=True)
    args = parser.parse_args()
    payload = json.loads(args.response.read_text(encoding="utf-8"))
    validate(payload)
    return 0


def validate(payload: object) -> None:
    _require(isinstance(payload, dict), "matching output must be an object")
    assert isinstance(payload, dict)
    identity = payload.get("configured_tileset_identity")
    _require(isinstance(identity, str) and identity, "tileset identity must be configured")
    minimum_confidence = payload.get("minimum_match_confidence")
    _require(
        isinstance(minimum_confidence, int | float),
        "minimum match confidence must be numeric",
    )
    _require(
        payload.get("provider_overlay_write_enabled") is False,
        "provider overlay writes must remain disabled for this gate",
    )
    results = payload.get("results")
    _require(isinstance(results, list) and results, "at least one segment must be evaluated")

    accepted = []
    direction_verified = 0
    for result in results:
        _require(isinstance(result, dict), "matching result must be an object")
        match = result.get("match")
        _require(isinstance(match, dict), "matching result must contain match details")
        if match.get("direction_match") is True:
            direction_verified += 1
        graph_ids = match.get("directed_edge_ids")
        confidence = match.get("confidence")
        if (
            result.get("quality_accepted") is True
            and isinstance(graph_ids, list)
            and graph_ids
            and isinstance(confidence, int | float)
            and confidence >= minimum_confidence
            and match.get("match_method") == "geometry_trace"
            and match.get("valhalla_tileset_version") == identity
            and match.get("mapping_version") == "valhalla-openlr-geometry-v1"
            and match.get("direction_match") is True
            and result.get("write_eligible") is True
        ):
            accepted.append(result)

    _require(
        accepted,
        "no fresh TomTom segment produced a confident Valhalla geometry match",
    )
    _require(
        len(accepted) == len(results),
        "not every TomTom segment passed OpenLR direction and write-eligibility checks",
    )
    summary = {
        "provider": payload.get("provider"),
        "configured_tileset_identity": identity,
        "segments_considered": len(results),
        "confident_geometry_matches": len(accepted),
        "direction_verified": direction_verified,
        "write_eligible": sum(result.get("write_eligible") is True for result in results),
        "provider_overlay_write_enabled": False,
        "sample": accepted[0],
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    print("TomTom geometry-to-Valhalla matching live diagnostic accepted.")


def _require(condition: object, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
