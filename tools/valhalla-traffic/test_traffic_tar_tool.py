#!/usr/bin/env python3
from __future__ import annotations

import io
import json
import struct
import subprocess
import tarfile
import tempfile
from pathlib import Path

TOOL = "/usr/local/bin/compass-valhalla-traffic-tool"
LEVEL = 0
TILE_ID = 3017
EDGE_ID = 1
GRAPH_ID = f"{LEVEL}/{TILE_ID}/{EDGE_ID}"
OPENLR_REFERENCE = "CwajFyA9fAEJRxG5+OgBGw=="


def _write_tar(path: Path, name: str, payload: bytes) -> None:
    with tarfile.open(path, "w", format=tarfile.USTAR_FORMAT) as archive:
        member = tarfile.TarInfo(name)
        member.size = len(payload)
        member.mode = 0o644
        archive.addfile(member, io.BytesIO(payload))


def _run(*args: str) -> dict[str, object]:
    completed = subprocess.run(
        [TOOL, *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(completed.stdout)


def main() -> None:
    decoded = _run("decode-openlr", "--reference", OPENLR_REFERENCE)
    assert decoded["canonical_reference"] == OPENLR_REFERENCE
    assert decoded["location_type"] == "line"
    assert decoded["line_direction"] == "first_lrp_to_last_lrp"
    assert len(decoded["lrps"]) >= 2
    assert decoded["lrps"][0]["distance_to_next_meters"] > 0

    malformed = subprocess.run(
        [TOOL, "decode-openlr", "--reference", "not-openlr"],
        check=False,
        capture_output=True,
        text=True,
    )
    assert malformed.returncode != 0
    assert "error:" in malformed.stderr

    with tempfile.TemporaryDirectory(prefix="compass-traffic-tool-") as directory:
        root = Path(directory)
        index_tar = root / "index.tar"
        tiles_tar = root / "tiles.tar"
        traffic_tar = root / "traffic.tar"

        # TrafficTileHeader.tile_id stores the encoded tile-base GraphId.
        tile_base_graph_id = LEVEL | (TILE_ID << 3)
        traffic_header = struct.pack("<QQIIII", tile_base_graph_id, 0, 2, 3, 0, 0)
        traffic_speeds = bytes(2 * 8)
        _write_tar(index_tar, "index.bin", b"fixture-index")
        _write_tar(tiles_tar, "0/003/017.gph", traffic_header + traffic_speeds)

        # Valhalla traffic extracts may contain concatenated TAR sections separated
        # by empty blocks. midgard::tar deliberately traverses those blocks.
        traffic_tar.write_bytes(index_tar.read_bytes() + tiles_tar.read_bytes())

        common = ("--traffic-tar", str(traffic_tar), "--graph-id", GRAPH_ID)
        initial = _run("inspect", *common)
        assert initial["member_name"] == "0/003/017.gph"
        assert initial["speed_valid"] is False

        updated = _run(
            "set",
            *common,
            "--speed-kph",
            "18",
            "--congestion",
            "0.9",
            "--incidents",
        )
        assert updated["overall_speed_kph"] == 18
        assert updated["has_incidents"] is True

        reset = _run("reset", *common)
        assert reset["speed_valid"] is False
        assert reset["closed"] is False

        plan_path = root / "plan.json"
        plan_path.write_text(
            json.dumps(
                {
                    "set_updates": [
                        {
                            "graph_id": GRAPH_ID,
                            "speed_kph": 22,
                            "congestion": 0.4,
                            "closed": False,
                            "has_incidents": False,
                        }
                    ],
                    "reset_graph_ids": [],
                }
            ),
            encoding="utf-8",
        )
        applied = _run(
            "apply-plan",
            "--traffic-tar",
            str(traffic_tar),
            "--plan-file",
            str(plan_path),
        )
        assert applied["set_count"] == 1
        assert applied["reset_count"] == 0
        assert _run("inspect", *common)["overall_speed_kph"] == 22

        plan_path.write_text(
            json.dumps({"set_updates": [], "reset_graph_ids": [GRAPH_ID]}),
            encoding="utf-8",
        )
        applied = _run(
            "apply-plan",
            "--traffic-tar",
            str(traffic_tar),
            "--plan-file",
            str(plan_path),
        )
        assert applied["set_count"] == 0
        assert applied["reset_count"] == 1
        assert _run("inspect", *common)["speed_valid"] is False

        before_invalid = traffic_tar.read_bytes()
        plan_path.write_text(
            json.dumps(
                {
                    "set_updates": [
                        {
                            "graph_id": GRAPH_ID,
                            "speed_kph": 30,
                            "closed": False,
                        }
                    ],
                    "reset_graph_ids": [GRAPH_ID],
                }
            ),
            encoding="utf-8",
        )
        invalid = subprocess.run(
            [
                TOOL,
                "apply-plan",
                "--traffic-tar",
                str(traffic_tar),
                "--plan-file",
                str(plan_path),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        assert invalid.returncode != 0
        assert "duplicate graph id" in invalid.stderr
        assert traffic_tar.read_bytes() == before_invalid


if __name__ == "__main__":
    main()
