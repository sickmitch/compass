#!/usr/bin/env python3
"""Apply Compass-required settings to an existing Valhalla runtime config."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def enable_hard_exclusions(config_path: Path) -> bool:
    """Enable Valhalla hard exclusions and return whether the file changed."""
    if not config_path.is_file():
        raise FileNotFoundError(
            f"Valhalla config not found at {config_path}; build the routing tiles first"
        )
    config = json.loads(config_path.read_text(encoding="utf-8"))
    service_limits = config.setdefault("service_limits", {})
    if not isinstance(service_limits, dict):
        raise ValueError("Valhalla service_limits must be a JSON object")
    if service_limits.get("allow_hard_exclusions") is True:
        return False
    service_limits["allow_hard_exclusions"] = True
    temporary_path = config_path.with_suffix(f"{config_path.suffix}.tmp")
    temporary_path.write_text(
        json.dumps(config, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary_path.replace(config_path)
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path("/custom_files/valhalla.json"))
    args = parser.parse_args()
    changed = enable_hard_exclusions(args.config)
    message = (
        "Valhalla hard exclusions enabled"
        if changed
        else "Valhalla hard exclusions already enabled"
    )
    print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
