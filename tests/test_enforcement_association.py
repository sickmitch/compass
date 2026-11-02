import asyncio
import json

import httpx
import pytest

from compass.candidates.geometry import encode_polyline6
from compass.config import Settings
from compass.enforcement.association import associate, graph_identity, source_path, validate_trace
from compass.enforcement.osm import parse_report, query
from compass.routing.domain import Coordinate


def raw():
    return {"type": "relation", "members": [
        {"role": "from", "type": "node", "ref": 1},
        {"role": "to", "type": "node", "ref": 3},
    ], "road_ways": [{"id": 10, "nodes": [1, 2, 3], "geometry": [
        {"lat": 45, "lon": 9}, {"lat": 45, "lon": 9.001}, {"lat": 45, "lon": 9.002},
    ]}]}


def matched():
    shape = encode_polyline6(tuple(Coordinate(p["lat"], p["lon"])
                                   for p in raw()["road_ways"][0]["geometry"]))
    return {"shape": shape, "edges": [
        {"way_id": 10, "forward": True, "id": 100, "length": .157},
    ]}


def test_from_to_orders_geometry_and_reversal():
    source = source_path(raw())
    assert source.state == "source_path"
    assert source.evidence["forward"]
    reverse = raw()
    reverse["members"].reverse()
    # Member array order must not determine direction.
    assert source_path(reverse) == source
    reverse["members"][0]["role"] = "from"
    reverse["members"][1]["role"] = "to"
    result = source_path(reverse)
    assert not result.evidence["forward"]
    assert result.evidence["shape"][0]["lon"] == 9.002


def test_camera_facing_direction_is_never_a_driving_heading():
    assert source_path({"type": "node", "tags": {"direction": "90"}}).state == "unknown_direction"


def test_parallel_way_and_missing_topology_are_not_guessed():
    data = raw()
    data["road_ways"].append({**data["road_ways"][0], "id": 11})
    assert source_path(data).state == "ambiguous"
    data["road_ways"] = []
    assert source_path(data).state == "ambiguous"


@pytest.mark.parametrize("change", [
    {"way_id": 11}, {"forward": False}, {"forward": None},
    {"id": None}, {"length": -1}, {"length": float("nan")},
])
def test_graph_mismatch_or_bad_edge_cannot_be_matched(change):
    data = matched()
    data["edges"][0].update(change)
    assert validate_trace(data, source_path(raw())).state != "matched"


def test_match_keeps_directed_ids_and_network_length():
    result = validate_trace(matched(), source_path(raw()))
    assert result.state == "matched"
    assert result.evidence["edge_ids"] == ["100"]
    assert result.evidence["length_meters"] == 157


def test_adapter_uses_exact_edge_walk_and_never_nearest_road_fallback():
    async def check():
        calls = []
        def handle(request):
            calls.append(request)
            body = json.loads(request.content)
            assert body["shape_match"] == "edge_walk"
            assert len(body["shape"]) == 3
            return httpx.Response(200, json=matched())
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            assert (await associate(raw(), client, Settings())).state == "matched"
            result = await associate({"type": "node"}, client, Settings())
            assert result.state == "unknown_direction"
        assert len(calls) == 1
    asyncio.run(check())


def test_graph_identity_requires_nonzero_versioned_tileset():
    async def check():
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"version": "3.8.3", "tileset_last_modified": 123})
        )) as client:
            assert await graph_identity(client, Settings()) == "3.8.3:123"
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"version": "3.8.3"})
        )) as client:
            with pytest.raises(ValueError):
                await graph_identity(client, Settings())
    asyncio.run(check())


def test_import_retains_parent_way_topology_without_proximity_join():
    data = raw()
    relation = {"type": "relation", "id": 7, "members": data["members"],
                "tags": {"type": "enforcement", "enforcement": "maxspeed"}}
    elements = [relation, {"type": "way", "id": 10, "nodes": [1, 2, 3],
                          "tags": {"highway": "primary"}}]
    elements += [{"type": "node", "id": i + 1, **point}
                 for i, point in enumerate(data["road_ways"][0]["geometry"])]
    report = parse_report(json.dumps({"elements": elements}).encode())
    assert not report.rejected
    assert source_path(report.controls[0]["raw"]).state == "source_path"
    assert "way(bn.members)" in query("IT")


def test_batch_discards_all_results_if_graph_changes(monkeypatch):
    from unittest.mock import MagicMock

    from compass.enforcement import association_job
    from compass.enforcement.association import Association

    sessions = MagicMock()
    reader = sessions.return_value.__enter__.return_value
    reader.execute.return_value.scalar_one.return_value = 0
    reader.execute.return_value.mappings.return_value.all.return_value = [
        {"source_key": "osm:relation:7", "raw": raw(), "import_id": 1},
    ]
    identities = iter(["3.8.3:123", "3.8.3:456"])

    async def identity(*_):
        return next(identities)

    async def match(*_):
        return Association("matched", "fixture", {})

    monkeypatch.setattr(association_job, "SessionLocal", sessions)
    monkeypatch.setattr(association_job, "graph_identity", identity)
    monkeypatch.setattr(association_job, "associate", match)
    with pytest.raises(ValueError, match="graph changed"):
        asyncio.run(association_job.run(Settings(), 10))
    sessions.begin.assert_not_called()


def test_valid_edge_ids_do_not_override_wrong_matched_endpoints():
    data = matched()
    data["shape"] = encode_polyline6((Coordinate(46, 9), Coordinate(46, 9.002)))
    result = validate_trace(data, source_path(raw()))
    assert result.state == "ambiguous"
    assert result.reason == "matched_endpoint_mismatch"


@pytest.mark.parametrize("graph,import_id,legacy,expected", [
    ("3.8.3:123", 1, False, "matched"),
    ("3.8.3:123", 1, True, "missing_source_geometry"),
    ("3.8.3:456", 1, False, "stale"),
    ("3.8.3:123", 2, False, "stale"),
    (None, None, False, "unmatched"),
])
def test_read_api_never_exposes_old_graph_or_source_evidence(
    monkeypatch, graph, import_id, legacy, expected,
):
    from unittest.mock import MagicMock

    from fastapi import FastAPI, Request, Response

    from compass.api.enforcement import association_evidence
    from compass.enforcement import association

    async def identity(*_):
        return "3.8.3:123"

    monkeypatch.setattr(association, "graph_identity", identity)
    app = FastAPI()
    app.state.routing_http_client = object()
    session = MagicMock()
    session.execute.return_value.mappings.return_value.one_or_none.return_value = {
        "raw": {"type": "relation"} if legacy else raw(),
        "current_import_id": 1, "import_id": import_id, "graph_identity": graph,
        "state": "matched", "reason": "fixture", "evidence": {"edge_ids": ["100"]},
    }
    result = asyncio.run(association_evidence(
        "osm:relation:7", Request({"type": "http", "app": app}), Response(), session,
    ))
    assert result.state == expected
    assert bool(result.evidence) == (expected == "matched")
    assert result.alert_eligible is False


def test_legacy_import_is_distinct_from_attempted_but_ambiguous_topology():
    data = raw()
    del data["road_ways"]
    result = source_path(data)
    assert result.state == "missing_source_geometry"
    assert result.reason == "enriched_import_required"
    assert not result.evidence


def test_batch_requires_enriched_import_before_persisting_associations(monkeypatch):
    from unittest.mock import AsyncMock, MagicMock

    from compass.enforcement import association_job

    sessions = MagicMock()
    sessions.return_value.__enter__.return_value.execute.return_value.scalar_one.return_value = 100
    match = AsyncMock()
    monkeypatch.setattr(association_job, "SessionLocal", sessions)
    monkeypatch.setattr(association_job, "graph_identity", AsyncMock(return_value="3.8.3:123"))
    monkeypatch.setattr(association_job, "associate", match)
    result = asyncio.run(association_job.run(Settings(), 100))
    assert result["status"] == "source_geometry_required"
    assert result["processed"] == 0
    assert result["missing_source_geometry"] == 100
    match.assert_not_called()
    sessions.begin.assert_not_called()
