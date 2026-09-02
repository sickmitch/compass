#!/usr/bin/env python3
"""Atomically rebuild only Valhalla's traffic extract from the active graph tiles."""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    args = parser.parse_args()

    source = json.loads(args.config.read_text(encoding="utf-8"))
    mjolnir = source.get("mjolnir")
    if not isinstance(mjolnir, dict):
        raise ValueError("Valhalla config has no mjolnir object")
    traffic_extract = _configured_path(mjolnir, "traffic_extract")
    tile_extract = _configured_path(mjolnir, "tile_extract")
    temporary_traffic = traffic_extract.with_name(
        f".{traffic_extract.name}.compass-rebuild"
    )
    temporary_tiles = tile_extract.with_name(
        f".{tile_extract.name}.compass-traffic-rebuild"
    )
    temporary_config: Path | None = None

    try:
        rebuild_config = build_rebuild_config(
            source,
            traffic_extract=temporary_traffic,
            tile_extract=temporary_tiles,
        )
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            prefix="compass-traffic-rebuild-",
            suffix=".json",
            delete=False,
        ) as handle:
            temporary_config = Path(handle.name)
            json.dump(rebuild_config, handle)
            handle.write("\n")

        subprocess.run(
            (
                "valhalla_build_extract",
                "--config",
                str(temporary_config),
                "--with-traffic",
                "--overwrite",
            ),
            check=True,
        )
        if not temporary_traffic.is_file() or temporary_traffic.stat().st_size <= 0:
            raise RuntimeError("Valhalla did not create a non-empty traffic extract")
        temporary_traffic.replace(traffic_extract)
        print(
            json.dumps(
                {
                    "traffic_extract": str(traffic_extract),
                    "traffic_extract_size_bytes": traffic_extract.stat().st_size,
                    "routing_extract_preserved": str(tile_extract),
                    "native_builder": "valhalla_build_extract --with-traffic --overwrite",
                },
                sort_keys=True,
            )
        )
        return 0
    finally:
        for path in (temporary_config, temporary_traffic, temporary_tiles):
            if path is not None:
                path.unlink(missing_ok=True)


def build_rebuild_config(
    source: dict[str, object],
    *,
    traffic_extract: Path,
    tile_extract: Path,
) -> dict[str, object]:
    result = json.loads(json.dumps(source))
    mjolnir = result.get("mjolnir")
    if not isinstance(mjolnir, dict):
        raise ValueError("Valhalla config has no mjolnir object")
    mjolnir["traffic_extract"] = str(traffic_extract)
    mjolnir["tile_extract"] = str(tile_extract)
    return result


def _configured_path(mjolnir: dict[str, object], field: str) -> Path:
    value = mjolnir.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"Valhalla config mjolnir.{field} is missing")
    path = Path(value)
    if not path.is_absolute():
        raise ValueError(f"Valhalla config mjolnir.{field} must be absolute")
    return path


if __name__ == "__main__":
    raise SystemExit(main())
