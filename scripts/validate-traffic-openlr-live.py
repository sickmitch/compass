#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from math import asin, cos, radians, sin, sqrt
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matching", type=Path, required=True)
    parser.add_argument("--decoded", type=Path, action="append", required=True)
    parser.add_argument("--endpoint-tolerance-meters", type=float, default=300.0)
    args = parser.parse_args()
    matching = json.loads(args.matching.read_text(encoding="utf-8"))
    decoded = [json.loads(path.read_text(encoding="utf-8")) for path in args.decoded]
    validate(
        matching,
        decoded,
        endpoint_tolerance_meters=args.endpoint_tolerance_meters,
    )
    return 0


def validate(
    matching: object,
    decoded: object,
    *,
    endpoint_tolerance_meters: float = 300.0,
) -> None:
    _require(endpoint_tolerance_meters > 0, "endpoint tolerance must be positive")
    _require(isinstance(matching, dict), "matching output must be an object")
    _require(isinstance(decoded, list) and decoded, "at least one OpenLR must be decoded")
    assert isinstance(matching, dict)
    assert isinstance(decoded, list)
    results = matching.get("results")
    _require(isinstance(results, list) and results, "matching results must be present")
    assert isinstance(results, list)
    expected_references = {
        result.get("provider_segment_id")
        for result in results
        if isinstance(result, dict)
        and isinstance(result.get("provider_segment_id"), str)
        and result.get("provider_segment_id")
    }
    decoded_references: set[str] = set()
    decoded_by_reference: dict[str, dict[str, object]] = {}
    lrp_count = 0
    for value in decoded:
        _require(isinstance(value, dict), "decoded OpenLR must be an object")
        assert isinstance(value, dict)
        reference = value.get("reference")
        canonical = value.get("canonical_reference")
        _require(isinstance(reference, str) and reference, "OpenLR reference is missing")
        _require(canonical == reference, "OpenLR native round trip changed the reference")
        _require(value.get("location_type") == "line", "expected an OpenLR line location")
        _require(
            value.get("line_direction") == "first_lrp_to_last_lrp",
            "OpenLR line direction is missing",
        )
        lrps = value.get("lrps")
        _require(isinstance(lrps, list) and len(lrps) >= 2, "OpenLR needs at least two LRPs")
        assert isinstance(lrps, list)
        for index, lrp in enumerate(lrps):
            _require(isinstance(lrp, dict), "OpenLR LRP must be an object")
            assert isinstance(lrp, dict)
            _require(lrp.get("index") == index, "OpenLR LRPs must remain ordered")
            for field in ("longitude", "latitude", "bearing_degrees"):
                _require(isinstance(lrp.get(field), int | float), f"LRP {field} must be numeric")
        decoded_references.add(reference)
        decoded_by_reference[reference] = value
        lrp_count += len(lrps)

    _require(
        decoded_references == expected_references,
        "decoded OpenLR references do not match the live matching records",
    )
    direction_evidence = []
    for result in results:
        assert isinstance(result, dict)
        reference = result.get("provider_segment_id")
        _require(isinstance(reference, str), "provider segment ID must be a string")
        source = result.get("source_reference")
        _require(
            isinstance(source, dict),
            "matching output lacks source_reference; rerun the matching live script",
        )
        assert isinstance(source, dict)
        _require(source.get("openlr") == reference, "source OpenLR does not match segment ID")
        geometry = source.get("geometry")
        _require(
            isinstance(geometry, list) and len(geometry) >= 2,
            "provider geometry needs at least two points",
        )
        assert isinstance(geometry, list)
        decoded_value = decoded_by_reference[reference]
        lrps = decoded_value["lrps"]
        assert isinstance(lrps, list)
        first_lrp = _coordinate(lrps[0], "first LRP")
        last_lrp = _coordinate(lrps[-1], "last LRP")
        first_geometry = _coordinate(geometry[0], "first provider geometry point")
        last_geometry = _coordinate(geometry[-1], "last provider geometry point")
        direct_start = _distance_meters(first_lrp, first_geometry)
        direct_end = _distance_meters(last_lrp, last_geometry)
        reverse_start = _distance_meters(first_lrp, last_geometry)
        reverse_end = _distance_meters(last_lrp, first_geometry)
        direct_total = direct_start + direct_end
        reverse_total = reverse_start + reverse_end
        _require(
            direct_total < reverse_total,
            "provider geometry runs opposite to the decoded OpenLR direction",
        )
        _require(
            max(direct_start, direct_end) <= endpoint_tolerance_meters,
            f"OpenLR endpoints exceed {endpoint_tolerance_meters:.0f} m tolerance",
        )
        direction_evidence.append(
            {
                "provider_segment_id": reference,
                "start_alignment_meters": round(direct_start, 3),
                "end_alignment_meters": round(direct_end, 3),
                "reverse_alignment_meters": round(reverse_total, 3),
                "direction_verified": True,
            }
        )
    summary = {
        "provider": matching.get("provider"),
        "decoded_reference_count": len(decoded_references),
        "decoded_lrp_count": lrp_count,
        "direction_semantics": "first_lrp_to_last_lrp",
        "direction_verified_count": len(direction_evidence),
        "direction_evidence": direction_evidence,
        "provider_overlay_write_enabled": False,
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    print("Native Valhalla OpenLR decoding live diagnostic accepted.")


def _require(condition: object, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _coordinate(value: object, label: str) -> tuple[float, float]:
    _require(isinstance(value, dict), f"{label} must be an object")
    assert isinstance(value, dict)
    latitude = value.get("latitude")
    longitude = value.get("longitude")
    _require(isinstance(latitude, int | float), f"{label} latitude must be numeric")
    _require(isinstance(longitude, int | float), f"{label} longitude must be numeric")
    return float(latitude), float(longitude)


def _distance_meters(left: tuple[float, float], right: tuple[float, float]) -> float:
    left_latitude, left_longitude = map(radians, left)
    right_latitude, right_longitude = map(radians, right)
    latitude_delta = right_latitude - left_latitude
    longitude_delta = right_longitude - left_longitude
    value = (
        sin(latitude_delta / 2) ** 2
        + cos(left_latitude)
        * cos(right_latitude)
        * sin(longitude_delta / 2) ** 2
    )
    return 2 * 6_371_008.8 * asin(sqrt(min(1.0, value)))


if __name__ == "__main__":
    raise SystemExit(main())
