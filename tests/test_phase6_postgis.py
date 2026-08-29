import asyncio
import os
from datetime import datetime
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from compass.candidates.domain import CorridorCandidateRequest, CorridorPolicy
from compass.config import get_settings
from compass.detours.domain import NetworkDetourPolicy, NetworkDetourRequest
from compass.etl.service import ingest_mimit, ingest_osm
from compass.ranking.domain import RankedCandidatesRequest, RankingPolicy
from compass.ranking.service import rank_cng_candidates
from compass.reconciliation.service import normalize_and_reconcile, set_match_override
from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    MatrixCost,
    MatrixRequest,
    MatrixResult,
    RouteRequest,
)

FIXTURES = Path(__file__).parent / "fixtures"
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL")


def _encode_polyline6(coordinates: tuple[Coordinate, ...]) -> str:
    encoded: list[str] = []
    previous_latitude = 0
    previous_longitude = 0
    for coordinate in coordinates:
        latitude_value = round(coordinate.latitude * 1_000_000)
        longitude_value = round(coordinate.longitude * 1_000_000)
        for delta in (
            latitude_value - previous_latitude,
            longitude_value - previous_longitude,
        ):
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                encoded.append(chr((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            encoded.append(chr(value + 63))
        previous_latitude = latitude_value
        previous_longitude = longitude_value
    return "".join(encoded)


class _RoutingProvider:
    def __init__(self, route: RouteRequest) -> None:
        self.route_request = route
        self.route_calls = 0
        self.matrix_requests: list[MatrixRequest] = []

    async def route(self, request: RouteRequest) -> BaseRoute:
        assert request == self.route_request
        self.route_calls += 1
        return BaseRoute(
            distance_meters=210_000,
            duration_seconds=7_200,
            encoded_polyline=_encode_polyline6((request.origin, request.destination)),
            maneuvers=(Maneuver(1, "Procedi.", 210_000, 7_200, 0, 1),),
            provider="valhalla",
        )

    async def matrix(self, request: MatrixRequest) -> MatrixResult:
        self.matrix_requests.append(request)
        if request.sources == (self.route_request.origin,):
            return MatrixResult(
                costs=(tuple(MatrixCost(100_000, 3_600) for _ in request.targets),),
                provider="valhalla",
                algorithm="fixture",
            )
        return MatrixResult(
            costs=tuple((MatrixCost(120_000, 4_200),) for _ in request.sources),
            provider="valhalla",
            algorithm="fixture",
        )


@pytest.mark.integration
@pytest.mark.skipif(not TEST_DATABASE_URL, reason="TEST_DATABASE_URL is not configured")
def test_phase6_ranks_postgis_candidates_by_eta_availability_and_price(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    assert TEST_DATABASE_URL is not None
    database_name = make_url(TEST_DATABASE_URL).database or ""
    assert database_name.endswith(
        "_test"
    ), "integration tests require a disposable *_test database"
    monkeypatch.setenv("DATABASE_URL", TEST_DATABASE_URL)
    get_settings.cache_clear()
    alembic = Config("alembic.ini")
    engine = create_engine(TEST_DATABASE_URL)
    try:
        # The database-name guard above makes this destructive reset explicit and scoped.
        command.downgrade(alembic, "base")
        command.upgrade(alembic, "head")
        with Session(engine) as session:
            ingest_mimit(
                session,
                stations_content=(FIXTURES / "phase2_mimit_stations.csv").read_bytes(),
                prices_content=(FIXTURES / "phase2_mimit_prices.csv").read_bytes(),
                stations_url="fixture:///phase2_mimit_stations.csv",
                prices_url="fixture:///phase2_mimit_prices.csv",
            )
            ingest_osm(
                session,
                content=(FIXTURES / "phase2_osm_cng.json").read_bytes(),
                source_url="fixture:///phase2_osm_cng.json",
            )
            normalize_and_reconcile(session)
            set_match_override(
                session,
                mimit_station_id="1002",
                action="link",
                osm_type="node",
                osm_id=201,
                reason="phase 6 deterministic availability fixture",
                created_by="pytest",
            )
            normalize_and_reconcile(session)

            route_request = RouteRequest(
                origin=Coordinate(45.4642, 9.19),
                destination=Coordinate(44.4949, 11.3426),
            )
            provider = _RoutingProvider(route_request)
            result = asyncio.run(
                rank_cng_candidates(
                    session,
                    provider,
                    RankedCandidatesRequest(
                        network_request=NetworkDetourRequest(
                            corridor_request=CorridorCandidateRequest(
                                route=route_request,
                                effective_cng_range_km=125,
                            ),
                            maximum_detour_seconds=600,
                            departure_at=datetime.fromisoformat(
                                "2026-08-28T20:00:00+02:00"
                            ),
                        )
                    ),
                    corridor_policy=CorridorPolicy(candidate_limit=10),
                    detour_policy=NetworkDetourPolicy(matrix_batch_size=10),
                    ranking_policy=RankingPolicy(),
                    max_route_geometry_points=100,
                )
            )

            assert provider.route_calls == 1
            assert len(provider.matrix_requests) == 2
            assert result.metrics.detour_eligible_candidate_count == 2
            assert result.metrics.opening_open_count == 1
            assert result.metrics.opening_closed_count == 1
            assert result.metrics.opening_unknown_count == 0
            assert result.metrics.excluded_closed_count == 1
            assert result.metrics.price_available_count == 2
            assert result.metrics.price_missing_count == 0
            assert result.metrics.ranked_candidate_count == 1
            assert result.metrics.enrichment_queries == 1

            candidate = result.candidates[0]
            assert candidate.detour.station.mimit_station_id == "1001"
            assert candidate.opening.state == "open"
            assert candidate.opening.opening_hours == "24/7"
            assert candidate.opening.timezone == "Europe/Rome"
            assert candidate.phone == "+39 02 123456"
            assert candidate.osm_match_confidence is not None
            assert candidate.price is not None
            assert str(candidate.price.unit_price) == "1.499"
            assert candidate.price.currency == "EUR"
            assert candidate.price.unit == "kg"
            assert candidate.price.freshness_state == "fresh"
            assert candidate.ranking.rank == 1
            assert candidate.ranking.availability_multiplier == 1
    finally:
        engine.dispose()
        get_settings.cache_clear()
