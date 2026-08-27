import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from compass.config import get_settings
from compass.etl.service import ingest_mimit, ingest_osm
from compass.models import (
    OsmFeature,
    ReconciliationResult,
    Station,
    StationCurrentPrice,
    StationOsmLink,
    StationPrice,
)
from compass.reconciliation.service import normalize_and_reconcile, set_match_override

FIXTURES = Path(__file__).parent / "fixtures"
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL")


def _count(session: Session, model: type[object]) -> int:
    return session.scalar(select(func.count()).select_from(model)) or 0


@pytest.mark.integration
@pytest.mark.skipif(not TEST_DATABASE_URL, reason="TEST_DATABASE_URL is not configured")
def test_phase2_pipeline_on_postgis(monkeypatch: pytest.MonkeyPatch) -> None:
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

            first = normalize_and_reconcile(session)
            assert first["reused"] is False
            assert first["metrics"]["matched"] == 1
            assert first["metrics"]["ambiguous"] == 1
            assert first["metrics"]["unmatched"] == 2
            assert first["metrics"]["candidate_pairs"] == 3
            assert first["metrics"]["osm_features_unmatched"] == 3
            assert _count(session, Station) == 4
            assert _count(session, OsmFeature) == 4
            assert _count(session, StationPrice) == 4
            assert _count(session, StationCurrentPrice) == 4
            assert _count(session, StationOsmLink) == 1

            statuses = {
                station_id: status
                for station_id, status in session.execute(
                    select(Station.mimit_station_id, ReconciliationResult.status)
                    .join(
                        ReconciliationResult,
                        ReconciliationResult.station_id == Station.id,
                    )
                    .where(
                        ReconciliationResult.reconciliation_run_id == first["reconciliation_run_id"]
                    )
                )
            }
            assert statuses == {
                "1001": "matched",
                "1002": "ambiguous",
                "1003": "unmatched",
                "1004": "unmatched",
            }
            assert (
                session.scalar(
                    select(func.ST_SRID(Station.location)).where(Station.mimit_station_id == "1001")
                )
                == 4326
            )
            assert (
                session.scalar(select(Station.location).where(Station.mimit_station_id == "1004"))
                is None
            )

            gist_indexes = set(
                session.scalars(
                    text(
                        "SELECT indexname FROM pg_indexes "
                        "WHERE schemaname = 'public' AND indexdef ILIKE '%USING gist%'"
                    )
                )
            )
            assert "ix_stations_location_gist" in gist_indexes
            assert "ix_osm_cng_features_location_gist" in gist_indexes

            representative = session.execute(
                select(
                    Station.mimit_station_id,
                    StationPrice.unit_price,
                    OsmFeature.opening_hours,
                    OsmFeature.phone,
                )
                .join(
                    StationCurrentPrice,
                    StationCurrentPrice.station_id == Station.id,
                )
                .join(
                    StationPrice,
                    StationPrice.id == StationCurrentPrice.station_price_id,
                )
                .join(StationOsmLink, StationOsmLink.station_id == Station.id)
                .join(OsmFeature, OsmFeature.id == StationOsmLink.osm_feature_id)
                .where(Station.mimit_station_id == "1001")
            ).one()
            assert representative.mimit_station_id == "1001"
            assert str(representative.unit_price) == "1.499"
            assert representative.opening_hours == "24/7"
            assert representative.phone == "+39 02 123456"

            repeated = normalize_and_reconcile(session)
            assert repeated["reused"] is True
            assert repeated["reconciliation_run_id"] == first["reconciliation_run_id"]
            assert _count(session, StationPrice) == 4

            set_match_override(
                session,
                mimit_station_id="1002",
                action="link",
                osm_type="node",
                osm_id=201,
                reason="deterministic integration fixture",
                created_by="pytest",
            )
            overridden = normalize_and_reconcile(session)
            assert overridden["reused"] is False
            assert overridden["configuration_sha256"] != first["configuration_sha256"]
            assert overridden["metrics"]["matched"] == 2
            assert overridden["metrics"]["ambiguous"] == 0
            assert overridden["metrics"]["unmatched"] == 2
            assert overridden["metrics"]["manual_links"] == 1
            assert _count(session, StationOsmLink) == 2
    finally:
        engine.dispose()
        get_settings.cache_clear()
