#!/usr/bin/env python3
"""Package a separately built Valhalla 3.9.0 graph for Android's offline fallback."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import tarfile
import zipfile
from pathlib import Path

ENGINE_VERSION = "3.9.0"
ATTRIBUTION = "© OpenStreetMap contributors — ODbL 1.0"


def export(archive: Path, output: Path, *, engine_version: str, label: str,
           source_date: str, bounds: list[float], probe: list[list[float]]) -> dict:
    if engine_version != ENGINE_VERSION:
        raise ValueError("Build a separate Valhalla 3.9.0 graph; do not relabel a 3.8.3 graph")
    if output.exists():
        raise FileExistsError(output)
    south, west, north, east = bounds
    if not (-90 <= south < north <= 90 and -180 <= west < east <= 180):
        raise ValueError("Invalid bounds")
    if len(probe) != 2 or any(len(p) != 2 or not (
        south <= p[0] <= north and west <= p[1] <= east
    ) for p in probe):
        raise ValueError("The two probe coordinates must be inside the coverage bounds")
    if not (1 <= len(label) <= 120 and 1 <= len(source_date) <= 40):
        raise ValueError("Invalid label or source date")
    size = archive.stat().st_size
    if not 0 < size <= 8 * 1024 ** 3:
        raise ValueError("Graph must be nonempty and at most 8 GiB")
    with tarfile.open(archive, "r:") as tiles:
        if not any(t.isfile() and t.name.endswith(".gph") and t.size > 0 for t in tiles):
            raise ValueError("Archive contains no graph tiles")
    with archive.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest = dict(schemaVersion=1, engineVersion=engine_version, label=label,
                    sourceDate=source_date,
                    sha256=digest, bytes=size, south=south, west=west, north=north, east=east,
                    probe=probe, attribution=ATTRIBUTION)
    temporary = output.with_name(output.name + ".part")
    stream = temporary.open("xb")
    try:
        # Exclusive creation prevents two exports from sharing a partially written package.
        with stream:
            with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED,
                                 allowZip64=True) as package:
                package.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False))
                package.write(archive, "tiles.tar")
            stream.flush()
            os.fsync(stream.fileno())
        # Publish without overwriting a package created concurrently by another exporter.
        os.link(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tiles", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--engine-version", required=True, choices=[ENGINE_VERSION])
    parser.add_argument("--label", required=True)
    parser.add_argument("--source-date", required=True)
    parser.add_argument("--bounds", nargs=4, required=True, type=float,
                        metavar=("SOUTH", "WEST", "NORTH", "EAST"))
    parser.add_argument("--probe", nargs=4, required=True, type=float,
                        metavar=("ORIGIN_LAT", "ORIGIN_LON", "DEST_LAT", "DEST_LON"))
    args = parser.parse_args()
    print(json.dumps(export(args.tiles, args.output, engine_version=args.engine_version,
                            label=args.label, source_date=args.source_date, bounds=args.bounds,
                            probe=[args.probe[:2], args.probe[2:]]), ensure_ascii=False))


if __name__ == "__main__":
    main()
