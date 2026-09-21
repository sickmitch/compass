import importlib.util
import json
from pathlib import Path

import pytest

MODULE_PATH = Path("tools/configure-valhalla-runtime.py")


def _module():
    spec = importlib.util.spec_from_file_location("configure_valhalla_runtime", MODULE_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_enables_hard_exclusions_without_losing_existing_config(tmp_path: Path) -> None:
    config_path = tmp_path / "valhalla.json"
    config_path.write_text(
        json.dumps({"mjolnir": {"tile_dir": "/tiles"}, "service_limits": {"auto": {}}}),
        encoding="utf-8",
    )

    changed = _module().enable_hard_exclusions(config_path)

    assert changed is True
    assert json.loads(config_path.read_text(encoding="utf-8")) == {
        "mjolnir": {"tile_dir": "/tiles"},
        "service_limits": {"allow_hard_exclusions": True, "auto": {}},
    }


def test_hard_exclusion_configuration_is_idempotent(tmp_path: Path) -> None:
    config_path = tmp_path / "valhalla.json"
    config_path.write_text(
        json.dumps({"service_limits": {"allow_hard_exclusions": True}}),
        encoding="utf-8",
    )

    assert _module().enable_hard_exclusions(config_path) is False


def test_missing_valhalla_config_has_actionable_error(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match="build the routing tiles first"):
        _module().enable_hard_exclusions(tmp_path / "valhalla.json")
