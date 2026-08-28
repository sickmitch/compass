import asyncio
from collections.abc import AsyncIterator

import httpx
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from compass.api.main import app
from compass.db import get_session
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    RouteRequest,
    RoutingUnavailableError,
)


def _get(path: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path)

    return asyncio.run(request())


def _post(path: str, payload: dict[str, object]) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(path, json=payload)

    return asyncio.run(request())


class FakeRoutingProvider:
    def __init__(self, *, ready: bool = True, unavailable: bool = False) -> None:
        self.ready = ready
        self.unavailable = unavailable
        self.request: RouteRequest | None = None

    async def is_ready(self) -> bool:
        return self.ready

    async def route(self, request: RouteRequest) -> BaseRoute:
        self.request = request
        if self.unavailable:
            raise RoutingUnavailableError("test failure")
        return BaseRoute(
            distance_meters=2500,
            duration_seconds=320,
            encoded_polyline="encoded-route",
            maneuvers=(
                Maneuver(
                    type=1,
                    instruction="Procedi su Via Roma.",
                    distance_meters=2500,
                    duration_seconds=320,
                    begin_shape_index=0,
                    end_shape_index=10,
                    street_names=("Via Roma",),
                    travel_mode="drive",
                    travel_type="car",
                ),
            ),
            provider="valhalla",
        )


def test_liveness_does_not_claim_dependency_readiness() -> None:
    response = _get("/health/live")
    assert response.status_code == 200
    assert response.json()["database"] == "not_checked"
    assert response.json()["routing"] == "not_checked"


def test_readiness_checks_database() -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    provider = FakeRoutingProvider()

    async def override_session() -> AsyncIterator[Session]:
        with Session(engine) as session:
            yield session

    async def override_provider() -> FakeRoutingProvider:
        return provider

    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _get("/health/ready")
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

    assert response.status_code == 200
    assert response.json()["database"] == "ready"
    assert response.json()["routing"] == "ready"


def test_readiness_reports_routing_unavailable() -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")

    async def override_session() -> AsyncIterator[Session]:
        with Session(engine) as session:
            yield session

    async def override_provider() -> FakeRoutingProvider:
        return FakeRoutingProvider(ready=False)

    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _get("/health/ready")
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

    assert response.status_code == 503
    assert response.json()["status"] == "not_ready"
    assert response.json()["database"] == "ready"
    assert response.json()["routing"] == "unavailable"


def test_base_route_contract_is_provider_independent() -> None:
    provider = FakeRoutingProvider()

    async def override_provider() -> FakeRoutingProvider:
        return provider

    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _post(
            "/api/v1/routes",
            {
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 45.4781, "longitude": 9.2271},
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json() == {
        "distance_meters": 2500.0,
        "duration_seconds": 320.0,
        "geometry": {"format": "polyline6", "encoded_polyline": "encoded-route"},
        "maneuvers": [
            {
                "type": 1,
                "instruction": "Procedi su Via Roma.",
                "distance_meters": 2500.0,
                "duration_seconds": 320.0,
                "begin_shape_index": 0,
                "end_shape_index": 10,
                "street_names": ["Via Roma"],
                "verbal_transition_alert_instruction": None,
                "verbal_pre_transition_instruction": None,
                "verbal_post_transition_instruction": None,
                "bearing_before": None,
                "bearing_after": None,
                "travel_mode": "drive",
                "travel_type": "car",
            }
        ],
        "provider": "valhalla",
    }
    assert provider.request == RouteRequest(
        origin=Coordinate(latitude=45.4642, longitude=9.19),
        destination=Coordinate(latitude=45.4781, longitude=9.2271),
        language="it-IT",
    )


def test_base_route_rejects_extra_input_with_machine_error() -> None:
    response = _post(
        "/api/v1/routes",
        {
            "origin": {"latitude": 45.4642, "longitude": 9.19},
            "destination": {"latitude": 45.4781, "longitude": 9.2271},
            "maximum_detour_minutes": 10,
        },
    )

    assert response.status_code == 422
    assert response.json() == {
        "code": "invalid_request",
        "message": "The request payload is invalid.",
    }


def test_base_route_hides_provider_failure_details() -> None:
    async def override_provider() -> FakeRoutingProvider:
        return FakeRoutingProvider(unavailable=True)

    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _post(
            "/api/v1/routes",
            {
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 45.4781, "longitude": 9.2271},
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 503
    assert response.json() == {
        "code": "routing_unavailable",
        "message": "The routing service is unavailable.",
    }
