#!/usr/bin/env python3
"""Container-owned scheduled Docker jobs, using Europe/Rome wall-clock time."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import socket
import stat
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(os.environ.get("COMPASS_REPOSITORY", Path(__file__).resolve().parents[1]))
STATE = Path(os.environ.get("COMPASS_UPDATE_STATE", ROOT / ".scheduled-updates"))


def run(*args: str, env: dict[str, str] | None = None, capture: bool = True) -> str:
    result = subprocess.run(
        args,
        cwd=ROOT,
        env=env,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return (result.stdout or "").strip()


def compose(*args: str, capture: bool | None = None) -> str:
    return run(
        "docker",
        "compose",
        "--project-directory",
        os.environ.get("COMPASS_HOST_REPOSITORY", str(ROOT)),
        "--env-file",
        str(ROOT / ".env"),
        "-f",
        str(ROOT / "compose.yaml"),
        "--profile",
        "routing",
        "--profile",
        "jobs",
        "--profile",
        "routing-build",
        *args,
        capture=(args[0] == "ps") if capture is None else capture,
    )


def update_cng() -> None:
    compose("run", "--rm", "--no-deps", "etl", "mimit")
    # An enrichment outage must not prevent publishing the official prices.
    try:
        compose("run", "--rm", "--no-deps", "etl", "osm")
    except subprocess.CalledProcessError:
        print("OSM import failed; normalizing with the previously retained OSM data", flush=True)
    compose("run", "--rm", "--no-deps", "etl", "normalize")


def update_enforcement() -> None:
    import_error = None
    try:
        compose("run", "--rm", "--no-deps", "etl", "enforcement")
    except subprocess.CalledProcessError as error:
        import_error = error
        print("Enforcement import failed; associating retained inventory to the active graph",
              flush=True)
    compose("run", "--rm", "--no-deps", "etl", "enforcement-associate", "--all", "--limit", "500")
    if import_error is not None:
        # Association recovery is not a successful source refresh.
        raise import_error


def dataset(action: str, generation: str | None = None) -> dict:
    args = ("--generation", generation) if generation is not None else ()
    output = compose("run", "--rm", "--no-deps", "-T", "valhalla-config", action, *args,
                     capture=True)
    return json.loads(output) if output.strip() else {}


def wait_healthy(container: str, timeout: int = 600) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = json.loads(run("docker", "inspect", "--format", "{{json .State}}", container))
        if state.get("Health", {}).get("Status") == "healthy":
            return
        if not state.get("Running"):
            raise RuntimeError("Candidate routing service exited")
        time.sleep(5)
    raise TimeoutError("Routing health check timed out")


def probe_route(container: str) -> None:
    probe = os.environ.get(
        "COMPASS_MAP_PROBE_LOCATIONS",
        '[{"lat":45.4642,"lon":9.1900},{"lat":45.4857,"lon":9.2045}]',
    )
    payload = json.dumps({"locations": json.loads(probe), "costing": "auto"})
    response = json.loads(run("docker", "exec", container, "curl", "--fail", "--silent",
                              "--show-error", "--max-time", "60",
                              "-H", "Content-Type: application/json", "--data", payload,
                              "http://127.0.0.1:8002/route"))
    if response.get("trip", {}).get("status") != 0:
        raise RuntimeError("Graph failed routing validation")


def restart_router() -> str:
    compose("up", "-d", "--no-deps", "--force-recreate", "valhalla")
    current = compose("ps", "-q", "valhalla")
    wait_healthy(current)
    probe_route(current)
    return current


def activate_graph(generation: str, old_state: dict, *, restart: bool = True) -> None:
    candidate = f"compass-map-check-{uuid.uuid4().hex[:12]}"
    activation_attempted = False
    confirmed = False
    try:
        compose("run", "-d", "--name", candidate, "--no-deps", "valhalla",
                "serve", "--generation", generation)
        wait_healthy(candidate)
        probe_route(candidate)
        # Set this before the command: the manifest may be committed even if CLI output fails.
        activation_attempted = True
        dataset("activate", generation)
        if restart:
            restart_router()
        dataset("confirm", generation)
        confirmed = True
        print(f"Map activated: {generation}; previous={old_state['active']}", flush=True)
    except BaseException:
        if activation_attempted:
            state = dataset("status")
            # If confirm succeeded but its response was lost, never undo a confirmed activation.
            confirmed = state["active"] == generation and "pending" not in state
            if state["active"] == generation and "pending" in state:
                dataset("abort", generation)
                if not restart:
                    pass  # Import preparation must leave the existing deployment untouched.
                elif old_state["active"] is not None:
                    restart_router()
                else:
                    compose("stop", "valhalla")
        raise
    finally:
        run("docker", "rm", "-f", candidate)
    if confirmed:
        dataset("prune")


def update_map() -> None:
    # recover also adopts an existing flat volume, without changing .env or copying its files.
    old_state = dataset("recover")
    generation = f"map-{datetime.now(UTC):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:12]}"
    try:
        compose("run", "--rm", "--no-deps", "valhalla-tiles", "build", "--generation", generation)
        activate_graph(generation, old_state)
    except BaseException:
        state = dataset("status")
        if "pending" not in state and generation not in (state["active"], state["previous"]):
            dataset("discard", generation)
        raise


def import_map(source_volume: str | None = None) -> None:
    state = dataset("recover")
    if state["active"] is not None:
        print("Dataset store already initialized; nothing copied or replaced", flush=True)
        return
    if source_volume is None:
        current = compose("ps", "-a", "-q", "valhalla")
        if not current:
            raise RuntimeError("No existing router; specify map-import --source-volume NAME")
        mounts = json.loads(run("docker", "inspect", "--format", "{{json .Mounts}}", current))
        sources = [m["Name"] for m in mounts
                   if m.get("Type") == "volume" and m.get("Destination") == "/custom_files"]
        if len(sources) != 1:
            raise RuntimeError("Cannot identify source volume; specify --source-volume NAME")
        source_volume = sources[0]
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]+", source_volume):
        raise ValueError("Invalid Docker volume name")
    if source_volume == "compass_valhalla_datasets":
        raise ValueError("Source is the new store; specify the old volume with --source-volume")
    # Inspect first: Docker must not silently create an empty volume for a misspelled source.
    run("docker", "volume", "inspect", source_volume)
    generation = f"map-import-{uuid.uuid4().hex[:12]}"
    print(f"Copying {source_volume} read-only into compass_valhalla_datasets", flush=True)
    try:
        compose("run", "--rm", "--no-deps", "-T", "--volume", f"{source_volume}:/legacy:ro",
                "valhalla-config", "import", "--generation", generation)
        # Probe the copy and publish it for the next deployment. Do not recreate the old router.
        activate_graph(generation, state, restart=False)
    except BaseException:
        current_state = dataset("status")
        if "pending" not in current_state and generation not in (
            current_state["active"], current_state["previous"],
        ):
            dataset("discard", generation)
        raise
    print("Import verified; recreate valhalla to use the permanent store. Old volume retained.",
          flush=True)


def rollback_map() -> None:
    state = dataset("recover")
    if state["previous"] is None:
        raise RuntimeError("No previous graph available")
    activate_graph(state["previous"], state)
    compose("run", "--rm", "--no-deps", "etl", "enforcement-associate", "--all", "--limit", "500")


def execute(job: str, source_volume: str | None = None) -> None:
    STATE.mkdir(mode=0o700, exist_ok=True)
    # Map activation and association must not race, including manual scheduler runs.
    routing_jobs = {"map", "map-import", "map-rollback", "enforcement"}
    lock_name = "routing-maintenance" if job in routing_jobs else job
    with (STATE / f"{lock_name}.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Update already running; skipped", flush=True)
            return
        jobs = ("map", "enforcement") if job == "map" else (job,)
        actions = {"map": update_map, "map-rollback": rollback_map, "cng": update_cng,
                   "enforcement": update_enforcement,
                   "map-import": lambda: import_map(source_volume)}
        errors = []
        for current in jobs:
            print(f"{current} update started at {datetime.now(UTC).isoformat()}", flush=True)
            try:
                actions[current]()
                (STATE / f"{current}.last-success").write_text(
                    datetime.now(UTC).isoformat() + "\n"
                )
            except Exception as error:
                print(f"{current} update failed: {type(error).__name__}; see job logs", flush=True)
                errors.append(error)
        if errors:
            raise errors[0]


def due_jobs(now: datetime) -> list[tuple[str, str]]:
    local = now.astimezone(ZoneInfo("Europe/Rome"))
    if local.minute != 0:
        return []
    slot = local.strftime("%Y-%m-%dT%H")
    if local.hour == 3:
        return [("map", slot)]
    if 7 <= local.hour <= 20:
        return [("cng", slot)]
    return []


def check_docker_connection() -> None:
    path = Path("/var/run/docker.sock")
    try:
        mode = path.stat().st_mode
    except FileNotFoundError as error:
        raise RuntimeError(
            "Docker socket missing; check COMPASS_DOCKER_SOCKET bind source"
        ) from error
    if not stat.S_ISSOCK(mode):
        raise RuntimeError("/var/run/docker.sock is not a Unix socket; correct the bind source")
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(5)
            client.connect(str(path))
    except PermissionError as error:
        raise RuntimeError(
            "Docker socket access denied; check daemon/user-namespace permissions"
        ) from error
    except OSError as error:
        raise RuntimeError(
            "Docker socket connection failed; check daemon and socket path, then recreate "
            "scheduled-updates to refresh its bind mount"
        ) from error
    try:
        run("docker", "version", "--format", "{{.Server.Version}}")
    except subprocess.CalledProcessError as error:
        raise RuntimeError("Docker API check failed; inspect the Docker CLI error above") from error


def validate_dataset_compose() -> None:
    """Reject mixed scheduler/Compose versions before touching datasets or containers."""
    config = json.loads(compose("config", "--format", "json", capture=True))
    problems = []
    volume_name = config.get("volumes", {}).get("valhalla_data", {}).get("name")
    if volume_name != "compass_valhalla_datasets":
        problems.append("volumes.valhalla_data.name must be compass_valhalla_datasets")
    manager = "/usr/local/bin/valhalla-datasets.py"
    for name, command in (("valhalla-config", "prepare"), ("valhalla-tiles", "build"),
                          ("valhalla", "serve")):
        service = config.get("services", {}).get(name, {})
        if service.get("entrypoint") != ["python3", manager]:
            problems.append(f"{name}.entrypoint must use {manager}")
        if service.get("command") != [command]:
            problems.append(f"{name}.command must be {command}")
        mounts = {mount.get("target"): mount for mount in service.get("volumes", [])}
        for target in (manager, "/usr/local/bin/configure-valhalla-runtime.py"):
            if mounts.get(target, {}).get("type") != "bind":
                problems.append(f"{name} must mount {target}")
        data = mounts.get("/custom_files", {})
        if data.get("type") != "volume" or data.get("source") != "valhalla_data":
            problems.append(f"{name} must mount valhalla_data at /custom_files")
        if name == "valhalla" and not data.get("read_only"):
            problems.append("valhalla must mount /custom_files read-only")
    for filename in ("valhalla-datasets.py", "configure-valhalla-runtime.py"):
        if not (ROOT / "tools" / filename).is_file():
            problems.append(f"tools/{filename} is missing from the mounted repository")
    if problems:
        raise RuntimeError(
            "Scheduler/Compose dataset configuration mismatch: " + "; ".join(problems)
            + ". Synchronize the dataset service settings and tools in the mounted repository, "
            "preserving custom networks. No dataset operation has started."
        )


def initialize() -> None:
    check_docker_connection()
    os.environ["COMPASS_HOST_REPOSITORY"] = discover_host_repository()
    validate_dataset_compose()


def discover_host_repository() -> str:
    # Sibling containers resolve bind mounts on the daemon host, not /workspace.
    mounts = json.loads(
        run("docker", "inspect", "--format", "{{json .Mounts}}", os.environ["HOSTNAME"])
    )
    return next(
        m["Source"] for m in mounts if m["Destination"] == str(ROOT) and m["Type"] == "bind"
    )


def serve() -> None:
    STATE.mkdir(mode=0o700, exist_ok=True)
    (STATE / "heartbeat").unlink(missing_ok=True)
    while True:
        try:
            initialize()
            break
        except (
            RuntimeError,
            subprocess.CalledProcessError,
            OSError,
            KeyError,
            StopIteration,
        ) as error:
            print(f"Scheduler unavailable: {error}; retry in 30 seconds", flush=True)
            time.sleep(30)
    print("Scheduler ready: Europe/Rome; map 03:00 then enforcement; CNG hourly 07:00–20:00",
          flush=True)
    running = {}
    with ThreadPoolExecutor(max_workers=2) as executor:
        while True:
            (STATE / "heartbeat").write_text(str(time.time()))
            for job, future in list(running.items()):
                if future.done():
                    try:
                        future.result()
                    except Exception as error:
                        print(
                            f"{job} update failed: {type(error).__name__}; see job logs", flush=True
                        )
                    del running[job]
            for job, slot in due_jobs(datetime.now(UTC)):
                marker = STATE / f"{job}.last-attempt-slot"
                if job in running or (marker.exists() and marker.read_text() == slot):
                    continue
                marker.write_text(slot)
                running[job] = executor.submit(execute, job)
            time.sleep(15)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("job", choices=["map", "map-import", "map-status", "map-rollback",
                                        "cng", "enforcement", "serve", "health"])
    parser.add_argument("--source-volume",
                        help="Existing Docker volume to copy once (map-import only)")
    args = parser.parse_args()
    if args.source_volume and args.job != "map-import":
        parser.error("--source-volume is only valid for map-import")
    if args.job == "health":
        try:
            age = time.time() - float((STATE / "heartbeat").read_text())
            sys.exit(0 if 0 <= age < 90 else 1)
        except (OSError, ValueError):
            sys.exit(1)
    elif args.job == "serve":
        serve()
    else:
        if os.environ.get("COMPASS_REPOSITORY"):
            initialize()
        if args.job == "map-status":
            print(json.dumps(dataset("status"), sort_keys=True))
        else:
            execute(args.job, args.source_volume)


if __name__ == "__main__":
    main()
