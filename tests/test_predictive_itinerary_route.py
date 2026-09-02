import asyncio
from collections.abc import AsyncIterator
from datetime import datetime

import httpx
import pytest

from compass.api.main import app
from compass.db import get_session
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import Maneuver, RouteLeg, WaypointRoute, WaypointRouteRequest
from compass.stations.domain import StationRoutePoint


async def _override_session() -> AsyncIterator[object]:
    yield object()


def _post(payload: dict[str, object]) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post("/api/v1/routes/with-cng-itinerary", json=payload)

    return asyncio.run(request())


def _payload() -> dict[str, object]:
    return {
        "origin": {"latitude": 45.4642, "longitude": 9.19},
        "destination": {"latitude": 44.4949, "longitude": 11.3426},
        "mimit_station_ids": ["1001", "1002", "1003"],
        "effective_cng_range_km": 100,
        "estimated_remaining_cng_range_km": 65,
        "reserve_cng_range_km": 30,
        "departure_at": "2026-08-30T10:00:00+02:00",
    }


def _stations() -> dict[str, StationRoutePoint]:
    return {
        str(1000 + index): StationRoutePoint(
            station_id=index,
            mimit_station_id=str(1000 + index),
            name=f"Station {index}",
            municipality="Test",
            province="TS",
            latitude=45 - index / 10,
            longitude=9 + index / 10,
            is_active=True,
        )
        for index in range(1, 4)
    }


class _ItineraryProvider:
    def __init__(
        self,
        distances: tuple[float, ...] = (20_000, 60_000, 60_000, 70_000),
        *,
        summary_rounding_delta: float = 0,
    ):
        self.distances = distances
        self.summary_rounding_delta = summary_rounding_delta
        self.request: WaypointRouteRequest | None = None

    async def route_with_waypoints(self, request: WaypointRouteRequest) -> WaypointRoute:
        self.request = request
        legs = tuple(
            RouteLeg(
                distance_meters=distance,
                duration_seconds=distance / 25,
                encoded_polyline=f"leg-{index}",
                maneuvers=(
                    Maneuver(
                        type=1,
                        instruction=f"Leg {index}",
                        distance_meters=distance,
                        duration_seconds=distance / 25,
                        begin_shape_index=0,
                        end_shape_index=1,
                    ),
                ),
            )
            for index, distance in enumerate(self.distances, start=1)
        )
        return WaypointRoute(
            distance_meters=sum(self.distances) + self.summary_rounding_delta,
            duration_seconds=sum(self.distances) / 25 + self.summary_rounding_delta,
            legs=legs,
            provider="valhalla",
        )


def test_multi_stop_route_revalidates_every_road_leg_and_reserve(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = _ItineraryProvider(summary_rounding_delta=7)
    loaded_ids: list[tuple[str, ...]] = []

    async def override_provider() -> _ItineraryProvider:
        return provider

    def fake_load(_session: object, station_ids: tuple[str, ...]):
        loaded_ids.append(station_ids)
        return _stations()

    monkeypatch.setattr("compass.api.stations.load_station_route_points", fake_load)
    app.dependency_overrides[get_session] = _override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _post(_payload())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert loaded_ids == [("1001", "1002", "1003")]
    assert [stop["mimit_station_id"] for stop in body["selected_stops"]] == [
        "1001",
        "1002",
        "1003",
    ]
    assert [leg["kind"] for leg in body["legs"]] == [
        "origin_to_cng_station",
        "cng_station_to_cng_station",
        "cng_station_to_cng_station",
        "cng_station_to_destination",
    ]
    assert [leg["reserve_margin_at_arrival_km"] for leg in body["legs"]] == [
        15,
        10,
        10,
        0,
    ]
    assert body["range_validation"] == "all_legs_preserve_reserve"
    assert body["distance_meters"] == sum(provider.distances)
    assert body["duration_seconds"] == sum(provider.distances) / 25
    assert provider.request is not None
    assert len(provider.request.waypoints) == 3
    assert provider.request.departure_at == datetime.fromisoformat(
        "2026-08-30T10:00:00+02:00"
    )


def test_multi_stop_route_rejects_provider_leg_that_consumes_the_reserve(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = _ItineraryProvider((36_000, 60_000, 60_000, 54_000))

    async def override_provider() -> _ItineraryProvider:
        return provider

    monkeypatch.setattr(
        "compass.api.stations.load_station_route_points",
        lambda _session, _ids: _stations(),
    )
    app.dependency_overrides[get_session] = _override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _post(_payload())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 409
    assert response.json() == {
        "code": "cng_itinerary_out_of_range",
        "message": "At least one routed leg cannot preserve the requested CNG reserve.",
    }


@pytest.mark.parametrize(
    "change",
    [
        {"mimit_station_ids": ["1001", "1001"]},
        {"mimit_station_ids": [str(index) for index in range(33)]},
        {"mimit_station_ids": ["not-a-number"]},
        {"estimated_remaining_cng_range_km": 101},
        {"reserve_cng_range_km": 65},
    ],
)
def test_multi_stop_route_request_rejects_invalid_itineraries(
    change: dict[str, object],
) -> None:
    payload = _payload()
    payload.update(change)

    response = _post(payload)

    assert response.status_code == 422
    assert response.json() == {
        "code": "invalid_request",
        "message": "The request payload is invalid.",
    }


def test_multi_stop_route_reports_missing_station(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "compass.api.stations.load_station_route_points",
        lambda _session, _ids: {"1001": _stations()["1001"]},
    )
    app.dependency_overrides[get_session] = _override_session
    try:
        response = _post(_payload())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 404
    assert response.json()["code"] == "station_not_found"
