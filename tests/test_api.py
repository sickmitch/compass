import asyncio
from collections.abc import AsyncIterator
from datetime import UTC, datetime

import httpx
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from compass.api.main import app
from compass.candidates.domain import (
    CorridorCandidateResult,
    CorridorRadius,
    SpatialCandidate,
    SpatialPruningMetrics,
)
from compass.config import Settings, get_api_settings, get_settings
from compass.db import get_session
from compass.freshness.domain import DataFreshnessReport, DataSourceFreshness
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    ManeuverSign,
    ManeuverSignElement,
    RouteOriginDirection,
    RouteRequest,
    RouteSpeedLimit,
    RoutingUnavailableError,
)


def _get(path: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path)

    return asyncio.run(request())


def _get_with_auth(path: str, username: str, password: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path, auth=(username, password))

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
                    sign=ManeuverSign(
                        exit_number_elements=(ManeuverSignElement("1", 2),),
                        exit_branch_elements=(ManeuverSignElement("A1"),),
                        exit_toward_elements=(ManeuverSignElement("Bologna"),),
                    ),
                    roundabout_exit_count=2,
                ),
            ),
            provider="valhalla",
            speed_limits=(RouteSpeedLimit(0, 10, 50),),
            speed_limit_source="valhalla_graph",
        )


def test_liveness_does_not_claim_dependency_readiness() -> None:
    response = _get("/health/live")
    assert response.status_code == 200
    assert response.json()["database"] == "not_checked"
    assert response.json()["routing"] == "not_checked"


def test_api_authentication_check_reports_disabled_default() -> None:
    response = _get("/api/v1/auth/check")

    assert response.status_code == 200
    assert response.json() == {
        "authenticated": True,
        "auth_enabled": False,
    }


def test_enabled_api_authentication_rejects_missing_and_wrong_credentials() -> None:
    async def override_settings() -> Settings:
        return Settings(
            _env_file=None,
            api_auth_enabled=True,
            api_auth_username="mobile-user",
            api_auth_password="test-password",
        )

    app.dependency_overrides[get_api_settings] = override_settings
    try:
        missing = _get("/api/v1/auth/check")
        wrong = _get_with_auth("/api/v1/auth/check", "mobile-user", "wrong")
    finally:
        app.dependency_overrides.clear()

    for response in (missing, wrong):
        assert response.status_code == 401
        assert response.headers["www-authenticate"].startswith("Basic")
        assert response.json()["code"] == "invalid_credentials"


def test_enabled_api_authentication_returns_stable_error_for_malformed_basic_header() -> None:
    async def override_settings() -> Settings:
        return Settings(
            _env_file=None,
            api_auth_enabled=True,
            api_auth_username="mobile-user",
            api_auth_password="test-password",
        )

    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(
                "/api/v1/auth/check",
                headers={"Authorization": "Basic not-base64"},
            )

    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = asyncio.run(request())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 401
    assert response.json()["code"] == "invalid_credentials"


def test_enabled_api_authentication_accepts_credentials_and_protects_api_routers() -> None:
    async def override_settings() -> Settings:
        return Settings(
            _env_file=None,
            api_auth_enabled=True,
            api_auth_username="mobile-user",
            api_auth_password="test-password",
        )

    app.dependency_overrides[get_api_settings] = override_settings
    try:
        unauthenticated = _get("/api/v1/places/search?q=Milano")
        authenticated = _get_with_auth(
            "/api/v1/auth/check",
            "mobile-user",
            "test-password",
        )
        public_health = _get("/health/live")
    finally:
        app.dependency_overrides.clear()

    assert unauthenticated.status_code == 401
    assert authenticated.status_code == 200
    assert authenticated.json() == {
        "authenticated": True,
        "auth_enabled": True,
    }
    assert public_health.status_code == 200


def test_openapi_documents_basic_authentication_without_protecting_health() -> None:
    schema = app.openapi()

    assert schema["components"]["securitySchemes"]["OptionalHTTPBasic"] == {
        "type": "http",
        "scheme": "basic",
    }
    route_operation = schema["paths"]["/api/v1/routes"]["post"]
    assert route_operation["security"] == [{"OptionalHTTPBasic": []}]
    assert "401" in route_operation["responses"]
    assert "security" not in schema["paths"]["/health/live"]["get"]


def _freshness_report(state: str = "ready") -> DataFreshnessReport:
    now = datetime.now(UTC)
    source = DataSourceFreshness("fixture", "fresh", now, now, 0, 3600)
    return DataFreshnessReport(
        evaluated_at=now,
        overall_state=state,
        mimit=source,
        osm=source,
        reconciliation=source,
    )


def test_readiness_checks_database(monkeypatch: pytest.MonkeyPatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    provider = FakeRoutingProvider()

    async def override_session() -> AsyncIterator[Session]:
        with Session(engine) as session:
            yield session

    async def override_provider() -> FakeRoutingProvider:
        return provider

    async def override_settings():
        return get_settings().model_copy(
            update={
            }
        )

    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    monkeypatch.setattr(
        "compass.api.main.load_data_freshness", lambda *args, **kwargs: _freshness_report()
    )
    try:
        response = _get("/health/ready")
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

    assert response.status_code == 200
    assert response.json()["database"] == "ready"
    assert response.json()["routing"] == "ready"
    assert response.json()["data"] == "ready"


def test_readiness_reports_routing_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")

    async def override_session() -> AsyncIterator[Session]:
        with Session(engine) as session:
            yield session

    async def override_provider() -> FakeRoutingProvider:
        return FakeRoutingProvider(ready=False)

    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    monkeypatch.setattr(
        "compass.api.main.load_data_freshness", lambda *args, **kwargs: _freshness_report()
    )
    try:
        response = _get("/health/ready")
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

    assert response.status_code == 503
    assert response.json()["status"] == "not_ready"
    assert response.json()["database"] == "ready"
    assert response.json()["routing"] == "unavailable"


@pytest.mark.parametrize(
    ("data_state", "expected_status", "expected_service_status"),
    [
        ("degraded", 200, "ok"),
        ("unavailable", 503, "not_ready"),
    ],
)
def test_readiness_distinguishes_stale_from_missing_required_data(
    monkeypatch: pytest.MonkeyPatch,
    data_state: str,
    expected_status: int,
    expected_service_status: str,
) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")

    async def override_session() -> AsyncIterator[Session]:
        with Session(engine) as session:
            yield session

    async def override_provider() -> FakeRoutingProvider:
        return FakeRoutingProvider()

    monkeypatch.setattr(
        "compass.api.main.load_data_freshness",
        lambda *args, **kwargs: _freshness_report(data_state),
    )
    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _get("/health/ready")
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

    assert response.status_code == expected_status
    assert response.json()["status"] == expected_service_status
    assert response.json()["data"] == data_state


def test_base_route_contract_is_provider_independent() -> None:
    provider = FakeRoutingProvider()

    async def override_provider() -> FakeRoutingProvider:
        return provider

    async def override_settings() -> Settings:
        return Settings(_env_file=None)

    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = _post(
            "/api/v1/routes",
            {
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 45.4781, "longitude": 9.2271},
                "allow_highways": False,
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert provider.request is not None
    assert provider.request.allow_highways is False
    assert provider.request == RouteRequest(
        origin=Coordinate(latitude=45.4642, longitude=9.19),
        destination=Coordinate(latitude=45.4781, longitude=9.2271),
        language="it-IT",
        allow_highways=False,
    )


@pytest.mark.parametrize("costing", ["pedestrian", "bicycle"])
def test_base_route_accepts_and_forwards_active_travel_costing(costing) -> None:
    provider = FakeRoutingProvider()

    async def override_provider() -> FakeRoutingProvider:
        return provider

    async def override_settings() -> Settings:
        return Settings(_env_file=None)

    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = _post(
            "/api/v1/routes",
            {
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 45.4781, "longitude": 9.2271},
                "costing": costing,
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert provider.request is not None
    assert provider.request.costing == costing


def test_base_route_forwards_a_valid_origin_direction() -> None:
    provider = FakeRoutingProvider()

    async def override_provider() -> FakeRoutingProvider:
        return provider

    async def override_settings() -> Settings:
        return Settings(_env_file=None)

    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = _post(
            "/api/v1/routes",
            {
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 45.4781, "longitude": 9.2271},
                "origin_heading_degrees": 91.5,
                "origin_heading_tolerance_degrees": 45,
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert provider.request is not None
    assert provider.request.origin_direction == RouteOriginDirection(91.5, 45)


def test_base_route_rejects_origin_heading_tolerance_without_heading() -> None:
    response = _post(
        "/api/v1/routes",
        {
            "origin": {"latitude": 45.4642, "longitude": 9.19},
            "destination": {"latitude": 45.4781, "longitude": 9.2271},
            "origin_heading_tolerance_degrees": 45,
        },
    )

    assert response.status_code == 422


def test_base_route_propagates_scheduled_departure() -> None:
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
                "departure_at": "2026-08-30T10:00:00+02:00",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert provider.request is not None
    assert provider.request.departure_at == datetime.fromisoformat(
        "2026-08-30T10:00:00+02:00"
    )


def test_base_route_rejects_naive_scheduled_departure() -> None:
    response = _post(
        "/api/v1/routes",
        {
            "origin": {"latitude": 45.4642, "longitude": 9.19},
            "destination": {"latitude": 45.4781, "longitude": 9.2271},
            "departure_at": "2026-08-30T10:00:00",
        },
    )

    assert response.status_code == 422
    assert response.json()["code"] == "invalid_request"


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


def test_corridor_candidates_exposes_policy_and_prefilter_semantics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = FakeRoutingProvider()
    expected_route = asyncio.run(
        provider.route(
            RouteRequest(
                origin=Coordinate(45.4642, 9.19),
                destination=Coordinate(44.4949, 11.3426),
            )
        )
    )

    async def override_session() -> AsyncIterator[object]:
        yield object()

    async def override_provider() -> FakeRoutingProvider:
        return provider

    async def fake_find(*args: object, **kwargs: object) -> CorridorCandidateResult:
        assert kwargs["max_route_geometry_points"] == 200_000
        return CorridorCandidateResult(
            base_route=expected_route,
            corridor=CorridorRadius(300, 0.20, 60, 50, "maximum"),
            metrics=SpatialPruningMetrics(
                active_station_count=1512,
                active_station_with_location_count=1505,
                excluded_missing_location_count=7,
                corridor_candidate_count=83,
                returned_candidate_count=1,
                pruned_with_location_count=1422,
                reduction_ratio=1 - 83 / 1505,
                candidate_limit_applied=False,
            ),
            candidates=(
                SpatialCandidate(
                    station_id=10,
                    mimit_station_id="1001",
                    name="Milano Metano",
                    municipality="Milano",
                    province="MI",
                    latitude=45.4642,
                    longitude=9.19,
                    straight_line_distance_to_route_meters=12.5,
                    route_fraction=0.1,
                ),
            ),
        )

    monkeypatch.setattr("compass.api.routes.find_corridor_candidates", fake_find)
    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _post(
            "/api/v1/cng/corridor-candidates",
            {
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 44.4949, "longitude": 11.3426},
                "effective_cng_range_km": 300,
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["stage"] == "spatial_pruning"
    assert body["corridor"] == {
        "effective_cng_range_km": 300.0,
        "range_fraction": 0.2,
        "uncapped_radius_km": 60.0,
        "radius_km": 50.0,
        "cap_applied": "maximum",
    }
    assert body["metrics"]["active_station_count"] == 1512
    assert body["metrics"]["corridor_candidate_count"] == 83
    assert body["metrics"]["routing_calls"] == 1
    assert body["candidates"] == [
        {
            "station_id": 10,
            "mimit_station_id": "1001",
            "name": "Milano Metano",
            "municipality": "Milano",
            "province": "MI",
            "latitude": 45.4642,
            "longitude": 9.19,
            "straight_line_distance_to_route_meters": 12.5,
            "route_fraction": 0.1,
        }
    ]


def test_corridor_candidates_rejects_detour_input() -> None:
    response = _post(
        "/api/v1/cng/corridor-candidates",
        {
            "origin": {"latitude": 45.4642, "longitude": 9.19},
            "destination": {"latitude": 44.4949, "longitude": 11.3426},
            "effective_cng_range_km": 300,
            "maximum_detour_minutes": 10,
        },
    )

    assert response.status_code == 422
    assert response.json()["code"] == "invalid_request"


def test_detour_candidates_requires_timezone_offset() -> None:
    response = _post(
        "/api/v1/cng/detour-candidates",
        {
            "origin": {"latitude": 45.4642, "longitude": 9.19},
            "destination": {"latitude": 44.4949, "longitude": 11.3426},
            "effective_cng_range_km": 300,
            "maximum_detour_minutes": 10,
            "departure_at": "2026-08-28T08:00:00",
        },
    )

    assert response.status_code == 422
    assert response.json() == {
        "code": "invalid_request",
        "message": "The request payload is invalid.",
    }
