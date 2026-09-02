import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from compass.candidates.geometry import route_linestring_wkt
from compass.candidates.repository import PostgisCandidateRepository
from compass.config import get_settings
from compass.etl.service import ingest_mimit, ingest_osm
from compass.reconciliation.service import normalize_and_reconcile
from compass.routing.domain import Coordinate

FIXTURES = Path(__file__).parent / "fixtures"
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL")


@pytest.mark.integration
@pytest.mark.skipif(not TEST_DATABASE_URL, reason="TEST_DATABASE_URL is not configured")
def test_phase4_route_corridor_prunes_with_postgis(monkeypatch: pytest.MonkeyPatch) -> None:
    assert TEST_DATABASE_URL is not None
    database_name = make_url(TEST_DATABASE_URL).database or ""
    assert database_name.endswith("_test"), "integration tests require a disposable *_test database"
    monkeypatch.setenv("DATABASE_URL", TEST_DATABASE_URL)
    get_settings.cache_clear()
    alembic = Config("alembic.ini")
    engine = create_engine(TEST_DATABASE_URL)
    try:
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

            route_wkt = route_linestring_wkt(
                (
                    Coordinate(latitude=45.4642, longitude=9.19),
                    Coordinate(latitude=44.4949, longitude=11.3426),
                )
            )
            result = PostgisCandidateRepository(session).within_corridor(
                route_wkt=route_wkt,
                radius_meters=25_000,
                limit=10,
            )

            assert result.active_station_count == 4
            assert result.active_station_with_location_count == 3
            assert result.corridor_candidate_count == 2
            assert {candidate.mimit_station_id for candidate in result.candidates} == {
                "1001",
                "1002",
            }
            assert all(
                candidate.straight_line_distance_to_route_meters < 1
                for candidate in result.candidates
            )
            assert all(0 <= candidate.route_fraction <= 1 for candidate in result.candidates)

            excluded = PostgisCandidateRepository(session).within_corridor(
                route_wkt=route_wkt,
                radius_meters=25_000,
                limit=10,
                excluded_mimit_station_ids=("1001",),
            )
            assert excluded.corridor_candidate_count == 1
            assert [candidate.mimit_station_id for candidate in excluded.candidates] == [
                "1002"
            ]

            limited = PostgisCandidateRepository(session).within_corridor(
                route_wkt=route_wkt,
                radius_meters=25_000,
                limit=1,
            )
            assert limited.corridor_candidate_count == 2
            assert len(limited.candidates) == 1

            session.execute(text("SET LOCAL enable_seqscan = off"))
            plan = "\n".join(
                session.scalars(
                    text(
                        "EXPLAIN (COSTS OFF) "
                        "SELECT id FROM stations "
                        "WHERE is_active AND location IS NOT NULL "
                        "AND ST_DWithin("
                        "location, ST_GeomFromText(:route_wkt, 4326)::geography, :radius_meters"
                        ")"
                    ),
                    {"route_wkt": route_wkt, "radius_meters": 25_000},
                )
            )
            assert "ix_stations_location_gist" in plan
    finally:
        engine.dispose()
        get_settings.cache_clear()
