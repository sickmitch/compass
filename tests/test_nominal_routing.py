"""Nominal costs remain the single source of travel duration for every route."""

import asyncio
import json
from copy import deepcopy
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest

from compass.navigation.domain import build_navigation_timing
from compass.routing.domain import Coordinate, RouteRequest, WaypointRouteRequest
from compass.routing.valhalla import ValhallaRoutingAdapter


@pytest.mark.parametrize("waypoints", [False, True])
@pytest.mark.parametrize("departure", [None, datetime(2027, 1, 1, 8, tzinfo=UTC)])
def test_one_nominal_route_request_preserves_times_and_dwell(waypoints, departure):
    fixture = json.loads(Path("tests/fixtures/valhalla_route_response.json").read_text())
    if waypoints:
        fixture["trip"]["legs"].append(deepcopy(fixture["trip"]["legs"][0]))
        fixture["trip"]["summary"]["time"] *= 2
    calls = []

    def handler(request):
        calls.append(request)
        payload = json.loads(request.content)
        assert request.url.path == "/route"
        assert "date_time" not in payload
        assert "prioritize_bidirectional" not in payload
        assert payload["costing_options"]["auto"]["speed_types"] == [
            "constrained", "freeflow",
        ]
        return httpx.Response(200, json=fixture)

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = ValhallaRoutingAdapter(
                base_url="http://engine.test", connect_timeout_seconds=1,
                read_timeout_seconds=1, user_agent="test", client=client,
            )
            args = dict(origin=Coordinate(45.4, 9.1), destination=Coordinate(45.5, 9.3),
                        departure_at=departure)
            if waypoints:
                return await adapter.route_with_waypoints(WaypointRouteRequest(
                    **args, waypoints=(Coordinate(45.45, 9.2),),
                ))
            return await adapter.route(RouteRequest(**args))

    result = asyncio.run(run())
    assert len(calls) == 1
    assert result.duration_seconds == fixture["trip"]["summary"]["time"]
    timing = build_navigation_timing(
        encoded_polylines=("shape",), driving_duration_seconds=result.duration_seconds,
        fuel_stop_ids=("123",) if waypoints else (), departure_at=departure,
    )
    assert timing.driving_duration_seconds == result.duration_seconds
    assert timing.total_trip_duration_seconds == (
        result.duration_seconds + (1200 if waypoints else 0)
    )
    if departure:
        assert (timing.trip_arrival_at - departure).total_seconds() == (
            timing.total_trip_duration_seconds
        )
