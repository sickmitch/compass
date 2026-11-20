"""Validate the actual Android asset; optional native check uses pyvalhalla==3.9.0."""
import importlib.metadata
import json
from pathlib import Path

import pytest

ASSET = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/offline-valhalla.json"


def test_route_actor_has_meili_initialization_config_without_remote_services():
    config = json.loads(ASSET.read_text())
    # Loki reads breakage_distance even for /route; Thor constructs the Meili factory.
    meili = config["meili"]
    assert meili["default"]["breakage_distance"] == 2000
    for key in ("sigma_z", "beta", "max_route_distance_factor", "max_route_time_factor",
                "max_search_radius", "interpolation_distance"):
        assert meili["default"][key] > 0
    assert meili["grid"]["size"] > 0
    assert meili["grid"]["cache_size"] > 0
    assert "service" not in meili
    assert config["loki"]["actions"] == ["route"]
    assert "tile_url" not in config["mjolnir"]
    # Independent offline graphs must never share tiles cached by graph ID.
    assert config["mjolnir"]["global_synchronized_cache"] is False


def test_native_actor_reproduces_old_failure_and_accepts_current_asset(tmp_path):
    try:
        version = importlib.metadata.version("pyvalhalla")
    except importlib.metadata.PackageNotFoundError:
        pytest.skip("Optional native check: install pyvalhalla==3.9.0 in a separate environment")
    assert version == "3.9.0", "Use the matching pinned native validation runtime"
    from valhalla import Actor

    config = json.loads(ASSET.read_text())
    config["mjolnir"]["tile_dir"] = str(tmp_path)
    config["mjolnir"]["tile_extract"] = str(tmp_path / "no-graph.tar")
    old = {key: value for key, value in config.items() if key != "meili"}
    with pytest.raises(RuntimeError, match=r"meili.default.breakage_distance"):
        Actor(old)
    # No graph is needed to test constructor configuration. This does not test routing.
    actor = Actor(config)
    del actor
