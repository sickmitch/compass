from __future__ import annotations

import importlib.util
from pathlib import Path


def _load_module():
    path = (
        Path(__file__).resolve().parents[1]
        / "tools"
        / "valhalla-traffic"
        / "rebuild_traffic_extract.py"
    )
    spec = importlib.util.spec_from_file_location("rebuild_traffic_extract", path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_rebuild_config_redirects_both_outputs_without_mutating_source() -> None:
    module = _load_module()
    source = {
        "mjolnir": {
            "tile_dir": "/custom_files/valhalla_tiles",
            "tile_extract": "/custom_files/valhalla_tiles.tar",
            "traffic_extract": "/custom_files/traffic.tar",
        },
        "service_limits": {"auto": {"max_locations": 20}},
    }

    result = module.build_rebuild_config(
        source,
        traffic_extract=Path("/custom_files/.traffic.tar.compass-rebuild"),
        tile_extract=Path("/custom_files/.valhalla_tiles.tar.compass-traffic-rebuild"),
    )

    assert source["mjolnir"]["traffic_extract"] == "/custom_files/traffic.tar"
    assert result["mjolnir"]["traffic_extract"].endswith(".compass-rebuild")
    assert result["mjolnir"]["tile_extract"].endswith(".compass-traffic-rebuild")
    assert result["service_limits"] == source["service_limits"]


def test_rebuild_config_requires_mjolnir() -> None:
    module = _load_module()

    try:
        module.build_rebuild_config(
            {},
            traffic_extract=Path("/tmp/traffic.tar"),
            tile_extract=Path("/tmp/tiles.tar"),
        )
    except ValueError as error:
        assert "mjolnir" in str(error)
    else:
        raise AssertionError("missing mjolnir must be rejected")
