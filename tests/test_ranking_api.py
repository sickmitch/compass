import asyncio
import copy
import importlib.util
from collections.abc import AsyncIterator
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from types import ModuleType

import httpx
import pytest

from compass.api.main import app
from compass.candidates.domain import (
    CorridorCandidateResult,
    CorridorRadius,
    SpatialCandidate,
    SpatialPruningMetrics,
)
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.detours.domain import (
    EligibleDetourCandidate,
    NetworkCostBasis,
    NetworkDetourResult,
    NetworkEvaluationMetrics,
)
from compass.ranking.domain import (
    EvaluatedCngPrice,
    OpeningHoursEvaluation,
    RankedCandidate,
    RankedCandidatesResult,
    RankingBreakdown,
    RankingMetrics,
    RankingPolicy,
)
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import BaseRoute

PHASE6_VERIFIER = Path(__file__).parents[1] / "scripts" / "validate-phase6-live.py"


def _load_phase6_verifier() -> ModuleType:
    specification = importlib.util.spec_from_file_location("phase6_live_verifier", PHASE6_VERIFIER)
    assert specification is not None
    assert specification.loader is not None
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def _post(payload: dict[str, object]) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post("/api/v1/cng/ranked-candidates", json=payload)

    return asyncio.run(request())


def _result() -> RankedCandidatesResult:
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
    base_route = BaseRoute(2_500, 320, "encoded-route", (), "valhalla")
    spatial = CorridorCandidateResult(
        base_route=base_route,
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
    departure_at = datetime.fromisoformat("2026-08-28T08:00:00+02:00")
    detour = EligibleDetourCandidate(
        station=station,
        distance_from_previous_waypoint_meters=1_200,
        duration_from_previous_waypoint_seconds=120,
        station_to_destination_distance_meters=1_600,
        station_to_destination_duration_seconds=260,
        route_via_station_distance_meters=2_800,
        route_via_station_duration_seconds=380,
        extra_distance_meters=300,
        detour_duration_seconds=60,
        detour_minutes=1,
        station_eta=datetime.fromisoformat("2026-08-28T08:02:00+02:00"),
        destination_eta=datetime.fromisoformat("2026-08-28T08:06:20+02:00"),
    )
    network = NetworkDetourResult(
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
        candidates=(detour,),
    )
    candidate = RankedCandidate(
        detour=detour,
        opening=OpeningHoursEvaluation(
            state="open",
            validation="valid",
            opening_hours="24/7",
            source="osm",
            source_confidence=0.98,
            evaluated_at=datetime.fromisoformat("2026-08-28T08:02:00+02:00"),
            timezone="Europe/Rome",
            next_change_at=None,
        ),
        phone="+39 02 123456",
        brand="Fixture",
        operator=None,
        osm_match_confidence=0.98,
        price=EvaluatedCngPrice(
            unit_price=Decimal("1.499"),
            currency="EUR",
            unit="kg",
            service_mode="served",
            observed_at=datetime.fromisoformat("2026-08-26T18:30:00+02:00"),
            ingested_at=datetime.fromisoformat("2026-08-27T06:00:00+02:00"),
            source_name="mimit_cng",
            age_seconds=135_120,
            freshness_state="fresh",
        ),
        ranking=RankingBreakdown(
            rank=1,
            total_score=0.9,
            detour_score=0.9,
            opening_score=1,
            price_score=1,
            price_freshness_score=0.5,
            detour_contribution=0.45,
            opening_contribution=0.25,
            price_contribution=0.15,
            price_freshness_contribution=0.05,
            availability_multiplier=1,
        ),
    )
    return RankedCandidatesResult(
        network_result=network,
        policy=RankingPolicy(),
        include_closed=False,
        metrics=RankingMetrics(
            detour_eligible_candidate_count=1,
            opening_open_count=1,
            opening_closed_count=0,
            opening_unknown_count=0,
            opening_valid_count=1,
            opening_missing_count=0,
            opening_invalid_count=0,
            excluded_closed_count=0,
            price_available_count=1,
            price_missing_count=0,
            ranked_candidate_count=1,
            enrichment_queries=1,
        ),
        candidates=(candidate,),
    )


def _payload() -> dict[str, object]:
    return {
        "origin": {"latitude": 45.4642, "longitude": 9.19},
        "destination": {"latitude": 44.4949, "longitude": 11.3426},
        "effective_cng_range_km": 300,
        "maximum_detour_minutes": 10,
        "departure_at": "2026-08-28T08:00:00+02:00",
    }


def _post_fixture_result(
    monkeypatch: pytest.MonkeyPatch,
) -> httpx.Response:
    async def override_session() -> AsyncIterator[object]:
        yield object()

    async def override_provider() -> object:
        return object()

    async def override_settings() -> Settings:
        return Settings(_env_file=None)

    async def fake_rank(*args: object, **kwargs: object) -> RankedCandidatesResult:
        request = args[2]
        assert request.include_closed is False  # type: ignore[attr-defined]
        assert request.network_request.maximum_detour_seconds == 600  # type: ignore[attr-defined]
        assert kwargs["ranking_policy"] == RankingPolicy()
        return _result()

    monkeypatch.setattr("compass.api.ranking.rank_cng_candidates", fake_rank)
    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_routing_provider] = override_provider
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        return _post(_payload())
    finally:
        app.dependency_overrides.clear()


def test_ranked_candidates_exposes_arrival_availability_price_and_score(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = _post_fixture_result(monkeypatch)

    assert response.status_code == 200
    body = response.json()
    assert body["stage"] == "ranking"
    assert body["ranking_policy"] == {
        "detour_weight": 0.5,
        "opening_weight": 0.25,
        "price_weight": 0.15,
        "price_freshness_weight": 0.1,
        "unknown_opening_score": 0.25,
        "closed_score_multiplier": 0.25,
        "price_freshness_hours": 168.0,
        "opening_hours_timezone": "Europe/Rome",
        "opening_hours_country": "IT",
        "price_selection": "lowest_current_cng_unit_price",
        "closed_candidate_policy": "exclude",
    }
    assert body["ranking_evaluation"]["enrichment_queries"] == 1
    candidate = body["candidates"][0]
    assert candidate["opening"] == {
        "state": "open",
        "validation": "valid",
        "opening_hours": "24/7",
        "source": "osm",
        "source_confidence": 0.98,
        "evaluated_at": "2026-08-28T08:02:00+02:00",
        "timezone": "Europe/Rome",
        "next_change_at": None,
        "comment": None,
        "warnings": [],
    }
    assert candidate["phone"] == "+39 02 123456"
    assert candidate["price"]["unit_price"] == 1.499
    assert candidate["price"]["currency"] == "EUR"
    assert candidate["price"]["unit"] == "kg"
    assert candidate["price"]["freshness_state"] == "fresh"
    assert candidate["ranking"] == {
        "rank": 1,
        "total_score": 0.9,
        "detour_score": 0.9,
        "opening_score": 1.0,
        "price_score": 1.0,
        "price_freshness_score": 0.5,
        "detour_contribution": 0.45,
        "opening_contribution": 0.25,
        "price_contribution": 0.15,
        "price_freshness_contribution": 0.05,
        "availability_multiplier": 1.0,
    }

    verifier = _load_phase6_verifier()
    with_closed = copy.deepcopy(body)
    with_closed["ranking_policy"][
        "closed_candidate_policy"
    ] = "include_with_zero_opening_score"
    verifier.validate_pair(body, with_closed, require_closed=False)
    openapi_summary = verifier.validate_openapi(app.openapi())
    assert openapi_summary == {
        "required_request_fields": [
            "departure_at",
            "destination",
            "effective_cng_range_km",
            "maximum_detour_minutes",
            "origin",
        ],
        "include_closed_default": False,
    }


def test_phase6_live_verifier_rejects_spaced_sunday_off_reported_open(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    verifier = _load_phase6_verifier()
    payload = _post_fixture_result(monkeypatch).json()
    candidate = payload["candidates"][0]
    sunday_eta = datetime.fromisoformat("2026-08-30T08:02:00+02:00")
    candidate["station_eta"] = sunday_eta.isoformat()
    candidate["opening"]["evaluated_at"] = sunday_eta.isoformat()
    candidate["opening"][
        "opening_hours"
    ] = "Mo-Sa 06:30-12:30, 14:30-19:00; Su, PH off"
    observed_at = datetime.fromisoformat(candidate["price"]["observed_at"])
    candidate["price"]["age_seconds"] = (sunday_eta - observed_at).total_seconds()

    with pytest.raises(
        verifier.ValidationFailure,
        match="final Sunday/holiday off rule but is open",
    ):
        verifier.validate_payload(payload)


def test_ranked_candidates_request_is_strict_and_requires_offset() -> None:
    payload = _payload()
    payload["departure_at"] = "2026-08-28T08:00:00"
    payload["unknown_policy"] = True

    response = _post(payload)

    assert response.status_code == 422
    assert response.json() == {
        "code": "invalid_request",
        "message": "The request payload is invalid.",
    }
