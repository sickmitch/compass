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
from compass.db import get_session
from compass.detours.domain import (
    NetworkCostBasis,
    NetworkDetourResult,
    NetworkEvaluationMetrics,
    calculate_detour_candidate,
)
from compass.freshness.domain import DataFreshnessReport, DataSourceFreshness
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    MatrixCost,
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

    assert response.status_code == 200
    assert response.json()["database"] == "ready"
    assert response.json()["routing"] == "ready"
    assert response.json()["data"] == "ready"
    assert response.json()["traffic"] == "not_configured"


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


def test_detour_candidates_exposes_network_costs_eta_and_no_traffic_state(
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
    station = SpatialCandidate(
        station_id=10,
        mimit_station_id="1001",
        name="Milano Metano",
        municipality="Milano",
        province="MI",
        latitude=45.4,
        longitude=9.3,
        straight_line_distance_to_route_meters=12.5,
        route_fraction=0.1,
    )
    spatial = CorridorCandidateResult(
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
        candidates=(station,),
    )

    async def override_session() -> AsyncIterator[object]:
        yield object()

    async def override_provider() -> FakeRoutingProvider:
        return provider

    async def fake_evaluate(*args: object, **kwargs: object) -> NetworkDetourResult:
        domain_request = args[2]
        assert domain_request.maximum_detour_seconds == 600  # type: ignore[attr-defined]
        assert domain_request.departure_at.isoformat() == (  # type: ignore[attr-defined]
            "2026-08-28T08:00:00+02:00"
        )
        assert kwargs["detour_policy"].matrix_batch_size == 40
        departure_at = domain_request.departure_at  # type: ignore[attr-defined]
        candidate = calculate_detour_candidate(
            station=station,
            base_route=expected_route,
            previous_to_station=MatrixCost(1_200, 120),
            station_to_destination=MatrixCost(1_600, 260),
            departure_at=departure_at,
        )
        return NetworkDetourResult(
            spatial_result=spatial,
            maximum_detour_seconds=600,
            departure_at=departure_at,
            cost_basis=NetworkCostBasis(),
            metrics=NetworkEvaluationMetrics(
                spatial_candidate_count=83,
                matrix_candidate_count=1,
                reachable_candidate_count=1,
                unreachable_candidate_count=0,
                eligible_candidate_count=1,
                excluded_by_detour_count=0,
                matrix_batch_size=40,
                matrix_calls=2,
                matrix_fallback_splits=0,
                matrix_location_failures=0,
            ),
            candidates=(candidate,),
        )

    monkeypatch.setattr("compass.api.routes.evaluate_cng_detours", fake_evaluate)
    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    try:
        response = _post(
            "/api/v1/cng/detour-candidates",
            {
                "origin": {"latitude": 45.4642, "longitude": 9.19},
                "destination": {"latitude": 44.4949, "longitude": 11.3426},
                "effective_cng_range_km": 300,
                "maximum_detour_minutes": 10,
                "departure_at": "2026-08-28T08:00:00+02:00",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["stage"] == "network_detour"
    assert body["departure_at"] == "2026-08-28T08:00:00+02:00"
    assert body["maximum_detour_minutes"] == 10
    assert body["cost_basis"] == {
        "provider": "valhalla",
        "traffic_state": "not_configured",
        "traffic_aware": False,
        "duration_model": "valhalla_graph_speeds",
        "distance_model": "road_network",
    }
    assert body["network_evaluation"] == {
        "spatial_candidate_count": 83,
        "matrix_candidate_count": 1,
        "reachable_candidate_count": 1,
        "unreachable_candidate_count": 0,
        "eligible_candidate_count": 1,
        "excluded_by_detour_count": 0,
        "matrix_batch_size": 40,
        "matrix_calls": 2,
        "matrix_fallback_splits": 0,
        "matrix_location_failures": 0,
        "base_route_calls": 1,
        "per_candidate_route_calls": 0,
    }
    assert body["candidates"] == [
        {
            "station_id": 10,
            "mimit_station_id": "1001",
            "name": "Milano Metano",
            "municipality": "Milano",
            "province": "MI",
            "latitude": 45.4,
            "longitude": 9.3,
            "straight_line_distance_to_route_meters": 12.5,
            "route_fraction": 0.1,
            "distance_from_previous_waypoint_meters": 1200.0,
            "duration_from_previous_waypoint_seconds": 120.0,
            "station_to_destination_distance_meters": 1600.0,
            "station_to_destination_duration_seconds": 260.0,
            "route_via_station_distance_meters": 2800.0,
            "route_via_station_duration_seconds": 380.0,
            "extra_distance_meters": 300.0,
            "detour_duration_seconds": 60.0,
            "detour_minutes": 1.0,
            "station_eta": "2026-08-28T08:02:00+02:00",
            "destination_eta": "2026-08-28T08:06:20+02:00",
        }
    ]


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
