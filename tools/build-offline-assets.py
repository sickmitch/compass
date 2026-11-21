#!/usr/bin/env python3
"""One-shot weekly Android graph build, export and recoverable five-file publication."""

from __future__ import annotations

import fcntl
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
import uuid
from datetime import UTC, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

NAMES = ("nord-ovest", "nord-est", "centro", "sud-isole", "italia")
OUTPUT = Path("/offline_assets")
WORK = Path("/custom_files")
TOOLS = Path(__file__).resolve().parent


def now() -> datetime:
    return datetime.now(ZoneInfo("Europe/Rome"))


def atomic_json(path: Path, data: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w") as stream:
        json.dump(data, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def read_set(output: Path, date: str) -> dict | None:
    if not re.fullmatch(r"[0-9]{8}", date):
        raise ValueError("Invalid creation date")
    datetime.strptime(date, "%d%m%Y")
    path = output / f"set-{date}.json"
    if not path.exists():
        return None
    record = json.loads(path.read_text())
    expected = {f"{name}_{date}.zip" for name in NAMES}
    if record.get("schema_version") != 1 or set(record.get("files", {})) != expected:
        raise ValueError("Invalid offline set manifest")
    for filename, details in record["files"].items():
        file = output / filename
        if file.is_symlink() or not file.is_file() or file.stat().st_size != details["bytes"]:
            raise ValueError(f"Published offline file missing or changed: {filename}")
    return record


def publish(staging: Path, output: Path, created: datetime, family: str) -> dict:
    date = created.astimezone(ZoneInfo("Europe/Rome")).strftime("%d%m%Y")
    record = read_set(output, date)
    if record is not None:
        atomic_json(output / "latest.json", record)
        return record
    record = {
        "schema_version": 1,
        "created_at": created.isoformat(),
        "graph_family": family,
        "files": {},
    }
    for name in NAMES:
        source = staging / f"{name}.zip"
        with source.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
            os.fsync(stream.fileno())
        record["files"][f"{name}_{date}.zip"] = {
            "bytes": source.stat().st_size,
            "sha256": digest,
        }
    linked = []
    committed = False
    try:
        for name in NAMES:
            target = output / f"{name}_{date}.zip"
            # Only this job's uncommitted names for this date can be leftovers of a crash.
            target.unlink(missing_ok=True)
            os.link(staging / f"{name}.zip", target)
            linked.append(target)
        # A date's set manifest is the commit point; latest is updated only after all five files.
        atomic_json(output / f"set-{date}.json", record)
        committed = True
        atomic_json(output / "latest.json", record)
        return record
    except BaseException:
        # If replace succeeded but directory fsync failed, do not invalidate a committed set.
        if not committed and not (output / f"set-{date}.json").exists():
            for file in linked:
                file.unlink(missing_ok=True)
        raise


def prune(output: Path, keep: int) -> None:
    records = []
    latest = json.loads((output / "latest.json").read_text())
    for marker in output.glob("set-????????.json"):
        try:
            record = read_set(output, marker.stem[4:])
            records.append((datetime.fromisoformat(record["created_at"]), marker, record))
        except (ValueError, KeyError, OSError):
            print(f"Retaining unrecognized offline set: {marker.name}", flush=True)
    records.sort(key=lambda item: item[0], reverse=True)
    for _, marker, record in records[keep:]:
        if record == latest:
            continue
        # Remove the commit marker before files; never touch unrelated files/directories.
        marker.unlink()
        for filename in record["files"]:
            (output / filename).unlink(missing_ok=True)


def build_graph(folder: Path) -> None:
    folder.mkdir()
    env = dict(
        os.environ,
        path_extension=folder.name,
        serve_tiles="False",
        build_tar="True",
        force_rebuild="False",
        use_tiles_ignore_pbf="False",
    )
    subprocess.run(
        ["/valhalla/scripts/docker-entrypoint.sh", "build_tiles"],
        env=env,
        check=True,
        timeout=12 * 60 * 60,
    )
    if not (folder / "valhalla_tiles.tar").is_file():
        raise RuntimeError("Offline graph build did not produce valhalla_tiles.tar")


def probe_graph(folder: Path, regions: list[dict]) -> None:
    config = folder / "valhalla.json"
    with (folder / "probe.log").open("w+") as log:
        process = subprocess.Popen(
            ["valhalla_service", str(config), "1"], stdout=log, stderr=subprocess.STDOUT
        )
        try:
            deadline = time.monotonic() + 180
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Offline validation router exited; inspect job logs")
                try:
                    with urllib.request.urlopen("http://127.0.0.1:8002/status", timeout=5):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Offline validation router not ready") from None
                    time.sleep(2)
            probes = [region["probe"] for region in regions]
            # Additional crossing of two exported regions, using the one common graph.
            probes.append([regions[0]["probe"][0], regions[1]["probe"][0]])
            for points in probes:
                payload = {
                    "locations": [{"lat": lat, "lon": lon} for lat, lon in points],
                    "costing": "auto",
                }
                request = urllib.request.Request(
                    "http://127.0.0.1:8002/route",
                    data=json.dumps(payload).encode(),
                    headers={"Content-Type": "application/json"},
                )
                with urllib.request.urlopen(request, timeout=120) as response:
                    result = json.load(response)
                if result.get("trip", {}).get("status") != 0:
                    raise RuntimeError("Offline graph route validation failed")
        except BaseException:
            log.flush()
            log.seek(0, os.SEEK_END)
            log.seek(max(0, log.tell() - 16000))
            print(log.read(), flush=True)
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def generate(folder: Path, staging: Path, regions: list[dict]) -> str:
    spec = importlib.util.spec_from_file_location(
        "offline_split", TOOLS / "split-offline-routing.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    results = module.export_regions(
        folder / "valhalla_tiles.tar", staging, regions, f"snapshot-{now():%Y-%m-%d}"
    )
    families = {entry["graphFamily"] for entry in results}
    if len(families) != 1 or {entry["file"] for entry in results} != {f"{n}.zip" for n in NAMES}:
        raise RuntimeError("Offline export must contain five compatible packages")
    return families.pop()


def main() -> None:
    keep = int(os.environ.get("COMPASS_OFFLINE_KEEP_SETS", "2"))
    if keep < 1:
        raise ValueError("COMPASS_OFFLINE_KEEP_SETS must be at least one")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    with (OUTPUT / ".compass-offline.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        existing = read_set(OUTPUT, now().strftime("%d%m%Y"))
        if existing is not None:
            atomic_json(OUTPUT / "latest.json", existing)
            prune(OUTPUT, keep)
            print("Offline set already published today; no rebuild", flush=True)
            return
        for root, pattern in ((OUTPUT, ".compass-offline-stage-*"), (WORK, "offline-build-*")):
            for abandoned in root.glob(pattern):
                if abandoned.is_dir() and not abandoned.is_symlink():
                    shutil.rmtree(abandoned)
        folder = WORK / f"offline-build-{uuid.uuid4().hex}"
        staging = Path(tempfile.mkdtemp(prefix=".compass-offline-stage-", dir=OUTPUT))
        try:
            regions = json.loads((TOOLS / "offline-italia-regions.json").read_text())
            print(f"Offline Italy build started at {datetime.now(UTC).isoformat()}", flush=True)
            build_graph(folder)
            probe_graph(folder, regions)
            family = generate(folder, staging, regions)
            record = publish(staging, OUTPUT, now(), family)
            prune(OUTPUT, keep)
            print(json.dumps(record), flush=True)
        finally:
            shutil.rmtree(folder, ignore_errors=True)
            shutil.rmtree(staging, ignore_errors=True)


if __name__ == "__main__":
    main()
