import os
from datetime import UTC, datetime
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, event
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from compass.config import get_settings
from compass.etl.service import ingest_mimit, ingest_osm
from compass.freshness.service import load_data_freshness
from compass.reconciliation.service import normalize_and_reconcile
from compass.stations.repository import load_station_detail

FIXTURES = Path(__file__).parent / "fixtures"
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL")


@pytest.mark.integration
@pytest.mark.skipif(not TEST_DATABASE_URL, reason="TEST_DATABASE_URL is not configured")
def test_phase7_public_reads_use_normalized_postgis_data(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    assert TEST_DATABASE_URL is not None
    database_name = make_url(TEST_DATABASE_URL).database or ""
    assert database_name.endswith("_test"), "integration tests require a disposable *_test database"
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

            station_queries: list[str] = []

            def record_station_query(
                _connection: object,
                _cursor: object,
                statement: str,
                _parameters: object,
                _context: object,
                _executemany: object,
            ) -> None:
                station_queries.append(statement)

            event.listen(engine, "before_cursor_execute", record_station_query)
            try:
                station = load_station_detail(session, "1001")
            finally:
                event.remove(engine, "before_cursor_execute", record_station_query)
            assert station is not None
            assert len(station_queries) == 1
            assert station.is_active is True
            assert station.latitude == pytest.approx(45.4642)
            assert station.longitude == pytest.approx(9.19)
            assert len(station.current_cng_prices) == 1
            assert str(station.current_cng_prices[0].unit_price) == "1.499"
            assert station.current_cng_prices[0].currency == "EUR"
            assert station.current_cng_prices[0].unit == "kg"
            assert station.osm is not None
            assert station.osm.opening_hours == "24/7"
            assert station.osm.phone == "+39 02 123456"
            assert station.osm.confidence > 0

            freshness = load_data_freshness(
                session,
                mimit_threshold_seconds=48 * 3600,
                osm_threshold_seconds=7 * 24 * 3600,
                reconciliation_threshold_seconds=48 * 3600,
                evaluated_at=datetime.now(UTC),
            )
            assert freshness.overall_state in {"ready", "degraded"}
            assert freshness.mimit.state != "missing"
            assert freshness.osm.state != "missing"
            assert freshness.reconciliation.state != "missing"
    finally:
        engine.dispose()
        get_settings.cache_clear()
