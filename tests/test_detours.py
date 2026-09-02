import asyncio
from datetime import datetime
from types import SimpleNamespace

import pytest

from compass.candidates.domain import (
    CorridorCandidateRequest,
    CorridorCandidateResult,
    CorridorPolicy,
    CorridorRadius,
    SpatialCandidate,
    SpatialPruningMetrics,
)
from compass.detours.domain import (
    NetworkCostBasis,
    NetworkDetourPolicy,
    NetworkDetourRequest,
    calculate_detour_candidate,
)
from compass.detours.service import evaluate_cng_detours
from compass.ranking.domain import CandidateEnrichment, RankingPolicy
from compass.ranking.service import rank_network_candidates
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    MatrixCost,
    MatrixLocationError,
    MatrixRequest,
    MatrixResult,
    RouteRequest,
)


def _station(station_id: int) -> SpatialCandidate:
    return SpatialCandidate(
        station_id=station_id,
        mimit_station_id=str(1000 + station_id),
        name=f"Station {station_id}",
        municipality="Test",
        province="TS",
        latitude=44 + station_id / 100,
        longitude=10 + station_id / 100,
        straight_line_distance_to_route_meters=float(station_id * 10),
        route_fraction=station_id / 10,
    )


def _base_route() -> BaseRoute:
    return BaseRoute(
        distance_meters=1_000,
        duration_seconds=600,
        encoded_polyline="encoded",
        maneuvers=(Maneuver(1, "Procedi.", 1_000, 600, 0, 1),),
        provider="valhalla",
    )


def _spatial_result(candidates: tuple[SpatialCandidate, ...]) -> CorridorCandidateResult:
    return CorridorCandidateResult(
        base_route=_base_route(),
        corridor=CorridorRadius(300, 0.2, 60, 50, "maximum"),
        metrics=SpatialPruningMetrics(
            active_station_count=1512,
            active_station_with_location_count=1505,
            excluded_missing_location_count=7,
            corridor_candidate_count=10,
            returned_candidate_count=len(candidates),
            pruned_with_location_count=1495,
            reduction_ratio=1 - 10 / 1505,
            candidate_limit_applied=True,
        ),
        candidates=candidates,
    )


def _request() -> NetworkDetourRequest:
    return NetworkDetourRequest(
        corridor_request=CorridorCandidateRequest(
            route=RouteRequest(
                origin=Coordinate(45, 9),
                destination=Coordinate(44, 11),
            ),
            effective_cng_range_km=300,
        ),
        maximum_detour_seconds=60,
        departure_at=datetime.fromisoformat("2026-08-28T08:00:00+02:00"),
    )


class _MatrixProvider:
    outward: dict[int, MatrixCost | None] = {
        1: MatrixCost(400, 200),
        2: MatrixCost(450, 300),
        3: MatrixCost(500, 350),
        4: None,
        5: MatrixCost(300, 100),
    }
    onward: dict[int, MatrixCost | None] = {
        1: MatrixCost(600, 400),
        2: MatrixCost(650, 360),
        3: MatrixCost(700, 400),
        4: MatrixCost(800, 500),
        5: MatrixCost(500, 470),
    }

    def __init__(self) -> None:
        self.matrix_calls: list[MatrixRequest] = []

    async def matrix(self, request: MatrixRequest) -> MatrixResult:
        self.matrix_calls.append(request)
        if request.sources == (Coordinate(45, 9),):
            costs = tuple(
                self.outward[round((target.latitude - 44) * 100)]
                for target in request.targets
            )
            return MatrixResult((costs,), "valhalla", "costmatrix")
        costs = tuple(
            (self.onward[round((source.latitude - 44) * 100)],)
            for source in request.sources
        )
        return MatrixResult(costs, "valhalla", "costmatrix")


class _LocationFailureProvider(_MatrixProvider):
    async def matrix(self, request: MatrixRequest) -> MatrixResult:
        candidate_coordinates = (
            request.targets
            if request.sources == (Coordinate(45, 9),)
            else request.sources
        )
        station_ids = {
            round((coordinate.latitude - 44) * 100)
            for coordinate in candidate_coordinates
        }
        if 3 in station_ids:
            self.matrix_calls.append(request)
            raise MatrixLocationError("fixture location failure")
        return await super().matrix(request)


def test_detour_math_uses_road_costs_clamps_negative_delta_and_calculates_eta() -> None:
    result = calculate_detour_candidate(
        station=_station(1),
        base_route=_base_route(),
        previous_to_station=MatrixCost(300, 100),
        station_to_destination=MatrixCost(500, 470),
        departure_at=datetime.fromisoformat("2026-08-28T08:00:00+02:00"),
    )

    assert result.distance_from_previous_waypoint_meters == 300
    assert result.route_via_station_distance_meters == 800
    assert result.route_via_station_duration_seconds == 570
    assert result.extra_distance_meters == 0
    assert result.detour_duration_seconds == 0
    assert result.station_eta.isoformat() == "2026-08-28T08:01:40+02:00"
    assert result.destination_eta.isoformat() == "2026-08-28T08:09:30+02:00"


def test_network_service_batches_only_pruned_candidates_and_applies_inclusive_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    candidates = tuple(_station(index) for index in range(1, 6))
    spatial = _spatial_result(candidates)

    async def fake_find(*args: object, **kwargs: object) -> CorridorCandidateResult:
        assert kwargs["policy"] == CorridorPolicy(candidate_limit=200)
        return spatial

    monkeypatch.setattr("compass.detours.service.find_corridor_candidates", fake_find)
    provider = _MatrixProvider()
    result = asyncio.run(
        evaluate_cng_detours(
            SimpleNamespace(),  # type: ignore[arg-type]
            provider,  # type: ignore[arg-type]
            _request(),
            corridor_policy=CorridorPolicy(candidate_limit=200),
            detour_policy=NetworkDetourPolicy(matrix_batch_size=2),
            max_route_geometry_points=100,
        )
    )

    assert len(provider.matrix_calls) == 6
    assert all(
        len(call.sources) == 1 or len(call.targets) == 1
        for call in provider.matrix_calls
    )
    assert result.metrics.spatial_candidate_count == 10
    assert result.metrics.matrix_candidate_count == 5
    assert result.metrics.reachable_candidate_count == 4
    assert result.metrics.unreachable_candidate_count == 1
    assert result.metrics.eligible_candidate_count == 3
    assert result.metrics.excluded_by_detour_count == 1
    assert result.metrics.matrix_calls == 6
    assert result.metrics.matrix_fallback_splits == 0
    assert result.metrics.matrix_location_failures == 0
    assert result.metrics.base_route_calls == 1
    assert result.metrics.per_candidate_route_calls == 0
    assert [candidate.station.station_id for candidate in result.candidates] == [5, 1, 2]
    assert result.candidates[-1].detour_duration_seconds == 60
    assert result.cost_basis.traffic_state == "not_configured"
    assert result.cost_basis.traffic_aware is False
    assert all(call.departure_at == _request().departure_at for call in provider.matrix_calls)


def test_network_service_skips_matrix_when_spatial_pruning_returns_no_candidates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def fake_find(*args: object, **kwargs: object) -> CorridorCandidateResult:
        return _spatial_result(())

    monkeypatch.setattr("compass.detours.service.find_corridor_candidates", fake_find)
    provider = _MatrixProvider()
    result = asyncio.run(
        evaluate_cng_detours(
            SimpleNamespace(),  # type: ignore[arg-type]
            provider,  # type: ignore[arg-type]
            _request(),
            corridor_policy=CorridorPolicy(),
            detour_policy=NetworkDetourPolicy(matrix_batch_size=2),
            max_route_geometry_points=100,
        )
    )

    assert provider.matrix_calls == []
    assert result.metrics.matrix_calls == 0
    assert result.metrics.matrix_fallback_splits == 0
    assert result.metrics.matrix_location_failures == 0
    assert result.candidates == ()


def test_network_service_isolates_bad_matrix_location_without_losing_batch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    candidates = tuple(_station(index) for index in range(1, 6))

    async def fake_find(*args: object, **kwargs: object) -> CorridorCandidateResult:
        return _spatial_result(candidates)

    monkeypatch.setattr("compass.detours.service.find_corridor_candidates", fake_find)
    provider = _LocationFailureProvider()
    result = asyncio.run(
        evaluate_cng_detours(
            SimpleNamespace(),  # type: ignore[arg-type]
            provider,  # type: ignore[arg-type]
            _request(),
            corridor_policy=CorridorPolicy(),
            detour_policy=NetworkDetourPolicy(matrix_batch_size=4),
            max_route_geometry_points=100,
        )
    )

    assert result.metrics.matrix_calls == 12
    assert result.metrics.matrix_fallback_splits == 2
    assert result.metrics.matrix_location_failures == 1
    assert result.metrics.reachable_candidate_count == 3
    assert result.metrics.unreachable_candidate_count == 2
    assert [candidate.station.station_id for candidate in result.candidates] == [5, 1, 2]


def test_network_request_requires_timezone_aware_departure() -> None:
    with pytest.raises(ValueError, match="UTC offset"):
        NetworkDetourRequest(
            corridor_request=_request().corridor_request,
            maximum_detour_seconds=60,
            departure_at=datetime(2026, 8, 28, 8),
        )


def test_traffic_aware_valhalla_costs_control_detour_eta_opening_and_ranking(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    candidates = (_station(1), _station(2))

    async def fake_find(*args: object, **kwargs: object) -> CorridorCandidateResult:
        return _spatial_result(candidates)

    class ScenarioProvider(_MatrixProvider):
        def __init__(self, *, traffic_aware: bool) -> None:
            super().__init__()
            if traffic_aware:
                self.outward = {
                    1: MatrixCost(400, 600),
                    2: MatrixCost(450, 600),
                }
                self.onward = {
                    1: MatrixCost(600, 1_080),
                    2: MatrixCost(650, 600),
                }
            else:
                self.outward = {
                    1: MatrixCost(400, 300),
                    2: MatrixCost(450, 300),
                }
                self.onward = {
                    1: MatrixCost(600, 660),
                    2: MatrixCost(650, 780),
                }

    monkeypatch.setattr("compass.detours.service.find_corridor_candidates", fake_find)
    request = NetworkDetourRequest(
        corridor_request=_request().corridor_request,
        maximum_detour_seconds=12 * 60,
        departure_at=_request().departure_at,
    )
    static_result = asyncio.run(
        evaluate_cng_detours(
            SimpleNamespace(),  # type: ignore[arg-type]
            ScenarioProvider(traffic_aware=False),  # type: ignore[arg-type]
            request,
            corridor_policy=CorridorPolicy(),
            detour_policy=NetworkDetourPolicy(),
            max_route_geometry_points=100,
        )
    )
    traffic_result = asyncio.run(
        evaluate_cng_detours(
            SimpleNamespace(),  # type: ignore[arg-type]
            ScenarioProvider(traffic_aware=True),  # type: ignore[arg-type]
            request,
            corridor_policy=CorridorPolicy(),
            detour_policy=NetworkDetourPolicy(),
            max_route_geometry_points=100,
            cost_basis=NetworkCostBasis(
                traffic_state="fresh",
                traffic_aware=True,
                duration_model="valhalla_time_dependent_traffic",
            ),
        )
    )

    assert [item.detour_minutes for item in static_result.candidates] == [6, 8]
    assert [item.station.station_id for item in traffic_result.candidates] == [2]
    assert traffic_result.metrics.excluded_by_detour_count == 1
    assert traffic_result.candidates[0].detour_minutes == 10
    assert traffic_result.candidates[0].station_eta.isoformat() == (
        "2026-08-28T08:10:00+02:00"
    )
    assert traffic_result.cost_basis.duration_model == "valhalla_time_dependent_traffic"

    enrichments = {
        1: CandidateEnrichment(opening_hours="24/7"),
        2: CandidateEnrichment(opening_hours="Mo-Su 08:10-09:00"),
    }
    static_ranking = rank_network_candidates(
        SimpleNamespace(),  # type: ignore[arg-type]
        static_result,
        include_closed=True,
        ranking_policy=RankingPolicy(),
        enrichments=enrichments,
    )
    traffic_ranking = rank_network_candidates(
        SimpleNamespace(),  # type: ignore[arg-type]
        traffic_result,
        include_closed=True,
        ranking_policy=RankingPolicy(),
        enrichments=enrichments,
    )

    static_station_two = next(
        item for item in static_ranking.candidates if item.detour.station.station_id == 2
    )
    assert static_station_two.opening.state == "closed"
    assert traffic_ranking.candidates[0].opening.state == "open"
    assert traffic_ranking.candidates[0].ranking.detour_score == pytest.approx(1 / 6, abs=1e-6)
    assert traffic_ranking.candidates[0].ranking.detour_contribution == pytest.approx(
        RankingPolicy().detour_weight / 6,
        abs=1e-6,
    )
