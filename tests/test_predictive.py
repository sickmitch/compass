import asyncio
from collections.abc import AsyncIterator
from datetime import datetime
from types import SimpleNamespace

import httpx
import pytest

from compass.api.main import app
from compass.candidates.domain import (
    CorridorCandidateRequest,
    CorridorCandidateResult,
    CorridorPolicy,
    CorridorRadius,
    SpatialCandidate,
    SpatialPruningMetrics,
)
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.detours.domain import (
    EligibleDetourCandidate,
    NetworkCostBasis,
    NetworkDetourPolicy,
    NetworkDetourRequest,
    NetworkDetourResult,
    NetworkEvaluationMetrics,
)
from compass.predictive.domain import PredictiveCandidatesRequest
from compass.predictive.service import evaluate_predictive_cng_candidates
from compass.ranking.domain import CandidateEnrichment, RankedCandidatesRequest, RankingPolicy
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    MatrixCost,
    MatrixRequest,
    MatrixResult,
    RouteRequest,
)


def _candidate(
    station_id: int,
    distance_meters: float,
    *,
    base_distance_meters: float = 210_000,
) -> EligibleDetourCandidate:
    station = SpatialCandidate(
        station_id=station_id,
        mimit_station_id=str(1000 + station_id),
        name=f"Station {station_id}",
        municipality="Test",
        province="TS",
        latitude=45 - station_id / 100,
        longitude=9 + station_id / 100,
        straight_line_distance_to_route_meters=station_id * 10,
        route_fraction=station_id / 10,
    )
    departure = datetime.fromisoformat("2026-08-30T10:00:00+02:00")
    return EligibleDetourCandidate(
        station=station,
        distance_from_previous_waypoint_meters=distance_meters,
        duration_from_previous_waypoint_seconds=distance_meters / 25,
        station_to_destination_distance_meters=base_distance_meters - distance_meters,
        station_to_destination_duration_seconds=(base_distance_meters - distance_meters) / 25,
        route_via_station_distance_meters=base_distance_meters,
        route_via_station_duration_seconds=base_distance_meters / 25,
        extra_distance_meters=0,
        detour_duration_seconds=station_id * 30,
        detour_minutes=station_id / 2,
        station_eta=departure,
        destination_eta=departure,
    )


def _network_result(
    route_positions_meters: tuple[float, ...] = (50_000, 90_000, 110_000),
    *,
    base_distance_meters: float = 210_000,
) -> NetworkDetourResult:
    candidates = tuple(
        _candidate(
            station_id,
            distance_meters,
            base_distance_meters=base_distance_meters,
        )
        for station_id, distance_meters in enumerate(route_positions_meters, start=1)
    )
    spatial = CorridorCandidateResult(
        base_route=BaseRoute(
            base_distance_meters,
            base_distance_meters / 25,
            "encoded",
            (),
            "valhalla",
        ),
        corridor=CorridorRadius(300, 0.2, 60, 50, "maximum"),
        metrics=SpatialPruningMetrics(
            active_station_count=3,
            active_station_with_location_count=3,
            excluded_missing_location_count=0,
            corridor_candidate_count=3,
            returned_candidate_count=3,
            pruned_with_location_count=0,
            reduction_ratio=0,
            candidate_limit_applied=False,
        ),
        candidates=tuple(candidate.station for candidate in candidates),
    )
    return NetworkDetourResult(
        spatial_result=spatial,
        maximum_detour_seconds=600,
        departure_at=datetime.fromisoformat("2026-08-30T10:00:00+02:00"),
        cost_basis=NetworkCostBasis(),
        metrics=NetworkEvaluationMetrics(
            spatial_candidate_count=3,
            matrix_candidate_count=3,
            reachable_candidate_count=3,
            unreachable_candidate_count=0,
            eligible_candidate_count=3,
            excluded_by_detour_count=0,
            matrix_batch_size=40,
            matrix_calls=2,
            matrix_fallback_splits=0,
            matrix_location_failures=0,
        ),
        candidates=candidates,
    )


def _request(
    remaining_km: float,
    reserve_km: float = 30,
    *,
    effective_range_km: float = 300,
) -> PredictiveCandidatesRequest:
    return PredictiveCandidatesRequest(
        ranked_request=RankedCandidatesRequest(
            network_request=NetworkDetourRequest(
                corridor_request=CorridorCandidateRequest(
                    route=RouteRequest(
                        origin=Coordinate(45.4642, 9.19),
                        destination=Coordinate(44.4949, 11.3426),
                    ),
                    effective_cng_range_km=effective_range_km,
                ),
                maximum_detour_seconds=600,
                departure_at=datetime.fromisoformat("2026-08-30T10:00:00+02:00"),
            )
        ),
        estimated_remaining_cng_range_km=remaining_km,
        reserve_cng_range_km=reserve_km,
    )


def _evaluate(
    monkeypatch: pytest.MonkeyPatch,
    *,
    remaining_km: float,
    reserve_km: float = 30,
    opening_hours: str | None = None,
    effective_range_km: float = 300,
    network_result: NetworkDetourResult | None = None,
):
    network_calls = 0
    base_route_calls = 0

    async def fake_network(*args: object, **kwargs: object) -> NetworkDetourResult:
        nonlocal network_calls
        network_calls += 1
        return network_result or _network_result()

    async def fake_route(*args: object, **kwargs: object) -> BaseRoute:
        nonlocal base_route_calls
        base_route_calls += 1
        return (network_result or _network_result()).spatial_result.base_route

    pairwise_matrix_calls = 0

    async def fake_matrix(request: MatrixRequest) -> MatrixResult:
        nonlocal pairwise_matrix_calls
        pairwise_matrix_calls += 1
        active_network = network_result or _network_result()
        positions = {
            candidate.station.station_id: candidate.distance_from_previous_waypoint_meters
            for candidate in active_network.candidates
        }

        def station_id(coordinate: Coordinate) -> int:
            return round((45 - coordinate.latitude) * 100)

        return MatrixResult(
            costs=tuple(
                tuple(
                    MatrixCost(
                        distance_meters=abs(
                            positions[station_id(target)] - positions[station_id(source)]
                        ),
                        duration_seconds=abs(
                            positions[station_id(target)] - positions[station_id(source)]
                        )
                        / 25,
                    )
                    for target in request.targets
                )
                for source in request.sources
            ),
            provider="valhalla",
            algorithm="fixture",
        )

    loaded_station_ids: list[int] = []

    def fake_enrichment(session: object, station_ids: object) -> dict[object, object]:
        loaded_station_ids.extend(station_ids)  # type: ignore[arg-type]
        return {
            station_id: CandidateEnrichment(opening_hours=opening_hours)
            for station_id in loaded_station_ids
        }

    monkeypatch.setattr("compass.predictive.service.evaluate_cng_detours", fake_network)
    monkeypatch.setattr(
        "compass.predictive.service.load_candidate_enrichments",
        fake_enrichment,
    )
    result = asyncio.run(
        evaluate_predictive_cng_candidates(
            SimpleNamespace(),  # type: ignore[arg-type]
            SimpleNamespace(route=fake_route, matrix=fake_matrix),  # type: ignore[arg-type]
            _request(
                remaining_km,
                reserve_km,
                effective_range_km=effective_range_km,
            ),
            corridor_policy=CorridorPolicy(),
            detour_policy=NetworkDetourPolicy(),
            ranking_policy=RankingPolicy(),
            max_route_geometry_points=100,
        )
    )
    assert pairwise_matrix_calls == result.reachability.pairwise_matrix_calls
    return result, loaded_station_ids, network_calls, base_route_calls


def test_predictive_filter_is_inclusive_and_ranks_only_reachable_candidates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, loaded_station_ids, network_calls, base_route_calls = _evaluate(
        monkeypatch,
        remaining_km=120,
    )

    assert result.suggestion_state == "suggested"
    assert loaded_station_ids == [1, 2, 3]
    assert [item.ranked.detour.station.station_id for item in result.candidates] == [1]
    assert result.range_basis.usable_range_before_reserve_km == 90
    assert result.range_basis.range_shortfall_to_destination_km == 120
    assert result.reachability.reachable_before_reserve_count == 2
    assert result.reachability.excluded_unreachable_before_reserve_count == 1
    assert result.reachability.furthest_reachable_route_fraction == 0.2
    assert result.candidates[0].estimated_remaining_range_at_arrival_km == 70
    assert result.candidates[0].reserve_margin_at_arrival_km == 40
    assert result.itinerary is not None
    assert [stop.station.station_id for stop in result.itinerary.stops] == [1]
    assert result.itinerary.destination_leg.reserve_margin_at_arrival_km == 110
    assert network_calls == 1
    assert base_route_calls == 1


def test_destination_within_usable_range_suppresses_suggestion_and_enrichment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, loaded_station_ids, network_calls, base_route_calls = _evaluate(
        monkeypatch,
        remaining_km=250,
    )

    assert result.suggestion_state == "not_needed"
    assert result.range_basis.destination_reachable_with_reserve is True
    assert result.candidates == ()
    assert loaded_station_ids == []
    assert result.reachability.evaluation_skipped_destination_reachable is True
    assert result.reachability.excluded_unreachable_before_reserve_count == 0
    assert result.ranking_result.network_result.metrics.matrix_calls == 0
    assert result.ranking_result.network_result.metrics.matrix_candidate_count == 0
    assert network_calls == 0
    assert base_route_calls == 1


def test_predictive_planner_builds_complete_multi_refuel_chain(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    network = _network_result((20_000, 80_000, 140_000))

    result, loaded_station_ids, network_calls, base_route_calls = _evaluate(
        monkeypatch,
        remaining_km=65,
        reserve_km=30,
        effective_range_km=100,
        network_result=network,
    )

    assert result.suggestion_state == "suggested"
    assert loaded_station_ids == [1, 2, 3]
    assert result.itinerary is not None
    assert [stop.station.station_id for stop in result.itinerary.stops] == [1, 2, 3]
    assert [stop.leg_distance_meters for stop in result.itinerary.stops] == [
        20_000,
        60_000,
        60_000,
    ]
    assert [stop.available_range_at_departure_km for stop in result.itinerary.stops] == [
        65,
        100,
        100,
    ]
    assert [stop.reserve_margin_at_arrival_km for stop in result.itinerary.stops] == [
        15,
        10,
        10,
    ]
    assert result.itinerary.destination_leg.distance_meters == 70_000
    assert result.itinerary.destination_leg.reserve_margin_at_arrival_km == 0
    assert result.itinerary.total_distance_meters == 210_000
    assert result.reachability.pairwise_matrix_calls == 1
    assert result.reachability.itinerary_search_labels >= 3
    assert network_calls == 1
    assert base_route_calls == 1


def test_reachable_first_station_without_complete_chain_is_not_suggested(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    network = _network_result((20_000, 100_000, 180_000))

    result, _, _, _ = _evaluate(
        monkeypatch,
        remaining_km=65,
        reserve_km=30,
        effective_range_km=100,
        network_result=network,
    )

    assert result.suggestion_state == "no_complete_itinerary"
    assert result.reachability.reachable_before_reserve_count == 1
    assert result.candidates == ()
    assert result.itinerary is None
    assert result.reachability.pairwise_matrix_calls == 1


def test_no_station_before_reserve_is_explicit(monkeypatch: pytest.MonkeyPatch) -> None:
    result, loaded_station_ids, network_calls, base_route_calls = _evaluate(
        monkeypatch,
        remaining_km=40,
        reserve_km=30,
    )

    assert result.suggestion_state == "no_reachable_station"
    assert result.reachability.reachable_before_reserve_count == 0
    assert result.reachability.excluded_unreachable_before_reserve_count == 3
    assert loaded_station_ids == []
    assert network_calls == 1
    assert base_route_calls == 1


def test_reachable_but_closed_stations_produce_no_eligible_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, loaded_station_ids, network_calls, base_route_calls = _evaluate(
        monkeypatch,
        remaining_km=120,
        opening_hours="off",
    )

    assert result.suggestion_state == "no_eligible_station"
    assert result.candidates == ()
    assert result.reachability.reachable_before_reserve_count == 2
    assert result.reachability.excluded_unreachable_before_reserve_count == 1
    assert result.reachability.ranked_reachable_candidate_count == 0
    assert result.ranking_result.metrics.opening_closed_count == 2
    assert result.ranking_result.metrics.excluded_closed_count == 2
    assert loaded_station_ids == [1, 2, 3]
    assert network_calls == 1
    assert base_route_calls == 1


@pytest.mark.parametrize(
    ("remaining_km", "reserve_km"),
    [(0, 0), (301, 30), (100, -1), (100, 100)],
)
def test_predictive_request_rejects_impossible_range_state(
    remaining_km: float,
    reserve_km: float,
) -> None:
    with pytest.raises(ValueError):
        _request(remaining_km, reserve_km)


def _api_payload() -> dict[str, object]:
    return {
        "origin": {"latitude": 45.4642, "longitude": 9.19},
        "destination": {"latitude": 44.4949, "longitude": 11.3426},
        "effective_cng_range_km": 300,
        "estimated_remaining_cng_range_km": 120,
        "reserve_cng_range_km": 30,
        "maximum_detour_minutes": 10,
        "departure_at": "2026-08-30T10:00:00+02:00",
    }


def _post_predictive(payload: dict[str, object]) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post("/api/v1/cng/predictive-candidates", json=payload)

    return asyncio.run(request())


def test_predictive_api_exposes_range_basis_reachability_and_ranked_candidates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, _, _, _ = _evaluate(monkeypatch, remaining_km=120)

    async def override_session() -> AsyncIterator[object]:
        yield object()

    async def override_provider() -> object:
        return object()

    async def override_settings() -> Settings:
        return Settings(_env_file=None)

    async def fake_predictive(*args: object, **kwargs: object):
        request = args[2]
        assert request.estimated_remaining_cng_range_km == 120  # type: ignore[attr-defined]
        assert request.reserve_cng_range_km == 30  # type: ignore[attr-defined]
        return result

    monkeypatch.setattr(
        "compass.api.predictive.evaluate_predictive_cng_candidates",
        fake_predictive,
    )
    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        response = _post_predictive(_api_payload())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["stage"] == "predictive_ranking"
    assert body["suggestion_state"] == "suggested"
    assert body["range_basis"] == {
        "effective_cng_range_km": 300.0,
        "estimated_remaining_cng_range_km": 120.0,
        "reserve_cng_range_km": 30.0,
        "usable_range_before_reserve_km": 90.0,
        "remaining_route_distance_km": 210.0,
        "range_shortfall_to_destination_km": 120.0,
        "destination_reachable_with_reserve": False,
        "remaining_route_origin": "request_origin",
        "consumption_model": "caller_estimated_remaining_range",
        "traffic_state": "not_configured",
        "traffic_adjusted": False,
    }
    assert body["reachability_evaluation"] == {
        "detour_eligible_candidate_count": 3,
        "reachable_before_reserve_count": 2,
        "excluded_unreachable_before_reserve_count": 1,
        "ranked_reachable_candidate_count": 1,
        "furthest_reachable_route_fraction": 0.2,
        "evaluation_skipped_destination_reachable": False,
        "pairwise_matrix_calls": 0,
        "pairwise_matrix_fallback_splits": 0,
        "pairwise_matrix_location_failures": 0,
        "itinerary_search_labels": 2,
    }
    assert len(body["candidates"]) == 1
    assert body["candidates"][0]["estimated_remaining_range_at_arrival_km"] == 70
    assert body["candidates"][0]["reserve_margin_at_arrival_km"] == 40
    assert body["ranking_evaluation"]["enrichment_queries"] == 1
    assert [stop["mimit_station_id"] for stop in body["itinerary"]["stops"]] == ["1001"]
    assert body["itinerary"]["destination_leg"]["reserve_margin_at_arrival_km"] == 110


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("estimated_remaining_cng_range_km", 301),
        ("reserve_cng_range_km", 120),
        ("unknown_policy", True),
    ],
)
def test_predictive_api_request_is_strict(field: str, value: object) -> None:
    payload = _api_payload()
    payload[field] = value

    response = _post_predictive(payload)

    assert response.status_code == 422
    assert response.json() == {
        "code": "invalid_request",
        "message": "The request payload is invalid.",
    }


def test_predictive_openapi_contract_requires_range_state() -> None:
    openapi = app.openapi()
    operation = openapi["paths"]["/api/v1/cng/predictive-candidates"]["post"]
    schema_reference = operation["requestBody"]["content"]["application/json"]["schema"]["$ref"]
    schema_name = schema_reference.rsplit("/", 1)[-1]
    schema = openapi["components"]["schemas"][schema_name]

    assert set(schema["required"]) >= {
        "origin",
        "destination",
        "effective_cng_range_km",
        "estimated_remaining_cng_range_km",
        "reserve_cng_range_km",
        "maximum_detour_minutes",
        "departure_at",
    }
    assert schema["additionalProperties"] is False
