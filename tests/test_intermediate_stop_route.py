import asyncio
from datetime import datetime

import httpx

from compass.api.main import app
from compass.config import get_api_settings, get_settings
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    Coordinate,
    Maneuver,
    RouteLeg,
    WaypointRoute,
    WaypointRouteRequest,
)
from compass.traffic.dependencies import get_traffic_route_refresher
from compass.traffic.route_refresh import DisabledTrafficRouteRefresher


class _WaypointProvider:
    def __init__(self) -> None:
        self.request: WaypointRouteRequest | None = None

    async def route_with_waypoints(self, request: WaypointRouteRequest) -> WaypointRoute:
        self.request = request
        legs = tuple(
            RouteLeg(
                distance_meters=distance,
                duration_seconds=duration,
                encoded_polyline=shape,
                maneuvers=(Maneuver(1, instruction, distance, duration, 0, 1),),
            )
            for distance, duration, shape, instruction in (
                (12_000.0, 900.0, "leg-one", "Raggiungi la tappa"),
                (18_000.0, 1_200.0, "leg-two", "Prosegui alla destinazione"),
            )
        )
        return WaypointRoute(30_000.0, 2_100.0, legs, "valhalla")


class _MultipleWaypointProvider(_WaypointProvider):
    async def route_with_waypoints(self, request: WaypointRouteRequest) -> WaypointRoute:
        self.request = request
        points = (request.origin, *request.waypoints, request.destination)
        legs = tuple(
            RouteLeg(
                distance_meters=10_000.0 + index * 1_000.0,
                duration_seconds=600.0 + index * 60.0,
                encoded_polyline=f"leg-{index + 1}",
                maneuvers=(
                    Maneuver(
                        1,
                        f"Tratta {index + 1}",
                        10_000.0 + index * 1_000.0,
                        600.0 + index * 60.0,
                        0,
                        1,
                    ),
                ),
            )
            for index in range(len(points) - 1)
        )
        return WaypointRoute(
            sum(leg.distance_meters for leg in legs),
            sum(leg.duration_seconds for leg in legs),
            legs,
            "valhalla",
        )


def test_intermediate_stop_route_has_two_legs_and_no_dwell() -> None:
    provider = _WaypointProvider()

    async def override_provider() -> _WaypointProvider:
        return provider

    async def override_settings():
        return get_settings().model_copy(
            update={"api_auth_enabled": False, "traffic_enabled": False}
        )

    async def override_refresher() -> DisabledTrafficRouteRefresher:
        return DisabledTrafficRouteRefresher()

    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    app.dependency_overrides[get_traffic_route_refresher] = override_refresher

    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/routes/with-intermediate-stop",
                json={
                    "origin": {"latitude": 45.0, "longitude": 10.0},
                    "intermediate_stop": {"latitude": 45.2, "longitude": 10.3},
                    "destination": {"latitude": 45.4, "longitude": 10.6},
                    "departure_at": "2026-09-09T10:00:00+02:00",
                },
            )

    try:
        response = asyncio.run(request())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert [leg["kind"] for leg in body["legs"]] == [
        "origin_to_intermediate_stop",
        "intermediate_stop_to_destination",
    ]
    assert body["navigation"]["refueling_stop_count"] == 0
    assert body["navigation"]["total_refueling_dwell_seconds"] == 0
    assert provider.request is not None
    assert provider.request.waypoints == (Coordinate(45.2, 10.3),)
    assert provider.request.departure_at == datetime.fromisoformat(
        "2026-09-09T10:00:00+02:00"
    )


def test_multiple_intermediate_stops_preserve_order_and_have_no_dwell() -> None:
    provider = _MultipleWaypointProvider()

    async def override_provider() -> _MultipleWaypointProvider:
        return provider

    async def override_settings():
        return get_settings().model_copy(
            update={"api_auth_enabled": False, "traffic_enabled": False}
        )

    async def override_refresher() -> DisabledTrafficRouteRefresher:
        return DisabledTrafficRouteRefresher()

    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    app.dependency_overrides[get_traffic_route_refresher] = override_refresher

    stops = [
        {"latitude": 45.1, "longitude": 10.2},
        {"latitude": 45.3, "longitude": 10.4},
        {"latitude": 45.5, "longitude": 10.6},
    ]

    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/routes/with-intermediate-stops",
                json={
                    "origin": {"latitude": 45.0, "longitude": 10.0},
                    "intermediate_stops": stops,
                    "destination": {"latitude": 45.7, "longitude": 10.8},
                },
            )

    try:
        response = asyncio.run(request())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["intermediate_stops"] == stops
    assert [leg["sequence"] for leg in body["legs"]] == [1, 2, 3, 4]
    assert [leg["kind"] for leg in body["legs"]] == [
        "origin_to_intermediate_stop",
        "intermediate_stop_to_intermediate_stop",
        "intermediate_stop_to_intermediate_stop",
        "intermediate_stop_to_destination",
    ]
    assert body["navigation"]["refueling_stop_count"] == 0
    assert body["navigation"]["total_refueling_dwell_seconds"] == 0
    assert provider.request is not None
    assert provider.request.waypoints == tuple(
        Coordinate(stop["latitude"], stop["longitude"]) for stop in stops
    )
