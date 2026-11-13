#!/usr/bin/env python3
"""Manage immutable graph generations inside one persistent Valhalla volume."""

from __future__ import annotations

import argparse
import fcntl
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tarfile
from contextlib import contextmanager
from pathlib import Path

ROOT = Path("/custom_files")
MANIFEST = "compass-datasets.json"
READY = "compass-ready.json"
LEASE = ".compass-reader.lock"


def generation_path(generation: str) -> Path:
    if generation == "legacy":
        return ROOT
    if not re.fullmatch(r"map-[A-Za-z0-9-]{1,80}", generation):
        raise ValueError("Invalid graph generation")
    path = ROOT / "datasets" / generation
    if path.is_symlink() or (ROOT / "datasets").is_symlink():
        raise ValueError("Graph directories must not be symlinks")
    return path


def empty_state() -> dict:
    return {"schema_version": 1, "active": None, "previous": None}


def read_state() -> dict:
    path = ROOT / MANIFEST
    if not path.exists():
        return empty_state()
    state = json.loads(path.read_text())
    if state.get("schema_version") != 1:
        raise ValueError("Unsupported graph manifest")
    for snapshot in (state, state.get("pending", {})):
        for key in ("active", "previous"):
            if snapshot.get(key) is not None:
                generation_path(snapshot[key])
    return state


def write_json(path: Path, data: dict) -> None:
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w") as stream:
        json.dump(data, stream, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


@contextmanager
def lock(name: str):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / name).open("a") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("Another dataset operation is running") from error
        yield


def configure(path: Path) -> None:
    # Reuse the existing nominal-routing configuration policy.
    source = Path(__file__).with_name("configure-valhalla-runtime.py")
    spec = importlib.util.spec_from_file_location("compass_runtime_config", source)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.enable_hard_exclusions(path)


def validate_graph(generation: str, *, full: bool = False) -> Path:
    folder = generation_path(generation)
    config_path = folder / "valhalla.json"
    archive = folder / "valhalla_tiles.tar"
    if config_path.is_symlink() or archive.is_symlink():
        raise ValueError("Graph config and archive must not be symlinks")
    config = json.loads(config_path.read_text())
    if config.get("mjolnir", {}).get("tile_extract") != str(archive):
        raise ValueError("Graph configuration points outside its generation")
    if archive.stat().st_size == 0:
        raise ValueError("Empty graph archive")
    if full:
        with tarfile.open(archive, "r:") as tiles:
            has_tiles = False
            for member in tiles:
                has_tiles |= member.isfile() and member.name.endswith(".gph") and member.size > 0
            if not has_tiles:
                raise ValueError("No routing tiles in graph archive")
    else:
        ready = json.loads((folder / READY).read_text())
        stat = archive.stat()
        if ready != {"archive_bytes": stat.st_size, "archive_mtime_ns": stat.st_mtime_ns}:
            raise ValueError("Graph archive changed since preparation")
    return config_path


def mark_ready(generation: str) -> None:
    config = validate_graph(generation, full=True)
    configure(config)
    for file in (config, config.parent / "valhalla_tiles.tar"):
        with file.open("rb") as stream:
            os.fsync(stream.fileno())
    (config.parent / LEASE).touch(exist_ok=True)
    stat = (config.parent / "valhalla_tiles.tar").stat()
    write_json(config.parent / READY,
               {"archive_bytes": stat.st_size, "archive_mtime_ns": stat.st_mtime_ns})


def recover(*, require_active: bool = False) -> dict:
    with lock(".dataset-state.lock"):
        state = read_state()
        if "pending" in state:
            # A normal redeploy after interrupted activation uses the last confirmed graph.
            state = state["pending"]
            write_json(ROOT / MANIFEST, state)
        if (state["active"] is None and not (ROOT / MANIFEST).exists()
                and (ROOT / "valhalla.json").exists()):
            # Adopt the operator's current volume in place; no copy or rebuild.
            mark_ready("legacy")
            state["active"] = "legacy"
            write_json(ROOT / MANIFEST, state)
        if state["active"] is not None:
            validate_graph(state["active"])
        elif require_active:
            raise RuntimeError("No prepared graph; run the scheduler map job first")
        return state


def build(generation: str) -> None:
    if generation == "legacy":
        raise ValueError("The adopted graph cannot be rebuilt in place")
    with lock(".dataset-build.lock"):
        folder = generation_path(generation)
        folder.parent.mkdir(exist_ok=True)
        # Refuse even a partial existing generation: every build starts isolated.
        folder.mkdir(exist_ok=False)
        env = dict(os.environ, path_extension=f"datasets/{generation}",
                   serve_tiles="False", force_rebuild="False", build_tar="True")
        subprocess.run(["/valhalla/scripts/docker-entrypoint.sh", "build_tiles"],
                       env=env, check=True)
        mark_ready(generation)


def import_graph(generation: str, source: Path = Path("/legacy")) -> None:
    """Copy a read-only legacy volume; never publish an unprobed graph."""
    if generation == "legacy" or source.samefile(ROOT):
        raise ValueError("Import requires a separate source and a new generation")
    with lock(".dataset-build.lock"), lock(".dataset-state.lock"):
        state = read_state()
        if state["active"] is not None or "pending" in state:
            raise RuntimeError("Destination store is already initialized")
        selected = "legacy"
        if (source / MANIFEST).exists():
            source_state = json.loads((source / MANIFEST).read_text())
            if source_state.get("schema_version") != 1:
                raise ValueError("Unsupported source manifest")
            source_state = source_state.get("pending", source_state)
            selected = source_state.get("active")
            if selected != "legacy" and (
                not isinstance(selected, str)
                or not re.fullmatch(r"map-[A-Za-z0-9-]{1,80}", selected)
            ):
                raise ValueError("No valid confirmed source graph")
        origin = source if selected == "legacy" else source / "datasets" / selected
        if origin.is_symlink() or (source / "datasets").is_symlink():
            raise ValueError("Source graph must not contain symlinks")
        config = json.loads((origin / "valhalla.json").read_text())
        old_prefix = "/custom_files" + (f"/datasets/{selected}" if selected != "legacy" else "")
        if config.get("mjolnir", {}).get("tile_extract") != old_prefix + "/valhalla_tiles.tar":
            raise ValueError("Source config points outside the selected graph")
        folder = generation_path(generation)
        folder.parent.mkdir(exist_ok=True)
        folder.mkdir(exist_ok=False)
        ignored = {MANIFEST, READY, LEASE, "datasets"}
        for item in origin.iterdir():
            if (item.name in ignored or item.name.startswith(".dataset-")
                    or item.name.endswith(".pbf")):
                continue
            if (item.is_symlink()
                    or (item.is_dir() and any(p.is_symlink() for p in item.rglob("*")))):
                raise ValueError("Source graph must not contain symlinks")
            if item.is_dir():
                shutil.copytree(item, folder / item.name)
            else:
                shutil.copy2(item, folder / item.name)

        def relocate(value):
            if isinstance(value, dict):
                return {key: relocate(child) for key, child in value.items()}
            if isinstance(value, list):
                return [relocate(child) for child in value]
            if isinstance(value, str) and value.startswith(old_prefix + "/"):
                return str(folder) + value[len(old_prefix):]
            return value

        write_json(folder / "valhalla.json", relocate(config))
        mark_ready(generation)


def activate(generation: str) -> dict:
    with lock(".dataset-state.lock"):
        state = read_state()
        if "pending" in state:
            raise RuntimeError("Unconfirmed activation; recover it before activating another graph")
        validate_graph(generation)
        if state["active"] == generation:
            raise ValueError("Graph is already active")
        state = {"schema_version": 1, "active": generation, "previous": state["active"],
                 "pending": state}
        write_json(ROOT / MANIFEST, state)
        return state


def finish(generation: str, *, abort: bool = False) -> dict:
    with lock(".dataset-state.lock"):
        state = read_state()
        if state["active"] != generation or "pending" not in state:
            raise RuntimeError("Activation no longer matches the requested generation")
        if abort:
            state = state["pending"]
        else:
            validate_graph(generation)
            state.pop("pending")
        write_json(ROOT / MANIFEST, state)
        return state


def discard(generation: str) -> None:
    with lock(".dataset-build.lock"), lock(".dataset-state.lock"):
        state = read_state()
        if generation == "legacy" or "pending" in state or generation in (
            state["active"], state["previous"],
        ):
            raise RuntimeError("Refusing to remove an active, previous or pending graph")
        path = generation_path(generation)
        if path.exists():
            remove_unused(path)


def remove_unused(path: Path) -> None:
    # Running routers retain a shared lock across exec, including candidate containers.
    # Unfinished builds have no reader lease and are protected by the build lock.
    if not (path / LEASE).exists():
        shutil.rmtree(path)
        return
    with (path / LEASE).open("rb") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        shutil.rmtree(path)


def prune() -> None:
    with lock(".dataset-build.lock"), lock(".dataset-state.lock"):
        state = read_state()
        if "pending" in state or state["active"] is None:
            raise RuntimeError("Cannot prune before activation has been confirmed")
        for path in (ROOT / "datasets").glob("map-*"):
            if path.name not in (state["active"], state["previous"]) and (path / READY).is_file():
                remove_unused(generation_path(path.name))
        # Never delete adopted root files or other Docker volumes automatically.


def serve(generation: str | None = None) -> None:
    generation = generation or read_state()["active"]
    if generation is None:
        raise RuntimeError("No active graph; run dataset preparation or the scheduler map job")
    with (generation_path(generation) / LEASE).open("rb") as lease:
        fcntl.flock(lease, fcntl.LOCK_SH | fcntl.LOCK_NB)
        config = validate_graph(generation)
        threads = int(os.environ.get("server_threads", "2"))  # noqa: SIM112 (upstream image setting)
        if threads < 1:
            raise ValueError("server_threads must be positive")
        os.set_inheritable(lease.fileno(), True)
        print(f"Serving graph generation {generation}; automatic building is disabled", flush=True)
        os.execvp("valhalla_service", ["valhalla_service", str(config), str(threads)])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "recover", "build", "import", "serve",
                                          "status", "activate", "confirm", "abort", "discard",
                                          "prune"])
    parser.add_argument("--generation")
    args = parser.parse_args()
    generation_actions = {"build", "import", "activate", "confirm", "abort", "discard"}
    if args.action in generation_actions and not args.generation:
        parser.error("--generation is required; use the scheduler map job for automatic builds")
    result = None
    if args.action == "prepare":
        result = recover(require_active=True)
    elif args.action == "recover":
        result = recover()
    elif args.action == "status":
        result = read_state()
    elif args.action == "build":
        build(args.generation)
    elif args.action == "import":
        import_graph(args.generation)
    elif args.action == "serve":
        serve(args.generation)
    elif args.action == "activate":
        result = activate(args.generation)
    elif args.action in {"confirm", "abort"}:
        result = finish(args.generation, abort=args.action == "abort")
    elif args.action == "discard":
        discard(args.generation)
    elif args.action == "prune":
        prune()
    if result is not None:
        print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
