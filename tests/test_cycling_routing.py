"""Mode contract and traffic isolation; no live routing or network calls."""

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from pydantic import ValidationError

from compass.api.routes import CorridorCandidatesRequest
from compass.api.stations import RouteWithCngItineraryRequest, RouteWithCngStopRequest
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    MatrixRequest,
    RouteRequest,
    WaypointRoute,
    WaypointRouteRequest,
)
from compass.routing.valhalla import ValhallaRoutingAdapter
from compass.traffic.routing import refresh_base_route_traffic, refresh_waypoint_route_traffic


@pytest.mark.parametrize("costing", ["pedestrian", "bicycle"])
def test_active_travel_never_refreshes_automotive_traffic(costing):
    class UnexpectedTraffic:
        async def refresh(self, **kwargs):
            raise AssertionError("active travel must not request a traffic overlay")

    origin, destination = Coordinate(45, 9), Coordinate(45.1, 9.1)
    base = BaseRoute(1000, 300, "shape", (), "valhalla")
    waypoint = WaypointRoute(1200, 400, (), "valhalla")
    common = dict(
        provider=None,
        refresher=UnexpectedTraffic(),
        current_departure_tolerance_seconds=300,
    )
    assert asyncio.run(refresh_base_route_traffic(
        route=base, request=RouteRequest(origin, destination, costing=costing), **common,
    )) is base
    assert asyncio.run(refresh_waypoint_route_traffic(
        route=waypoint,
        request=WaypointRouteRequest(origin, destination, (Coordinate(45.05, 9.05),), costing),
        **common,
    )) is waypoint


@pytest.mark.parametrize("schema", [
    CorridorCandidatesRequest, RouteWithCngStopRequest, RouteWithCngItineraryRequest,
])
def test_cng_contract_rejects_bicycle_costing(schema):
    with pytest.raises(ValidationError) as error:
        schema.model_validate({
            "origin": {"latitude": 45, "longitude": 9},
            "destination": {"latitude": 45.1, "longitude": 9.1},
            "costing": "bicycle",
        })
    assert any(item["loc"] == ("costing",) for item in error.value.errors())


def test_valhalla_bicycle_waypoints_and_matrix_keep_mode_without_auto_options():
    fixture_path = Path(__file__).parent / "fixtures/valhalla_route_response.json"
    fixture = json.loads(fixture_path.read_text())
    fixture["trip"]["legs"] *= 2
    payloads = []

    def handler(request):
        payload = json.loads(request.content)
        payloads.append(payload)
        assert payload["costing"] == "bicycle"
        assert "costing_options" not in payload
        assert "date_time" not in payload
        if request.url.path == "/route":
            assert len(payload["locations"]) == 3
            return httpx.Response(200, json=fixture)
        assert request.url.path == "/sources_to_targets"
        return httpx.Response(200, json={
            "sources_to_targets": [[{
                "from_index": 0, "to_index": 0, "distance": 1.2, "time": 300,
            }]],
            "units": "kilometers",
            "algorithm": "costmatrix",
        })

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = ValhallaRoutingAdapter(
                base_url="http://valhalla.test/", connect_timeout_seconds=1,
                read_timeout_seconds=2, user_agent="test", client=client,
                traffic_aware=True, speed_limits_enabled=True,
            )
            origin, destination = Coordinate(45, 9), Coordinate(45.1, 9.1)
            route = await adapter.route_with_waypoints(WaypointRouteRequest(
                origin, destination, (Coordinate(45.05, 9.05),),
                costing="bicycle", allow_highways=False,
            ))
            assert len(route.legs) == 2
            assert not route.traffic_aware
            assert all(not leg.speed_limits for leg in route.legs)
            matrix = await adapter.matrix(MatrixRequest(
                (origin,), (destination,), costing="bicycle", allow_highways=False,
            ))
            assert matrix.costs[0][0].duration_seconds == 300
    asyncio.run(run())
    assert len(payloads) == 2
