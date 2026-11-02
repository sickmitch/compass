import asyncio
from copy import deepcopy
from datetime import UTC, datetime
from unittest.mock import MagicMock

import httpx
import pytest
from fastapi import FastAPI

from compass.api.enforcement import router
from compass.candidates.geometry import encode_polyline6
from compass.db import get_session
from compass.enforcement import packages
from compass.enforcement.packages import package_control, read_package
from compass.routing.domain import Coordinate

IDENTITY = "3.8.3:123"


def record(**changes):
    result = {
        "source_key": "osm:relation:7", "kind": "fixed", "import_id": 2,
        "latitude": 45., "longitude": 9., "direction_degrees": None,
        "speed_limit_kph": 50., "fetched_at": datetime(2026, 11, 2, tzinfo=UTC),
        "raw": {"type": "relation", "road_ways": [], "members": [
            {"role": "device", "type": "node", "ref": 42},
        ]},
        "association_import": 2, "association_graph": IDENTITY,
        "state": "matched", "reason": "explicit_osm_topology_and_edge_walk",
        "evidence": {
            "osm_way_id": 10, "forward": True, "from_node": 1, "to_node": 3,
            "edge_ids": ["123", "9007199254740993"], "length_meters": 157.,
            "encoded_polyline": encode_polyline6((Coordinate(45, 9), Coordinate(45, 9.002))),
        },
    }
    result.update(changes)
    return result


def session_for(rows):
    session = MagicMock()
    session.execute.return_value.mappings.return_value.all.return_value = rows
    return session


def test_current_evidence_keeps_precision_direction_and_source_time_without_alerts():
    control = package_control(record(), IDENTITY)
    assert control.association_state == "matched"
    assert control.directed_path.edge_ids[-1] == "9007199254740993"
    assert control.directed_path.forward
    assert control.physical_control_key == "osm:node:42"
    assert control.source_import_id == "2"
    assert control.fetched_at == record()["fetched_at"]
    assert control.alert_eligible is False


@pytest.mark.parametrize("changes,expected", [
    ({"association_import": 1}, "stale"),
    ({"association_graph": "3.8.3:456"}, "stale"),
    ({"association_graph": None}, "unmatched"),
    ({"raw": {"type": "relation"}}, "missing_source_geometry"),
    ({"state": "ambiguous"}, "ambiguous"),
    ({"state": "unknown_direction"}, "unknown_direction"),
    ({"state": "unexpected"}, "invalid_evidence"),
])
def test_only_current_matched_associations_expose_directed_paths(changes, expected):
    control = package_control(record(**changes), IDENTITY)
    assert control.association_state == expected
    assert control.directed_path is None
    assert not control.alert_eligible


@pytest.mark.parametrize("key,value", [
    ("forward", "false"), ("osm_way_id", True), ("from_node", None),
    ("to_node", 1), ("edge_ids", []), ("edge_ids", [123]),
    ("encoded_polyline", "broken"), ("length_meters", float("nan")),
    ("length_meters", True), ("length_meters", -1),
])
def test_invalid_evidence_is_explicit_and_never_forwarded(key, value):
    row = record()
    row["evidence"][key] = value
    control = package_control(row, IDENTITY)
    assert control.association_state == "invalid_evidence"
    assert control.directed_path is None


def test_aliases_do_not_merge_opposite_directed_controls():
    first = record()
    second = deepcopy(first)
    second["source_key"] = "osm:relation:8"
    second["evidence"].update(forward=False, from_node=3, to_node=1, edge_ids=["456"])
    result = read_package(session_for([first, second]), IDENTITY, 45, 9, 2000)
    assert len(result.controls) == 2
    assert result.controls[0].physical_control_key == result.controls[1].physical_control_key
    assert result.controls[0].directed_path.forward != result.controls[1].directed_path.forward
    assert result.controls[0].directed_path.edge_ids != result.controls[1].directed_path.edge_ids


def test_count_and_byte_limits_are_explicit(monkeypatch):
    session = session_for([record() for _ in range(201)])
    result = read_package(session, IDENTITY, 45, 9, 2000)
    assert len(result.controls) == 200 and result.truncated
    session.execute.assert_called_once()
    assert session.execute.call_args.args[1]["limit"] == 201
    monkeypatch.setattr(packages, "MAX_BYTES", 1400)
    result = read_package(session_for([record()] * 3), IDENTITY, 45, 9, 2000)
    assert 0 < len(result.controls) < 3 and result.truncated
    assert len(result.model_dump_json().encode()) <= 1400
    empty = read_package(session_for([]), IDENTITY, 45, 9, 2000)
    assert not empty.controls and not empty.truncated


@pytest.mark.parametrize("mode,expected", [("ok", 200), ("changed", 503), ("down", 503)])
def test_http_package_uses_two_status_reads_and_no_routing_or_provider_fetch(mode, expected):
    async def check():
        app = FastAPI()
        app.include_router(router)
        session = session_for([record()])
        app.dependency_overrides[get_session] = lambda: session
        calls = []

        def handle(request):
            calls.append(request)
            assert request.method == "GET" and request.url.path == "/status"
            if mode == "down":
                return httpx.Response(503, text="private upstream details")
            timestamp = 456 if mode == "changed" and len(calls) == 2 else 123
            return httpx.Response(200, json={
                "version": "3.8.3", "tileset_last_modified": timestamp,
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as provider:
            app.state.routing_http_client = provider
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                         base_url="http://test") as client:
                response = await client.get("/api/v1/enforcement/map-package", params={
                    "latitude": 45, "longitude": 9,
                })
                assert response.status_code == expected
                if mode == "ok":
                    data = response.json()
                    assert data["graph_identity"] == IDENTITY
                    assert data["controls"][0]["association_state"] == "matched"
                    assert data["controls"][0]["alert_eligible"] is False
                    assert data["spatial_basis"] == "control_location"
                else:
                    assert "controls" not in response.json()
                    assert "private upstream" not in response.text
                bad = await client.get("/api/v1/enforcement/map-package", params={
                    "latitude": 45, "longitude": 9, "radius_meters": 10001,
                })
                assert bad.status_code == 422
        assert len(calls) == (1 if mode == "down" else 2)
        assert session.execute.call_count == (0 if mode == "down" else 1)
    asyncio.run(check())
