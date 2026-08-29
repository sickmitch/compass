import asyncio
from dataclasses import replace
from datetime import datetime
from decimal import Decimal
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
    EligibleDetourCandidate,
    NetworkCostBasis,
    NetworkDetourPolicy,
    NetworkDetourRequest,
    NetworkDetourResult,
    NetworkEvaluationMetrics,
)
from compass.ranking.domain import (
    CandidateEnrichment,
    CurrentCngPrice,
    RankedCandidatesRequest,
    RankingPolicy,
)
from compass.ranking.service import (
    _evaluate_price,
    _price_selection_key,
    rank_cng_candidates,
)
from compass.routing.domain import BaseRoute, Coordinate, RouteRequest


def _station(station_id: int) -> SpatialCandidate:
    return SpatialCandidate(
        station_id=station_id,
        mimit_station_id=str(1000 + station_id),
        name=f"Station {station_id}",
        municipality="Test",
        province="TS",
        latitude=45.0 - station_id / 100,
        longitude=9.0 + station_id / 100,
        straight_line_distance_to_route_meters=float(station_id * 10),
        route_fraction=station_id / 10,
    )


def _candidate(station_id: int, detour_seconds: float) -> EligibleDetourCandidate:
    station_eta = datetime.fromisoformat("2026-08-28T20:00:00+02:00")
    return EligibleDetourCandidate(
        station=_station(station_id),
        distance_from_previous_waypoint_meters=station_id * 10_000,
        duration_from_previous_waypoint_seconds=station_id * 600,
        station_to_destination_distance_meters=100_000,
        station_to_destination_duration_seconds=3_000,
        route_via_station_distance_meters=station_id * 10_000 + 100_000,
        route_via_station_duration_seconds=3_000 + station_id * 600,
        extra_distance_meters=station_id * 100,
        detour_duration_seconds=detour_seconds,
        detour_minutes=detour_seconds / 60,
        station_eta=station_eta,
        destination_eta=datetime.fromisoformat("2026-08-28T21:00:00+02:00"),
    )


def _network_request() -> NetworkDetourRequest:
    return NetworkDetourRequest(
        corridor_request=CorridorCandidateRequest(
            route=RouteRequest(
                origin=Coordinate(45.4642, 9.19),
                destination=Coordinate(44.4949, 11.3426),
            ),
            effective_cng_range_km=300,
        ),
        maximum_detour_seconds=600,
        departure_at=datetime.fromisoformat("2026-08-28T19:00:00+02:00"),
    )


def _network_result() -> NetworkDetourResult:
    candidates = (
        _candidate(1, 120),
        _candidate(2, 60),
        _candidate(3, 30),
    )
    return NetworkDetourResult(
        spatial_result=CorridorCandidateResult(
            base_route=BaseRoute(210_000, 7_200, "encoded", (), "valhalla"),
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
        ),
        maximum_detour_seconds=600,
        departure_at=_network_request().departure_at,
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


def _price(value: str, observed_at: str = "2026-08-27T20:00:00+02:00") -> CurrentCngPrice:
    return CurrentCngPrice(
        unit_price=Decimal(value),
        currency="EUR",
        unit="kg",
        service_mode="served",
        observed_at=datetime.fromisoformat(observed_at),
        ingested_at=datetime.fromisoformat("2026-08-28T06:00:00+02:00"),
        source_name="mimit_cng",
    )


def _enrichments() -> dict[int, CandidateEnrichment]:
    return {
        1: CandidateEnrichment(
            opening_hours="24/7",
            phone="+39 02 123456",
            brand="Fixture",
            osm_match_confidence=0.98,
            current_price=_price("1.600"),
        ),
        2: CandidateEnrichment(
            opening_hours="Mo-Fr 07:00-18:00",
            osm_match_confidence=0.90,
            current_price=_price("1.200"),
        ),
        3: CandidateEnrichment(),
    }


def _rank(
    monkeypatch: pytest.MonkeyPatch, *, include_closed: bool
):
    network_result = _network_result()

    async def fake_evaluate(*args: object, **kwargs: object) -> NetworkDetourResult:
        assert kwargs["corridor_policy"] == CorridorPolicy()
        assert kwargs["detour_policy"] == NetworkDetourPolicy()
        return network_result

    def fake_load(session: object, station_ids: object) -> dict[int, CandidateEnrichment]:
        assert list(station_ids) == [1, 2, 3]  # type: ignore[arg-type]
        return _enrichments()

    monkeypatch.setattr("compass.ranking.service.evaluate_cng_detours", fake_evaluate)
    monkeypatch.setattr("compass.ranking.service.load_candidate_enrichments", fake_load)
    return asyncio.run(
        rank_cng_candidates(
            SimpleNamespace(),  # type: ignore[arg-type]
            SimpleNamespace(),  # type: ignore[arg-type]
            RankedCandidatesRequest(
                network_request=_network_request(), include_closed=include_closed
            ),
            corridor_policy=CorridorPolicy(),
            detour_policy=NetworkDetourPolicy(),
            ranking_policy=RankingPolicy(),
            max_route_geometry_points=100,
        )
    )


def test_ranking_excludes_closed_but_keeps_unknown_and_explains_score(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = _rank(monkeypatch, include_closed=False)

    assert [candidate.detour.station.station_id for candidate in result.candidates] == [1, 3]
    assert [candidate.ranking.rank for candidate in result.candidates] == [1, 2]
    assert [candidate.opening.state for candidate in result.candidates] == ["open", "unknown"]
    assert result.candidates[0].phone == "+39 02 123456"
    assert result.candidates[0].price is not None
    assert result.candidates[0].price.freshness_state == "fresh"
    assert result.candidates[1].price is None

    for candidate in result.candidates:
        breakdown = candidate.ranking
        contribution_sum = (
            breakdown.detour_contribution
            + breakdown.opening_contribution
            + breakdown.price_contribution
            + breakdown.price_freshness_contribution
        )
        assert breakdown.total_score == pytest.approx(
            contribution_sum * breakdown.availability_multiplier,
            abs=4e-6,
        )

    assert result.metrics.detour_eligible_candidate_count == 3
    assert result.metrics.opening_open_count == 1
    assert result.metrics.opening_closed_count == 1
    assert result.metrics.opening_unknown_count == 1
    assert result.metrics.opening_valid_count == 2
    assert result.metrics.opening_missing_count == 1
    assert result.metrics.opening_invalid_count == 0
    assert result.metrics.excluded_closed_count == 1
    assert result.metrics.price_available_count == 2
    assert result.metrics.price_missing_count == 1
    assert result.metrics.ranked_candidate_count == 2
    assert result.metrics.enrichment_queries == 1


def test_include_closed_applies_explicit_penalty_and_stable_tie_breaks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = _rank(monkeypatch, include_closed=True)

    assert [candidate.detour.station.station_id for candidate in result.candidates] == [1, 3, 2]
    closed = result.candidates[-1]
    assert closed.opening.state == "closed"
    assert closed.ranking.opening_score == 0
    assert closed.ranking.availability_multiplier == 0.25
    assert result.metrics.excluded_closed_count == 0
    assert result.metrics.ranked_candidate_count == 3


def test_no_eligible_candidate_skips_enrichment_query(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    network = _network_result()
    empty_network = replace(
        network,
        metrics=replace(network.metrics, eligible_candidate_count=0),
        candidates=(),
    )

    async def fake_evaluate(*args: object, **kwargs: object) -> NetworkDetourResult:
        return empty_network

    monkeypatch.setattr("compass.ranking.service.evaluate_cng_detours", fake_evaluate)
    result = asyncio.run(
        rank_cng_candidates(
            SimpleNamespace(),  # type: ignore[arg-type]
            SimpleNamespace(),  # type: ignore[arg-type]
            RankedCandidatesRequest(network_request=_network_request()),
            corridor_policy=CorridorPolicy(),
            detour_policy=NetworkDetourPolicy(),
            ranking_policy=RankingPolicy(),
            max_route_geometry_points=100,
        )
    )

    assert result.candidates == ()
    assert result.metrics.detour_eligible_candidate_count == 0
    assert result.metrics.enrichment_queries == 0


@pytest.mark.parametrize(
    ("observed_at", "expected_state", "expected_age"),
    [
        ("2026-08-27T20:00:00+02:00", "fresh", 86_400),
        ("2026-08-20T20:00:00+02:00", "stale", 691_200),
        ("2026-08-29T20:00:00+02:00", "future_observation", -86_400),
    ],
)
def test_price_freshness_is_evaluated_against_station_eta(
    observed_at: str, expected_state: str, expected_age: float
) -> None:
    result = _evaluate_price(
        _price("1.499", observed_at),
        eta=datetime.fromisoformat("2026-08-28T20:00:00+02:00"),
        freshness_seconds=7 * 24 * 60 * 60,
    )

    assert result is not None
    assert result.freshness_state == expected_state
    assert result.age_seconds == expected_age


def test_current_price_selection_prefers_lowest_then_newest_then_service_mode() -> None:
    lower = _price("1.399", "2026-08-26T20:00:00+02:00")
    higher = _price("1.499", "2026-08-28T18:00:00+02:00")
    older_tie = _price("1.399", "2026-08-25T20:00:00+02:00")
    served_tie = _price("1.399", "2026-08-26T20:00:00+02:00")
    self_tie = replace(served_tie, service_mode="self")

    assert min((higher, lower), key=_price_selection_key) == lower
    assert min((older_tie, lower), key=_price_selection_key) == lower
    assert min((served_tie, self_tie), key=_price_selection_key) == self_tie


def test_ranking_policy_rejects_non_normalized_weights() -> None:
    with pytest.raises(ValueError, match="sum to one"):
        RankingPolicy(detour_weight=0.6)


def test_ranking_policy_requires_valid_timezone_and_country_context() -> None:
    with pytest.raises(ValueError, match="IANA timezone"):
        RankingPolicy(opening_hours_timezone="Europe/Not-A-Place")
    with pytest.raises(ValueError, match="ISO 3166"):
        RankingPolicy(opening_hours_country="it")
