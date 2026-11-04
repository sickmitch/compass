import asyncio
from copy import deepcopy
from unittest.mock import MagicMock

import httpx
import pytest
from fastapi import FastAPI

from compass.api.enforcement import router
from compass.candidates.geometry import encode_polyline6
from compass.db import get_session
from compass.enforcement.approach import candidate, unique_road
from compass.enforcement.road_context import RoadContextRequest, RoadEdge
from compass.routing.domain import Coordinate


def trace():
    return RoadContextRequest(points=[
        {"latitude": 45, "longitude": 9 + i * .0005,
         "elapsed_seconds": i * 3, "accuracy_meters": 5} for i in range(3)
    ])


def row():
    geometry = [{"lat": 45, "lon": 9 + i * .001} for i in range(6)]
    return {"source_key": "osm:relation:1", "kind": "fixed", "latitude": 45,
            "longitude": 9.0045, "speed_limit_kph": 90,
            "raw": {"type": "relation", "members": [
                {"type": "node", "role": "device", "ref": 42}],
                "road_ways": [{"id": 10, "nodes": [1, 2, 3, 4, 5, 6], "geometry": geometry}]},
            "evidence": {"osm_way_id": 10, "forward": True, "from_node": 5, "to_node": 6,
                         "edge_ids": ["123"], "length_meters": 78,
                         "encoded_polyline": encode_polyline6((
                             Coordinate(45, 9.004), Coordinate(45, 9.005)))}}


def locate():
    return [{"edges": [{"edge_info": {"way_id": 10, "shape": encode_polyline6((
        Coordinate(45, 9), Coordinate(45, 9.005)))},
        "edge": {"forward": True}, "edge_id": {"value": 123}}]}]


def edge():
    return RoadEdge(edge_id="123", osm_way_id="10", forward=True,
                    begin_heading=90, end_heading=90, speed_limit_kph=90)


def test_distance_is_along_directed_parent_road_before_short_device_zone():
    result = candidate(row(), trace().points[-1], 90)
    assert 270 < result.distance_meters < 280
    assert result.physical_control_key == "osm:node:42"
    assert result.target_longitude == pytest.approx(9.0045)
    assert candidate(row(), trace().points[-1], 270) is None
    off = trace().points[-1].model_copy(update={"latitude": 45.0002})
    assert candidate(row(), off, 90) is None
    past = trace().points[-1].model_copy(update={"longitude": 9.0048})
    assert candidate(row(), past, 90) is None


def test_reverse_direction_and_tutor_entry():
    data = row()
    data["evidence"].update(forward=False, from_node=6, to_node=5)
    position = trace().points[-1].model_copy(update={"longitude": 9.0049})
    assert 30 < candidate(data, position, 270).distance_meters < 33
    data = row()
    data["kind"] = "average_speed"
    assert candidate(data, trace().points[-1], 90).target_longitude == pytest.approx(9.004)


@pytest.mark.parametrize("change", ["duplicate_node", "missing_road", "device_far"])
def test_incomplete_or_ambiguous_source_does_not_produce_alert(change):
    data = row()
    if change == "duplicate_node":
        data["raw"]["road_ways"][0]["nodes"][0] = 3
    elif change == "missing_road":
        data["raw"]["road_ways"] = []
    else:
        data["latitude"] = 45.01
    assert candidate(data, trace().points[-1], 90) is None


def test_competing_parallel_road_is_rejected_but_opposite_direction_is_not_candidate():
    data = locate()
    assert unique_road(data, trace().points[-1], 90, edge())
    opposite = deepcopy(data[0]["edges"][0])
    opposite["edge"]["forward"] = False
    opposite["edge_id"]["value"] = 124
    data[0]["edges"].append(opposite)
    assert unique_road(data, trace().points[-1], 90, edge())
    parallel = deepcopy(data[0]["edges"][0])
    parallel["edge_info"]["way_id"] = 11
    parallel["edge_info"]["shape"] = encode_polyline6((
        Coordinate(45.00005, 9), Coordinate(45.00005, 9.005)))
    data[0]["edges"].append(parallel)
    assert not unique_road(data, trace().points[-1], 90, edge())
    assert not unique_road([None], trace().points[-1], 90, edge())
    assert not unique_road(locate(), trace().points[-1], 270, edge())


@pytest.mark.parametrize("mode,expected", [
    ("ok", "approach"), ("graph_changed", "uncertain"),
    ("parallel", "uncertain"), ("unknown_direction", "uncertain"), ("empty", "none"),
])
def test_endpoint_checks_graph_trace_competitors_and_current_source(mode, expected):
    async def check():
        calls = []
        def provider(request):
            path = request.url.path
            calls.append(path)
            if path == "/status":
                return httpx.Response(200, json={"version": "3.8.3", "tileset_last_modified":
                    456 if mode == "graph_changed" and calls.count(path) == 2 else 123})
            if path == "/locate":
                data = locate()
                if mode == "parallel":
                    extra = deepcopy(data[0]["edges"][0])
                    extra["edge_info"]["way_id"] = 99
                    data[0]["edges"].append(extra)
                return httpx.Response(200, json=data)
            assert path == "/trace_attributes"
            return httpx.Response(200, json={"edges": [{"id": 123, "way_id": 10,
                "forward": None if mode == "unknown_direction" else True,
                "begin_heading": 90, "end_heading": 90}], "matched_points": [
                    {"lat": p.latitude, "lon": p.longitude, "type": "matched", "edge_index": 0,
                     "distance_from_trace_point": 1} for p in trace().points]})
        session = MagicMock()
        session.execute.return_value.mappings.return_value.all.return_value = (
            [] if mode == "empty" else [row()])
        app = FastAPI()
        app.include_router(router)
        app.dependency_overrides[get_session] = lambda: session
        async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as upstream:
            app.state.routing_http_client = upstream
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                          base_url="http://test") as client:
                result = await client.post(
                    "/api/v1/enforcement/approach", json=trace().model_dump(),
                )
                assert result.status_code == 200, result.text
                assert result.headers["cache-control"] == "no-store"
                assert result.json()["state"] == expected
        session.commit.assert_not_called()
        if session.execute.called:
            sql = str(session.execute.call_args[0][0])
            assert "a.import_id=c.import_id" in sql
            assert "a.graph_identity=:graph" in sql
            assert "ST_DWithin" in sql and "LIMIT 101" in sql
    asyncio.run(check())
