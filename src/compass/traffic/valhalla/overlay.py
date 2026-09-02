from __future__ import annotations

import json
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path
from tempfile import NamedTemporaryFile

from compass.traffic.valhalla.planner import TrafficOverlayPlan


@dataclass(frozen=True, slots=True)
class ValhallaTrafficOverlayConfig:
    tile_dir: Path
    tile_extract: Path
    traffic_extract: Path
    valhalla_config: Path
    tileset_version: str | None = None

    def __post_init__(self) -> None:
        if self.tile_dir == self.traffic_extract:
            raise ValueError("traffic extract must not be the routing tile directory")
        if self.tile_extract == self.traffic_extract:
            raise ValueError("traffic extract must be separate from the routing tile extract")


def build_extract_command(config: ValhallaTrafficOverlayConfig) -> tuple[str, ...]:
    """Return the Valhalla command that creates the matched traffic.tar skeleton."""
    return (
        "valhalla_build_extract",
        "--config",
        str(config.valhalla_config),
        "--with-traffic",
        "--overwrite",
    )


class ValhallaTrafficWriterError(RuntimeError):
    """A native overlay transaction was rejected or could not be committed."""


@dataclass(frozen=True, slots=True)
class TrafficWriteReceipt:
    set_count: int
    reset_count: int
    operation_count: int


class NativeValhallaTrafficWriter:
    """Apply one validated plan with the pinned native Valhalla helper."""

    def __init__(
        self,
        *,
        executable_path: str,
        traffic_extract: Path,
        timeout_seconds: float = 30,
    ) -> None:
        if not executable_path:
            raise ValueError("native traffic writer path must not be empty")
        if timeout_seconds <= 0:
            raise ValueError("native traffic writer timeout must be positive")
        self._executable_path = executable_path
        self._traffic_extract = traffic_extract
        self._timeout_seconds = timeout_seconds

    def apply(
        self,
        plan: TrafficOverlayPlan,
        *,
        require_unknown: bool = False,
    ) -> TrafficWriteReceipt:
        payload = {
            "require_unknown": require_unknown,
            "set_updates": [
                {
                    "graph_id": update.graph_id,
                    "speed_kph": update.speed_kph,
                    "congestion": update.congestion,
                    "closed": update.closed,
                    "has_incidents": update.has_incidents,
                }
                for update in plan.set_updates
            ],
            "reset_graph_ids": list(plan.reset_graph_ids),
        }
        try:
            with NamedTemporaryFile(
                "w",
                encoding="utf-8",
                prefix="compass-traffic-plan-",
                suffix=".json",
            ) as handle:
                json.dump(payload, handle, separators=(",", ":"))
                handle.flush()
                completed = subprocess.run(
                    [
                        self._executable_path,
                        "apply-plan",
                        "--traffic-tar",
                        str(self._traffic_extract),
                        "--plan-file",
                        handle.name,
                    ],
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=self._timeout_seconds,
                )
        except (OSError, subprocess.SubprocessError) as error:
            raise ValhallaTrafficWriterError(
                "native Valhalla traffic writer could not run"
            ) from error
        if completed.returncode != 0:
            reason = completed.stderr.strip().splitlines()[-1:] or ["unknown error"]
            raise ValhallaTrafficWriterError(
                f"native Valhalla traffic plan was rejected: {reason[0]}"
            )
        try:
            response = json.loads(completed.stdout)
            receipt = TrafficWriteReceipt(
                set_count=int(response["set_count"]),
                reset_count=int(response["reset_count"]),
                operation_count=int(response["operation_count"]),
            )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise ValhallaTrafficWriterError(
                "native Valhalla traffic writer returned an invalid receipt"
            ) from error
        expected_operations = len(plan.set_updates) + len(plan.reset_graph_ids)
        if (
            receipt.set_count != len(plan.set_updates)
            or receipt.reset_count != len(plan.reset_graph_ids)
            or receipt.operation_count != expected_operations
        ):
            raise ValhallaTrafficWriterError(
                "native Valhalla traffic writer receipt does not match the plan"
            )
        try:
            with self._traffic_extract.open("rb") as archive:
                os.fsync(archive.fileno())
        except OSError as error:
            raise ValhallaTrafficWriterError(
                "applied traffic archive could not be synchronized"
            ) from error
        return receipt
