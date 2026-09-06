from __future__ import annotations

import json
from pathlib import Path

ASSET_ROOT = Path("android/app/src/main/assets")


def _load_style(theme: str) -> dict[str, object]:
    return json.loads((ASSET_ROOT / f"compass-{theme}.json").read_text(encoding="utf-8"))


def test_compass_day_and_night_styles_are_low_noise_and_structurally_equivalent() -> None:
    day = _load_style("day")
    night = _load_style("night")

    assert day["version"] == night["version"] == 8
    assert day["metadata"]["compass:theme"] == "day"
    assert night["metadata"]["compass:theme"] == "night"
    assert day["metadata"]["compass:purpose"] == "low-noise-driving"
    assert night["metadata"]["compass:purpose"] == "low-noise-driving"
    assert [layer["id"] for layer in day["layers"]] == [
        layer["id"] for layer in night["layers"]
    ]
    assert not any(layer["type"] == "fill-extrusion" for layer in day["layers"])
    assert not any(layer["type"] == "fill-extrusion" for layer in night["layers"])


def test_compass_styles_keep_road_hierarchy_italian_labels_and_https_resources() -> None:
    for theme in ("day", "night"):
        style = _load_style(theme)
        layer_ids = {layer["id"] for layer in style["layers"]}
        assert {
            "road-minor",
            "road-secondary",
            "road-primary",
            "road-motorway",
            "road-label",
            "place-label",
        } <= layer_ids
        assert style["sources"]["openmaptiles"]["url"].startswith("https://")
        assert style["glyphs"].startswith("https://")
        encoded = json.dumps(style, ensure_ascii=False)
        assert "name:it" in encoded
        assert '"source-layer": "poi"' not in encoded


def test_day_and_night_backgrounds_are_deliberately_distinct() -> None:
    day = _load_style("day")
    night = _load_style("night")
    day_background = day["layers"][0]["paint"]["background-color"]
    night_background = night["layers"][0]["paint"]["background-color"]

    assert day_background == "#f5f3ed"
    assert night_background == "#111714"
