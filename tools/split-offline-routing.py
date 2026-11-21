#!/usr/bin/env python3
"""Export jointly routable regional packages from ONE Valhalla 3.9.0 build."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path

ATTRIBUTION = "© OpenStreetMap contributors — ODbL 1.0"
TILE_PATH = re.compile(r"[0-2]/(?:[0-9]{3}/){1,2}[0-9]{3}\.gph")


def inventory_digest(tiles):
    text = "".join(
        f"{t['path']}\t{t['bytes']}\t{t['sha256']}\n"
        for t in sorted(tiles, key=lambda t: t["path"])
    )
    return hashlib.sha256(text.encode()).hexdigest()


def tile_bounds(path):
    level = int(path[0])
    tile_id = int(path[2:-4].replace("/", ""))
    size = (4.0, 1.0, 0.25)[level]
    columns = int(360 / size)
    rows = int(180 / size)
    digits = str(tile_id).zfill(9 if level == 2 else 6)
    canonical = f"{level}/" + "/".join(digits[i : i + 3] for i in range(0, len(digits), 3)) + ".gph"
    if tile_id >= columns * rows or path != canonical:
        raise ValueError(f"Invalid tile id: {path}")
    south = -90 + (tile_id // columns) * size
    west = -180 + (tile_id % columns) * size
    return south, west, south + size, west + size


def intersects(path, bounds):
    # Shared upper hierarchy keeps the original cross-region links/shortcuts intact.
    if not path.startswith("2/"):
        return True
    s, w, n, e = tile_bounds(path)
    a, b, c, d = bounds
    # One local tile of overlap preserves snapping and turns near region boundaries.
    return s < c + 0.25 and n > a - 0.25 and w < d + 0.25 and e > b - 0.25


def export_regions(
    archive: Path, output_dir: Path, regions: list[dict], source_date: str
) -> list[dict]:
    if not regions or not 1 <= len(source_date) <= 40:
        raise ValueError("Regions and source date are required")
    ids = set()
    for region in regions:
        name = region["id"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", name) or name in ids:
            raise ValueError("Region ids must be distinct safe filenames")
        ids.add(name)
        s, w, n, e = region["bounds"]
        if not all(math.isfinite(v) for v in (s, w, n, e)) or not (
            -90 <= s < n <= 90 and -180 <= w < e <= 180
        ):
            raise ValueError("Invalid region bounds")
        if (
            not 1 <= len(region["label"]) <= 120
            or len(region["probe"]) != 2
            or any(len(p) != 2 or not (s <= p[0] <= n and w <= p[1] <= e) for p in region["probe"])
        ):
            raise ValueError("Invalid label or probe coordinates")
        if (output_dir / f"{name}.zip").exists():
            raise FileExistsError(output_dir / f"{name}.zip")
    output_dir.mkdir(parents=True, exist_ok=True)
    manifests = []
    with tarfile.open(archive, "r:") as source:
        members = {}
        inventory = []
        for member in source:
            if member.name == "index.bin" and member.isfile():
                continue  # Offset index belongs to the original tar, never to a subset.
            if not member.isfile() or not TILE_PATH.fullmatch(member.name) or member.size <= 0:
                raise ValueError(f"Unexpected graph entry: {member.name}")
            tile_bounds(member.name)
            if member.name in members:
                raise ValueError(f"Duplicate graph tile: {member.name}")
            members[member.name] = member
            with source.extractfile(member) as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            inventory.append(dict(path=member.name, bytes=member.size, sha256=digest))
        if not inventory:
            raise ValueError("No graph tiles")
        family = inventory_digest(inventory)
        for region in regions:
            tiles = sorted(
                (t for t in inventory if intersects(t["path"], region["bounds"])),
                key=lambda t: t["path"],
            )
            if not any(t["path"].startswith("2/") for t in tiles):
                raise ValueError(f"No local road tiles for {region['id']}")
            size = sum(t["bytes"] for t in tiles)
            if size > 8 * 1024**3:
                raise ValueError("Region exceeds the 8 GiB per-package limit")
            s, w, n, e = region["bounds"]
            manifest = dict(
                schemaVersion=2,
                engineVersion="3.9.0",
                label=region["label"],
                sourceDate=source_date,
                sha256=inventory_digest(tiles),
                bytes=size,
                south=s,
                west=w,
                north=n,
                east=e,
                probe=region["probe"],
                attribution=ATTRIBUTION,
                graphFamily=family,
                tiles=tiles,
            )
            header = json.dumps(manifest, ensure_ascii=False).encode()
            if len(header) > 4 * 1024**2:
                raise ValueError("Region inventory exceeds manifest limit")
            output = output_dir / f"{region['id']}.zip"
            temporary = output.with_suffix(".zip.part")
            stream = temporary.open("xb")
            try:
                with stream:
                    with zipfile.ZipFile(
                        stream, "w", compression=zipfile.ZIP_STORED, allowZip64=True
                    ) as package:
                        package.writestr("manifest.json", header)
                        for tile in tiles:
                            with (
                                source.extractfile(members[tile["path"]]) as src,
                                package.open(tile["path"], "w", force_zip64=True) as dst,
                            ):
                                shutil.copyfileobj(src, dst, 1024 * 1024)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.link(temporary, output)
            finally:
                temporary.unlink(missing_ok=True)
            manifests.append(
                dict(file=output.name, graphFamily=family, tile_count=len(tiles), bytes=size)
            )
    return manifests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--tiles", type=Path, help="Complete tar from a single 3.9.0 build")
    source.add_argument("--package", type=Path, help="Existing schema-1 Compass ZIP from one build")
    parser.add_argument("--engine-version", required=True, choices=["3.9.0"])
    parser.add_argument("--source-date", help="Required with --tiles; inherited with --package")
    parser.add_argument(
        "--regions", type=Path, required=True, help="JSON list of id/label/bounds/probe objects"
    )
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    regions = json.loads(args.regions.read_text())
    if args.package:
        # No path extraction: copy only the verified, explicitly named graph member.
        with (
            zipfile.ZipFile(args.package) as package,
            tempfile.TemporaryDirectory(prefix="compass-split-") as temp,
        ):
            header = json.loads(package.read("manifest.json"))
            if header["schemaVersion"] != 1 or header["engineVersion"] != "3.9.0":
                raise ValueError("Expected an original schema-1 Valhalla 3.9.0 package")
            archive = Path(temp) / "tiles.tar"
            with package.open("tiles.tar") as src, archive.open("wb") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)
            with archive.open("rb") as stream:
                if (
                    archive.stat().st_size != header["bytes"]
                    or hashlib.file_digest(stream, "sha256").hexdigest() != header["sha256"]
                ):
                    raise ValueError("Original graph checksum mismatch")
            result = export_regions(
                archive, args.output_dir, regions, args.source_date or header["sourceDate"]
            )
    else:
        result = export_regions(args.tiles, args.output_dir, regions, args.source_date or "")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
