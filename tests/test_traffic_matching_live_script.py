from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


def _load_validator_module():
    path = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "validate-traffic-matching-live.py"
    )
    spec = importlib.util.spec_from_file_location(
        "validate_traffic_matching_live", path
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["validate_traffic_matching_live"] = module
    spec.loader.exec_module(module)
    return module


def _valid_payload() -> dict[str, object]:
    return {
        "provider": "tomtom",
        "configured_tileset_identity": "valhalla-3.8.3:123",
        "minimum_match_confidence": 0.75,
        "provider_overlay_write_enabled": False,
        "results": [
            {
                "provider_segment_id": "segment-1",
                "quality_accepted": True,
                "write_eligible": True,
                "match": {
                    "directed_edge_ids": ["2/779796/238122"],
                    "match_method": "geometry_trace",
                    "confidence": 0.91,
                    "direction_match": True,
                    "valhalla_tileset_version": "valhalla-3.8.3:123",
                    "mapping_version": "valhalla-openlr-geometry-v1",
                },
            }
        ],
    }


def test_matching_live_validator_accepts_confident_diagnostic_match() -> None:
    module = _load_validator_module()

    module.validate(_valid_payload())


def test_matching_live_validator_rejects_stale_or_low_confidence_match() -> None:
    module = _load_validator_module()
    payload = _valid_payload()
    result = payload["results"][0]  # type: ignore[index]
    result["quality_accepted"] = False  # type: ignore[index]

    with pytest.raises(AssertionError, match="no fresh TomTom segment"):
        module.validate(payload)


def test_matching_runner_builds_native_updater_and_preserves_overlay() -> None:
    runner = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "run-traffic-matching-live.sh"
    ).read_text(encoding="utf-8")

    assert "build traffic-updater" in runner
    assert runner.count("sha256sum /custom_files/traffic.tar") == 2
    assert 'test "$traffic_hash_before" = "$traffic_hash_after"' in runner
    assert "TRAFFIC_VALHALLA_OVERLAY_ENABLED=false" in runner
