from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session

from compass.etl.mimit import MimitParseError
from compass.etl.service import ingest_mimit, ingest_osm
from compass.models import (
    Base,
    IngestionRun,
    RawMimitCngPrice,
    RawMimitStation,
    RawOsmCngFeature,
    RawSourceSnapshot,
)

FIXTURES = Path(__file__).parent / "fixtures"


@contextmanager
def _session() -> Iterator[Session]:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(
        engine,
        tables=[
            RawSourceSnapshot.__table__,
            IngestionRun.__table__,
            RawMimitStation.__table__,
            RawMimitCngPrice.__table__,
            RawOsmCngFeature.__table__,
        ],
    )
    try:
        with Session(engine) as session:
            yield session
    finally:
        engine.dispose()


def _count(session: Session, model: type[Base]) -> int:
    return session.scalar(select(func.count()).select_from(model)) or 0


def test_repeated_mimit_fixture_ingestion_is_idempotent() -> None:
    with _session() as session:
        kwargs = {
            "stations_content": (FIXTURES / "mimit_stations.csv").read_bytes(),
            "prices_content": (FIXTURES / "mimit_prices.csv").read_bytes(),
            "stations_url": "fixture:///mimit_stations.csv",
            "prices_url": "fixture:///mimit_prices.csv",
        }
        first = ingest_mimit(session, **kwargs)
        second = ingest_mimit(session, **kwargs)

        assert first["reused"] is False
        assert second["reused"] is True
        assert first["run_id"] == second["run_id"]
        assert first["source_observed_at"] == second["source_observed_at"]
        assert _count(session, IngestionRun) == 1
        assert _count(session, RawSourceSnapshot) == 2
        assert _count(session, RawMimitStation) == 2
        assert _count(session, RawMimitCngPrice) == 2
        assert first["metrics"]["station_rows_seen"] == 3


def test_repeated_osm_fixture_ingestion_is_idempotent() -> None:
    with _session() as session:
        content = (FIXTURES / "osm_cng.json").read_bytes()
        later_timestamp = content.replace(b"2026-08-25T07:55:00Z", b"2026-08-25T07:56:00Z")
        first = ingest_osm(session, content=content, source_url="fixture:///osm_cng.json")
        second = ingest_osm(session, content=later_timestamp, source_url="fixture:///osm_cng.json")

        assert first["reused"] is False
        assert second["reused"] is True
        assert first["run_id"] == second["run_id"]
        assert first["snapshot_sha256"] == second["snapshot_sha256"]
        assert _count(session, IngestionRun) == 1
        assert _count(session, RawSourceSnapshot) == 1
        assert _count(session, RawOsmCngFeature) == 2
        assert first["metrics"] == {
            "elements_seen": 3,
            "cng_features": 2,
            "features_without_coordinates": 0,
            "content_identity_version": "osm-cng-v1",
        }


def test_changed_osm_feature_content_creates_a_new_run() -> None:
    with _session() as session:
        content = (FIXTURES / "osm_cng.json").read_bytes()
        changed_content = content.replace(b"Milano CNG", b"Milano CNG Updated")

        first = ingest_osm(session, content=content, source_url="fixture:///osm_cng.json")
        changed = ingest_osm(
            session,
            content=changed_content,
            source_url="fixture:///osm_cng.json",
        )

        assert first["reused"] is False
        assert changed["reused"] is False
        assert first["run_id"] != changed["run_id"]
        assert first["snapshot_sha256"] != changed["snapshot_sha256"]
        assert _count(session, IngestionRun) == 2
        assert _count(session, RawSourceSnapshot) == 2
        assert _count(session, RawOsmCngFeature) == 4


def test_mimit_rejects_mixed_daily_snapshots() -> None:
    with _session() as session:
        stations = (FIXTURES / "mimit_stations.csv").read_bytes()
        prices = (
            (FIXTURES / "mimit_prices.csv").read_bytes().replace(b"2026-08-25", b"2026-08-24", 1)
        )

        with pytest.raises(MimitParseError, match="extraction dates do not match"):
            ingest_mimit(
                session,
                stations_content=stations,
                prices_content=prices,
                stations_url="fixture:///mimit_stations.csv",
                prices_url="fixture:///mimit_prices.csv",
            )
