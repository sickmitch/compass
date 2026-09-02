from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


def _load_script_module():
    path = Path(__file__).resolve().parents[1] / "scripts" / "run-traffic-synthetic-live.py"
    spec = importlib.util.spec_from_file_location("run_traffic_synthetic_live", path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["run_traffic_synthetic_live"] = module
    spec.loader.exec_module(module)
    return module


def test_decodes_numeric_valhalla_graph_id_from_trace_attributes() -> None:
    module = _load_script_module()

    assert module._graph_id_to_string(7990054695074) == "2/779796/238122"


def test_keeps_string_valhalla_graph_id() -> None:
    module = _load_script_module()

    assert module._graph_id_to_string("2/493412/238120") == "2/493412/238120"


def test_parses_valhalla_traffic_tile_member_path() -> None:
    module = _load_script_module()

    assert module._traffic_member_tile_key("0/000/003/017.gph") == (0, 3017)
    assert module._traffic_member_tile_key("2/000/779/796.gph") == (2, 779796)
    assert module._traffic_member_tile_key("./1/048/431.gph") == (1, 48431)
    assert module._traffic_member_tile_key("index.bin") is None


def test_selects_route_edges_whose_tiles_exist_in_traffic_extract(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    module = _load_script_module()
    monkeypatch.setattr(module, "TMP_DIR", tmp_path)
    monkeypatch.setattr(
        module,
        "_valhalla_post",
        lambda _action, _payload: {
            "edges": [
                {"id": "0/3017/10", "length": 1.5},
                {"id": "1/48431/20", "length": 2.0},
                {"id": "0/3017/30", "length": 3.0},
            ]
        },
    )
    monkeypatch.setattr(module, "_traffic_tar_tile_keys", lambda: {(0, 3017)})

    assert module._select_writable_graph_ids("encoded-shape", edge_count=2) == [
        "0/3017/30",
        "0/3017/10",
    ]


def test_live_proof_routes_after_set_and_reset_without_restart() -> None:
    source = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "run-traffic-synthetic-live.py"
    ).read_text(encoding="utf-8")
    hot_update = source.split(
        'print("[6/10] Backing up traffic.tar and injecting synthetic slowdown")', 1
    )[1].split(
        'print("[8/10] Resetting injected edges to UNKNOWN without restarting Valhalla")',
        1,
    )[0]
    hot_reset = source.split(
        'print("[8/10] Resetting injected edges to UNKNOWN without restarting Valhalla")',
        1,
    )[1].split('print("[9/10] Hot live-traffic update and reset proof accepted")', 1)[0]

    assert "_restart_valhalla()" not in hot_update
    assert "_restart_valhalla()" not in hot_reset
    assert "traffic_after = _route" in hot_update
    assert '"reset"' in hot_reset
    assert "traffic_reset = _route" in hot_reset
