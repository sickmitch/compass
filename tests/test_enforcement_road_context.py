import asyncio
import json

import httpx
import pytest
from pydantic import ValidationError

from compass.config import Settings
from compass.enforcement.identity import physical_control_key
from compass.enforcement.road_context import (
    RoadContextRequest,
    RoadContextUnavailable,
    decode_context,
    match_context,
)


def trace():
    return RoadContextRequest(points=[
        {"latitude": 45, "longitude": 9 + i * .001,
         "elapsed_seconds": i * 5, "accuracy_meters": 10} for i in range(3)
    ])


def response():
    return {"edges": [{"id": 12345678901234567, "way_id": 456, "forward": True,
                       "begin_heading": 90, "end_heading": 90, "speed_limit": 50}],
            "matched_points": [
                {"lat": p.latitude, "lon": p.longitude, "type": "matched",
                 "edge_index": 0, "distance_from_trace_point": 1} for p in trace().points
            ]}


def test_matched_sequence_keeps_directed_identity_but_does_not_certify_carriageway():
    result = decode_context(response(), trace())
    assert result.state == "candidate"
    assert result.edges[0].edge_id == "12345678901234567"
    assert result.edges[0].forward is True
    assert result.current_edge_index == 0
    assert not result.alert_eligible
    assert not result.persistable


@pytest.mark.parametrize("change", [
    {"begin_route_discontinuity": True}, {"end_route_discontinuity": True},
    {"type": "unmatched"}, {"type": "interpolated"}, {"edge_index": 4},
    {"distance_from_trace_point": 26},
])
def test_uncertain_traces_cannot_emit_edge_matches(change):
    data = response()
    data["matched_points"][1].update(change)
    result = decode_context(data, trace())
    assert result.state == "uncertain"
    assert result.edges == []


@pytest.mark.parametrize("change", [{"id": None}, {"way_id": 0}, {"begin_heading": None}])
def test_missing_graph_identity_is_uncertain(change):
    data = response()
    data["edges"][0].update(change)
    assert decode_context(data, trace()).state == "uncertain"


@pytest.mark.parametrize("change", [
    {"elapsed_seconds": 0}, {"elapsed_seconds": 20}, {"latitude": 49},
    {"accuracy_meters": float("nan")},
])
def test_request_rejects_gaps_jumps_and_invalid_gps(change):
    data = trace().model_dump()
    data["points"][1].update(change)
    with pytest.raises(ValidationError):
        RoadContextRequest.model_validate(data)


def test_stationary_or_inaccurate_trace_never_calls_valhalla():
    async def check():
        def forbidden(_):
            pytest.fail("Valhalla should not be called")
        async with httpx.AsyncClient(transport=httpx.MockTransport(forbidden)) as client:
            data = trace().model_dump()
            for point in data["points"]:
                point["longitude"] = 9
            result = await match_context(RoadContextRequest(**data), client, Settings())
            assert result.state == "insufficient_movement"
            data["points"][0]["accuracy_meters"] = 60
            result = await match_context(RoadContextRequest(**data), client, Settings())
            assert result.reason == "poor_gps_accuracy"
    asyncio.run(check())


def test_adapter_posts_one_bounded_trace_without_database_or_provider_calls():
    async def check():
        calls = []
        def handle(request):
            calls.append(request)
            body = json.loads(request.content)
            assert body["shape_match"] == "map_snap"
            assert len(body["shape"]) == 3
            assert body["trace_options"]["search_radius"] == 40
            assert "matched.edge_index" in body["filters"]["attributes"]
            return httpx.Response(200, json=response())
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            assert (await match_context(trace(), client, Settings())).state == "candidate"
        assert len(calls) == 1
    asyncio.run(check())


@pytest.mark.parametrize("status,body", [(503, {}), (200, []), (200, {"edges": [None]})])
def test_provider_failures_are_controlled(status, body):
    async def check():
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(status, json=body)
        )) as client:
            with pytest.raises(RoadContextUnavailable):
                await match_context(trace(), client, Settings())
    asyncio.run(check())


def test_alias_requires_single_explicit_device_not_proximity_or_from_member():
    raw = {"type": "relation", "members": [{"type": "node", "ref": 1, "role": "device"}]}
    assert physical_control_key("osm:relation:2", "fixed", raw) == "osm:node:1"
    assert physical_control_key("osm:relation:2", "average_speed", raw) == "osm:relation:2"
    raw["members"][0]["role"] = "from"
    assert physical_control_key("osm:relation:2", "fixed", raw) == "osm:relation:2"
    raw["members"][0]["role"] = "device"
    raw["members"].append({"type": "node", "ref": 3, "role": "device"})
    assert physical_control_key("osm:relation:2", "fixed", raw) == "osm:relation:2"


def test_http_contract_and_provider_error_do_not_expose_raw_errors():
    from fastapi import FastAPI

    from compass.api.enforcement import router

    async def check():
        app = FastAPI()
        app.include_router(router)
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=response())
        )) as provider, httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test",
        ) as client:
            app.state.routing_http_client = provider
            result = await client.post(
                "/api/v1/enforcement/road-context", json=trace().model_dump(),
            )
            assert result.status_code == 200
            assert result.headers["cache-control"] == "no-store"
            assert result.json()["alert_eligible"] is False
            bad = await client.post("/api/v1/enforcement/road-context", json={"points": []})
            assert bad.status_code == 422
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(503, text="private provider failure")
        )) as provider, httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test",
        ) as client:
            app.state.routing_http_client = provider
            result = await client.post(
                "/api/v1/enforcement/road-context", json=trace().model_dump(),
            )
            assert result.status_code == 503
            assert result.json()["code"] == "road_context_unavailable"
            assert "private provider failure" not in result.text
    asyncio.run(check())
