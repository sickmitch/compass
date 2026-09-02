#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

TMP_DIR = Path("/tmp")
TRAFFIC_TAR = "/custom_files/traffic.tar"
BACKUP_TAR = f"/custom_files/traffic.tar.compass-synthetic-backup-{os.getpid()}"
GRAPH_ID_TILE_MASK = 0x1FFFFF8
GRAPH_ID_EDGE_MASK = 0x3FFFFE000000


@dataclass(frozen=True)
class RouteResult:
    distance_meters: float
    duration_seconds: float
    shape: str


def main() -> int:
    graph_ids: list[str] = []
    backup_created = False
    summary_path = TMP_DIR / "compass-traffic-synthetic-summary.json"
    try:
        origin = _coordinate_from_env("TRAFFIC_SYNTHETIC_ORIGIN", "45.4642,9.1900")
        destination = _coordinate_from_env(
            "TRAFFIC_SYNTHETIC_DESTINATION", "44.4949,11.3426"
        )
        edge_count = int(os.environ.get("TRAFFIC_SYNTHETIC_EDGE_COUNT", "12"))
        speed_kph = float(os.environ.get("TRAFFIC_SYNTHETIC_SPEED_KPH", "5"))
        min_delta = float(os.environ.get("TRAFFIC_SYNTHETIC_MIN_DELTA_SECONDS", "60"))
        static_tolerance = float(
            os.environ.get("TRAFFIC_SYNTHETIC_STATIC_TOLERANCE_SECONDS", "30")
        )

        print("[1/10] Checking Valhalla and traffic.tar")
        _compose(
            [
                "--profile",
                "routing",
                "exec",
                "-T",
                "valhalla",
                "sh",
                "-c",
                "test -s /custom_files/valhalla_tiles.tar && test -s /custom_files/traffic.tar",
            ]
        )
        print("traffic.tar is present and non-empty")

        print("[2/10] Building the Valhalla traffic writer helper")
        _compose(["build", "valhalla-traffic-tool"])

        print("[3/10] Requesting baseline route without date_time")
        static_before = _route(origin, destination, traffic_aware=False)
        _write_json(TMP_DIR / "compass-traffic-synthetic-static-before.json", static_before)
        print(f"static_before_seconds={static_before.duration_seconds:.3f}")

        print("[4/10] Requesting baseline route with current traffic enabled")
        traffic_before = _route(origin, destination, traffic_aware=True)
        _write_json(TMP_DIR / "compass-traffic-synthetic-current-before.json", traffic_before)
        print(f"current_before_seconds={traffic_before.duration_seconds:.3f}")

        print("[5/10] Extracting directed GraphIds from the baseline Valhalla route")
        graph_ids = _select_writable_graph_ids(traffic_before.shape, edge_count=edge_count)
        graph_ids_path = TMP_DIR / "compass-traffic-synthetic-graph-ids.json"
        _write_json(graph_ids_path, {"graph_ids": graph_ids})
        print(f"selected_graph_ids={json.dumps(graph_ids)}")

        print("[6/10] Backing up traffic.tar and injecting synthetic slowdown")
        _copy_in_valhalla(TRAFFIC_TAR, BACKUP_TAR)
        backup_created = True
        for graph_id in graph_ids:
            _traffic_tool(
                [
                    "set",
                    "--traffic-tar",
                    TRAFFIC_TAR,
                    "--graph-id",
                    graph_id,
                    "--speed-kph",
                    str(speed_kph),
                    "--congestion",
                    "1.0",
                    "--incidents",
                ]
            )

        print("[7/10] Re-routing without restarting Valhalla")
        static_after = _route(origin, destination, traffic_aware=False)
        traffic_after = _route(origin, destination, traffic_aware=True)
        _write_json(TMP_DIR / "compass-traffic-synthetic-static-after.json", static_after)
        _write_json(TMP_DIR / "compass-traffic-synthetic-current-after.json", traffic_after)

        current_delta = traffic_after.duration_seconds - traffic_before.duration_seconds
        static_delta = static_after.duration_seconds - static_before.duration_seconds
        path_changed = traffic_after.shape != traffic_before.shape
        static_path_changed = static_after.shape != static_before.shape

        print("[8/10] Resetting injected edges to UNKNOWN without restarting Valhalla")
        for graph_id in graph_ids:
            _traffic_tool(
                [
                    "reset",
                    "--traffic-tar",
                    TRAFFIC_TAR,
                    "--graph-id",
                    graph_id,
                ]
            )
        traffic_reset = _route(origin, destination, traffic_aware=True)
        _write_json(
            TMP_DIR / "compass-traffic-synthetic-current-reset.json",
            traffic_reset,
        )
        reset_delta = traffic_reset.duration_seconds - traffic_before.duration_seconds
        reset_path_changed = traffic_reset.shape != traffic_before.shape

        summary = {
            "origin": {"latitude": origin[0], "longitude": origin[1]},
            "destination": {
                "latitude": destination[0],
                "longitude": destination[1],
            },
            "traffic_tar": TRAFFIC_TAR,
            "backup_tar": BACKUP_TAR,
            "injected_graph_ids": graph_ids,
            "synthetic_speed_kph": speed_kph,
            "static_before_seconds": static_before.duration_seconds,
            "static_after_seconds": static_after.duration_seconds,
            "static_delta_seconds": static_delta,
            "static_path_changed": static_path_changed,
            "current_before_seconds": traffic_before.duration_seconds,
            "current_after_seconds": traffic_after.duration_seconds,
            "current_delta_seconds": current_delta,
            "current_path_changed": path_changed,
            "current_reset_seconds": traffic_reset.duration_seconds,
            "reset_delta_seconds": reset_delta,
            "reset_path_changed": reset_path_changed,
            "valhalla_restart_before_injected_route": False,
            "valhalla_restart_before_reset_route": False,
            "accepted": (
                abs(static_delta) <= static_tolerance
                and not static_path_changed
                and (current_delta >= min_delta or path_changed)
                and abs(reset_delta) <= static_tolerance
                and not reset_path_changed
            ),
        }
        _write_json(summary_path, summary)
        print(json.dumps(summary, indent=2, sort_keys=True))

        if not summary["accepted"]:
            print(
                "ERROR: synthetic traffic did not produce the required Valhalla behavior.",
                file=sys.stderr,
            )
            print(
                "Expected: no-date_time route unchanged, current date_time route duration "
                "increased or path changed without restart, then returned to baseline after "
                "an in-place UNKNOWN reset.",
                file=sys.stderr,
            )
            return 1

        print("[9/10] Hot live-traffic update and reset proof accepted")
        return 0
    finally:
        if backup_created:
            print("[10/10] Restoring original traffic.tar and restarting Valhalla")
            try:
                _restore_backup()
                _restart_valhalla()
            except Exception as error:  # noqa: BLE001
                print(
                    f"ERROR: automatic traffic.tar restore failed: {error}",
                    file=sys.stderr,
                )
                print(
                    "Run: docker compose --profile routing exec -T valhalla "
                    f"sh -c 'cp {BACKUP_TAR} {TRAFFIC_TAR}'",
                    file=sys.stderr,
                )
                raise


def _coordinate_from_env(name: str, default: str) -> tuple[float, float]:
    raw = os.environ.get(name, default)
    latitude, separator, longitude = raw.partition(",")
    if not separator:
        raise ValueError(f"{name} must be formatted as latitude,longitude")
    return (float(latitude.strip()), float(longitude.strip()))


def _route(
    origin: tuple[float, float],
    destination: tuple[float, float],
    *,
    traffic_aware: bool,
) -> RouteResult:
    payload: dict[str, Any] = {
        "locations": [
            {"lat": origin[0], "lon": origin[1], "type": "break"},
            {"lat": destination[0], "lon": destination[1], "type": "break"},
        ],
        "costing": "auto",
        "units": "kilometers",
        "language": "it-IT",
        "directions_type": "none",
        "shape_format": "polyline6",
    }
    if traffic_aware:
        payload["date_time"] = {"type": 0}
        payload["costing_options"] = {
            "auto": {
                "speed_types": [
                    "current",
                    "predicted",
                    "constrained",
                    "freeflow",
                ]
            }
        }
    response = _valhalla_post("route", payload)
    _write_json(
        TMP_DIR
        / f"compass-traffic-synthetic-route-{'current' if traffic_aware else 'static'}.json",
        response,
    )
    trip = response["trip"]
    summary = trip["summary"]
    legs = trip["legs"]
    return RouteResult(
        distance_meters=float(summary["length"]) * 1000,
        duration_seconds=float(summary["time"]),
        shape=str(legs[0]["shape"]),
    )


def _select_writable_graph_ids(shape: str, *, edge_count: int) -> list[str]:
    payload = {
        "encoded_polyline": shape,
        "shape_match": "edge_walk",
        "costing": "auto",
        "filters": {
            "attributes": [
                "edge.id",
                "edge.length",
                "edge.names",
                "edge.way_id",
                "edge.road_class",
                "edge.use",
            ],
            "action": "include",
        },
    }
    response = _valhalla_post("trace_attributes", payload)
    trace_path = TMP_DIR / "compass-traffic-synthetic-trace-attributes.json"
    _write_json(trace_path, response)
    edges = response.get("edges")
    if not isinstance(edges, list):
        raise RuntimeError(f"trace_attributes response has no edge list: {trace_path}")

    traffic_tile_keys = _traffic_tar_tile_keys()
    candidates: list[tuple[float, str]] = []
    seen: set[str] = set()
    for edge in edges:
        if not isinstance(edge, dict):
            continue
        graph_id = _graph_id_to_string(edge.get("id"))
        if graph_id is None:
            continue
        if graph_id in seen:
            continue
        seen.add(graph_id)
        length = edge.get("length", 0)
        try:
            length_value = float(length)
        except (TypeError, ValueError):
            length_value = 0.0
        candidates.append((length_value, graph_id))
    candidates.sort(reverse=True)
    graph_ids: list[str] = []
    rejected_missing_tiles: list[str] = []
    for _length, graph_id in candidates:
        if _graph_id_tile_key(graph_id) in traffic_tile_keys:
            graph_ids.append(graph_id)
            if len(graph_ids) >= edge_count:
                break
        else:
            rejected_missing_tiles.append(graph_id)
        if len(rejected_missing_tiles) >= 200 and not graph_ids:
            break

    _write_json(
        TMP_DIR / "compass-traffic-synthetic-graph-id-candidates.json",
        {
            "selected_graph_ids": graph_ids,
            "rejected_missing_traffic_tiles": rejected_missing_tiles,
            "candidate_count": len(candidates),
            "traffic_tile_count": len(traffic_tile_keys),
        },
    )
    if not graph_ids:
        traffic_members = _traffic_tar_members_sample()
        _write_json(
            TMP_DIR / "compass-traffic-synthetic-traffic-members-sample.json",
            {"members": traffic_members},
        )
        raise RuntimeError(
            "trace_attributes exposed route GraphIds, but none of the inspected candidates "
            "exists in the current traffic.tar. Inspect "
            "/tmp/compass-traffic-synthetic-graph-id-candidates.json and "
            "/tmp/compass-traffic-synthetic-traffic-members-sample.json."
        )
    return graph_ids


def _graph_id_to_string(value: object) -> str | None:
    if isinstance(value, str):
        if value.count("/") == 2:
            return value
        if not value.isdigit():
            return None
        value = int(value)
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    level = value & 0x7
    tile_id = (value & GRAPH_ID_TILE_MASK) >> 3
    edge_id = (value & GRAPH_ID_EDGE_MASK) >> 25
    return f"{level}/{tile_id}/{edge_id}"


def _graph_id_tile_key(graph_id: str) -> tuple[int, int] | None:
    parts = graph_id.split("/")
    if len(parts) != 3:
        return None
    try:
        return int(parts[0]), int(parts[1])
    except ValueError:
        return None


def _valhalla_post(action: str, payload: dict[str, Any]) -> dict[str, Any]:
    output = _compose(
        [
            "--profile",
            "routing",
            "exec",
            "-T",
            "valhalla",
            "curl",
            "--fail-with-body",
            "--silent",
            "--show-error",
            "--header",
            "Content-Type: application/json",
            "--data-binary",
            "@-",
            f"http://127.0.0.1:8002/{action}",
        ],
        input_text=json.dumps(payload),
    )
    return json.loads(output)


def _traffic_tar_tile_keys() -> set[tuple[int, int]]:
    keys: set[tuple[int, int]] = set()
    for member in _traffic_tar_members():
        key = _traffic_member_tile_key(member)
        if key is not None:
            keys.add(key)
    return keys


def _traffic_member_tile_key(member: str) -> tuple[int, int] | None:
    normalized = member.removeprefix("./")
    if not normalized.endswith(".gph"):
        return None
    parts = normalized.split("/")
    if len(parts) < 2:
        return None
    try:
        level = int(parts[0])
    except ValueError:
        return None
    tile_digits = [part.removesuffix(".gph") for part in parts[1:] if part]
    if not tile_digits or not all(part.isdigit() for part in tile_digits):
        return None
    return level, int("".join(tile_digits))


def _traffic_tar_members_sample() -> list[str]:
    return _traffic_tar_members()[:80]


def _traffic_tar_members() -> list[str]:
    output = _compose(
        [
            "--profile",
            "routing",
            "exec",
            "-T",
            "valhalla",
            "tar",
            "-tf",
            TRAFFIC_TAR,
        ],
        check=False,
        quiet_stderr=True,
    )
    return [line for line in output.splitlines() if line]


def _traffic_tool(args: list[str]) -> None:
    _compose(
        [
            "--profile",
            "traffic-tools",
            "run",
            "--rm",
            "--no-deps",
            "valhalla-traffic-tool",
            *args,
        ]
    )


def _copy_in_valhalla(source: str, target: str) -> None:
    _compose(
        [
            "--profile",
            "routing",
            "exec",
            "-T",
            "valhalla",
            "sh",
            "-c",
            "cp \"$1\" \"$2\"",
            "sh",
            source,
            target,
        ]
    )


def _restore_backup() -> None:
    _compose(
        [
            "--profile",
            "routing",
            "exec",
            "-T",
            "valhalla",
            "sh",
            "-c",
            "if test -f \"$2\"; then cp \"$2\" \"$1\" && rm -f \"$2\"; fi",
            "sh",
            TRAFFIC_TAR,
            BACKUP_TAR,
        ]
    )


def _restart_valhalla() -> None:
    _compose(["--profile", "routing", "restart", "valhalla"])
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        status = _compose(
            [
                "--profile",
                "routing",
                "exec",
                "-T",
                "valhalla",
                "curl",
                "--fail",
                "--silent",
                "http://127.0.0.1:8002/status",
            ],
            check=False,
            quiet_stderr=True,
        )
        if status:
            return
        time.sleep(2)
    raise RuntimeError("Valhalla did not become ready after restart")


def _compose(
    args: list[str],
    *,
    input_text: str | None = None,
    check: bool = True,
    quiet_stderr: bool = False,
) -> str:
    command = ["docker", "compose", *args]
    result = subprocess.run(
        command,
        input=input_text,
        text=True,
        check=False,
        capture_output=True,
    )
    if not check and result.returncode != 0:
        return ""
    if check and result.returncode != 0:
        if result.stdout:
            print(result.stdout, file=sys.stderr)
        if result.stderr:
            print(result.stderr, file=sys.stderr)
        raise subprocess.CalledProcessError(result.returncode, command)
    if result.stderr and not quiet_stderr:
        print(result.stderr, file=sys.stderr, end="")
    return result.stdout


def _write_json(path: Path, value: object) -> None:
    if isinstance(value, RouteResult):
        value = {
            "distance_meters": value.distance_meters,
            "duration_seconds": value.duration_seconds,
            "shape": value.shape,
        }
    path.write_text(json.dumps(value, indent=2, sort_keys=True), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
