from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "validate-traffic-openlr-live.py"
RUNNER = Path(__file__).parents[1] / "scripts" / "run-traffic-openlr-live.sh"
DIRECTION_RUNNER = (
    Path(__file__).parents[1] / "scripts" / "run-traffic-direction-live.sh"
)
SPEC = importlib.util.spec_from_file_location("validate_traffic_openlr_live", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def _matching() -> dict[str, object]:
    return {
        "provider": "tomtom",
        "results": [
            {
                "provider_segment_id": "CwajFyA9fAEJRxG5+OgBGw==",
                "source_reference": {
                    "openlr": "CwajFyA9fAEJRxG5+OgBGw==",
                    "direction": "unknown",
                    "geometry": [
                        {"latitude": 45.1, "longitude": 9.1},
                        {"latitude": 45.1, "longitude": 9.2},
                    ],
                },
                "quality_accepted": True,
                "write_eligible": False,
            }
        ],
    }


def _decoded() -> list[dict[str, object]]:
    reference = "CwajFyA9fAEJRxG5+OgBGw=="
    return [
        {
            "reference": reference,
            "canonical_reference": reference,
            "location_type": "line",
            "line_direction": "first_lrp_to_last_lrp",
            "lrps": [
                {"index": 0, "longitude": 9.1, "latitude": 45.1, "bearing_degrees": 90.0},
                {"index": 1, "longitude": 9.2, "latitude": 45.1, "bearing_degrees": 90.0},
            ],
        }
    ]


def test_validate_accepts_native_openlr_round_trip(capsys: pytest.CaptureFixture[str]) -> None:
    MODULE.validate(_matching(), _decoded())
    output = capsys.readouterr().out
    assert '"decoded_reference_count": 1' in output
    assert '"direction_verified_count": 1' in output
    assert "Native Valhalla OpenLR decoding live diagnostic accepted." in output


def test_validate_rejects_changed_reference() -> None:
    decoded = _decoded()
    decoded[0]["canonical_reference"] = "changed"
    with pytest.raises(AssertionError, match="round trip changed"):
        MODULE.validate(_matching(), decoded)


def test_validate_rejects_unordered_lrps() -> None:
    decoded = _decoded()
    assert isinstance(decoded[0]["lrps"], list)
    decoded[0]["lrps"][1]["index"] = 7
    with pytest.raises(AssertionError, match="remain ordered"):
        MODULE.validate(_matching(), decoded)


def test_runner_preloads_references_before_docker_consumes_stdin() -> None:
    runner = RUNNER.read_text(encoding="utf-8")

    assert 'mapfile -t references <"$references_path"' in runner
    assert 'for reference in "${references[@]}"' in runner
    assert '>"$decoded_path" </dev/null' in runner


def test_validate_rejects_reversed_provider_geometry() -> None:
    matching = _matching()
    results = matching["results"]
    assert isinstance(results, list)
    source = results[0]["source_reference"]
    assert isinstance(source, dict)
    geometry = source["geometry"]
    assert isinstance(geometry, list)
    source["geometry"] = list(reversed(geometry))

    with pytest.raises(AssertionError, match="opposite"):
        MODULE.validate(matching, _decoded())


def test_validate_rejects_provider_geometry_outside_tolerance() -> None:
    matching = _matching()
    results = matching["results"]
    assert isinstance(results, list)
    source = results[0]["source_reference"]
    assert isinstance(source, dict)
    source["geometry"] = [
        {"latitude": 46.1, "longitude": 9.1},
        {"latitude": 46.1, "longitude": 9.2},
    ]

    with pytest.raises(AssertionError, match="exceed"):
        MODULE.validate(matching, _decoded())


def test_direction_runner_refreshes_matching_before_openlr_validation() -> None:
    runner = DIRECTION_RUNNER.read_text(encoding="utf-8")

    matching = runner.index("bash scripts/run-traffic-matching-live.sh")
    openlr = runner.index("bash scripts/run-traffic-openlr-live.sh")
    assert matching < openlr
