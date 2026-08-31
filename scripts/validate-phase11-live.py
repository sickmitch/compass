#!/usr/bin/env python3
"""Prepare and validate Phase 11 live artifacts.

Phase 11 is an Android-client increment: the app can edit the route endpoints
that drive preview, manual CNG candidates and predictive CNG planning. The live
backend already accepts arbitrary coordinates, so this validator keeps the API
preflight narrow and deterministic.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

ROME = {"latitude": 41.9028, "longitude": 12.4964}
FLORENCE = {"latitude": 43.7696, "longitude": 11.2558}
MIN_EXPECTED_DISTANCE_METERS = 150_000.0
MAX_EXPECTED_DISTANCE_METERS = 400_000.0


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _read(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    _require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def _write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def _number(value: Any, field: str, *, positive: bool = False) -> float:
    _require(value is not None, f"{field} is missing")
    _require(isinstance(value, int | float), f"{field} must be numeric")
    result = float(value)
    _require(math.isfinite(result), f"{field} must be finite")
    _require(result > 0 if positive else result >= 0, f"{field} is outside its range")
    return result


def prepare_route(output: Path) -> None:
    _write(
        output,
        {
            "origin": ROME,
            "destination": FLORENCE,
        },
    )
    print(f"Custom Rome-to-Florence route request written to {output}")


def validate_route(response: Path) -> None:
    value = _read(response)
    _require(value.get("provider") == "valhalla", "route provider must be valhalla")
    distance = _number(value.get("distance_meters"), "distance_meters", positive=True)
    duration = _number(value.get("duration_seconds"), "duration_seconds", positive=True)
    _require(
        MIN_EXPECTED_DISTANCE_METERS <= distance <= MAX_EXPECTED_DISTANCE_METERS,
        (
            "custom route distance is outside the expected Rome-to-Florence range; "
            "confirm the live backend received the generated request artifact"
        ),
    )
    geometry = value.get("geometry")
    _require(isinstance(geometry, dict), "geometry must be an object")
    _require(geometry.get("format") == "polyline6", "route geometry must be polyline6")
    _require(bool(geometry.get("encoded_polyline")), "route polyline must not be empty")
    maneuvers = value.get("maneuvers")
    _require(isinstance(maneuvers, list) and maneuvers, "maneuvers must be non-empty")

    print(
        json.dumps(
            {
                "custom_route": "Rome -> Florence",
                "provider": value["provider"],
                "distance_meters": distance,
                "duration_seconds": duration,
                "maneuver_count": len(maneuvers),
            },
            indent=2,
        )
    )
    print("Phase 11 live API preflight validated successfully.")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare_parser = subparsers.add_parser("prepare-route")
    prepare_parser.add_argument("--output", type=Path, required=True)

    validate_parser = subparsers.add_parser("validate-route")
    validate_parser.add_argument("--response", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "prepare-route":
        prepare_route(args.output)
    elif args.command == "validate-route":
        validate_route(args.response)


if __name__ == "__main__":
    main()
