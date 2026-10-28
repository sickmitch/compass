#!/usr/bin/env python3
"""Container-owned scheduled Docker jobs, using Europe/Rome wall-clock time."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import socket
import stat
import subprocess
import sys
import time
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


def compose(*args: str, env: dict[str, str] | None = None) -> str:
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
        env=env,
        capture=args[0] == "ps",
    )


def update_cng() -> None:
    compose("run", "--rm", "--no-deps", "etl", "mimit")
    # An enrichment outage must not prevent publishing the official prices.
    try:
        compose("run", "--rm", "--no-deps", "etl", "osm")
    except subprocess.CalledProcessError:
        print("OSM import failed; normalizing with the previously retained OSM data", flush=True)
    compose("run", "--rm", "--no-deps", "etl", "normalize")


def select_volume(path: Path, volume: str) -> None:
    """Change only the graph-volume setting; preserve credentials and other settings."""
    original = path.read_text()
    metadata = path.stat()
    lines = [
        line
        for line in original.splitlines()
        if not line.strip().startswith("VALHALLA_VOLUME_NAME=")
    ]
    lines.append(f"VALHALLA_VOLUME_NAME={volume}")
    temporary = path.with_name(path.name + ".update-tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write("\n".join(lines) + "\n")
    os.chmod(temporary, metadata.st_mode & 0o777)
    os.chown(temporary, metadata.st_uid, metadata.st_gid)
    temporary.replace(path)


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


def update_map() -> None:
    env_path = ROOT / ".env"
    if not env_path.is_file():
        raise RuntimeError("Existing deployment .env required")
    current = compose("ps", "-q", "valhalla")
    if not current:
        raise RuntimeError("Start the existing Valhalla service before enabling map updates")
    mounts = json.loads(run("docker", "inspect", "--format", "{{json .Mounts}}", current))
    old_volume = next(
        m["Name"] for m in mounts if m["Destination"] == "/custom_files" and m["Type"] == "volume"
    )
    stamp = datetime.now(UTC).strftime("%Y%m%d%H%M%S")
    # A fresh volume forces a fresh PBF download and never changes the serving graph.
    new_volume = f"{old_volume.split('-scheduled-')[0]}-scheduled-{stamp}"
    candidate = f"compass-map-check-{stamp}"
    env = dict(os.environ, VALHALLA_VOLUME_NAME=new_volume, VALHALLA_FORCE_REBUILD="False")
    switched = False
    try:
        compose("run", "--rm", "--no-deps", "valhalla-tiles", env=env)
        compose("run", "--rm", "--no-deps", "valhalla-config", env=env)
        compose("run", "-d", "--name", candidate, "--no-deps", "valhalla", env=env)
        wait_healthy(candidate)
        # Check the actual routing endpoint as well as process readiness. Coordinates
        # are configurable to support regional extracts that do not contain Milan.
        probe = os.environ.get(
            "COMPASS_MAP_PROBE_LOCATIONS",
            '[{"lat":45.4642,"lon":9.1900},{"lat":45.4857,"lon":9.2045}]',
        )
        payload = json.dumps({"locations": json.loads(probe), "costing": "auto"})
        response = json.loads(
            run(
                "docker",
                "exec",
                candidate,
                "curl",
                "--fail",
                "--silent",
                "--show-error",
                "-H",
                "Content-Type: application/json",
                "--data",
                payload,
                "http://127.0.0.1:8002/route",
            )
        )
        if response.get("trip", {}).get("status") != 0:
            raise RuntimeError("Candidate graph failed routing validation")
        select_volume(env_path, new_volume)
        switched = True
        compose("up", "-d", "--no-deps", "--force-recreate", "valhalla", env=env)
        wait_healthy(compose("ps", "-q", "valhalla", env=env))
        retention = STATE / "previous-volume"
        previous = retention.read_text().strip() if retention.exists() else ""
        retention.write_text(old_volume + "\n")
        managed_prefix = new_volume.split("-scheduled-")[0] + "-scheduled-"
        if previous.startswith(managed_prefix) and previous not in (old_volume, new_volume):
            subprocess.run(["docker", "volume", "rm", previous], check=False)
        print(f"Map updated; previous volume retained for rollback: {old_volume}", flush=True)
    except BaseException:
        if switched:
            select_volume(env_path, old_volume)
            rollback = dict(
                os.environ, VALHALLA_VOLUME_NAME=old_volume, VALHALLA_FORCE_REBUILD="False"
            )
            compose("up", "-d", "--no-deps", "--force-recreate", "valhalla", env=rollback)
            wait_healthy(compose("ps", "-q", "valhalla", env=rollback))
            switched = False
        raise
    finally:
        subprocess.run(
            ["docker", "rm", "-f", candidate],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        # Remove only a failed candidate volume; retain the previous working graph.
        if not switched:
            subprocess.run(["docker", "volume", "rm", new_volume], check=False)


def execute(job: str) -> None:
    STATE.mkdir(mode=0o700, exist_ok=True)
    with (STATE / f"{job}.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Update already running; skipped", flush=True)
            return
        print(f"{job} update started at {datetime.now(UTC).isoformat()}", flush=True)
        (update_map if job == "map" else update_cng)()
        (STATE / f"{job}.last-success").write_text(datetime.now(UTC).isoformat() + "\n")


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


def initialize() -> None:
    check_docker_connection()
    os.environ["COMPASS_HOST_REPOSITORY"] = discover_host_repository()


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
    print("Scheduler ready: Europe/Rome; map 03:00; CNG hourly 07:00–20:00", flush=True)
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
    parser.add_argument("job", choices=["map", "cng", "serve", "health"])
    args = parser.parse_args()
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
        execute(args.job)


if __name__ == "__main__":
    main()
