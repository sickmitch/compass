"""Requires a dedicated test database; never point TEST_DATABASE_URL at production."""
import os
from datetime import UTC, datetime
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from compass.enforcement.importer import ingest
from compass.etl.acquisition import DownloadedPayload


@pytest.mark.integration
def test_enforcement_import_is_atomic_idempotent_and_spatial(monkeypatch):
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("Set TEST_DATABASE_URL to a dedicated PostGIS test database")
    if "test" not in (make_url(url).database or "").lower():
        pytest.fail("Refusing a database whose name does not contain test")
    from compass.config import get_settings

    monkeypatch.setenv("DATABASE_URL", url)
    get_settings.cache_clear()
    config = Config("alembic.ini")
    command.upgrade(config, "head")
    engine = create_engine(url)
    body = Path("tests/fixtures/enforcement/inventory.json").read_bytes()
    data = DownloadedPayload(body, "fixture://enforcement", "application/json", datetime.now(UTC))
    try:
        with Session(engine) as session:
            first = ingest(session, data, "TEST")
            second = ingest(session, data, "TEST")
            assert first["import_id"] == second["import_id"]
            assert second["status"] == "unchanged"
            rows = session.execute(text("""
                SELECT count(*) FROM enforcement_controls WHERE scope='TEST' AND active
                AND ST_DWithin(location,ST_SetSRID(ST_MakePoint(9,45),4326)::geography,1000)
            """)).scalar_one()
            assert rows == 3
            session.rollback()
            with pytest.raises(ValueError):
                ingest(session, DownloadedPayload(b'{"remark":"timeout","elements":[]}',
                       data.source_url, data.content_type, data.fetched_at), "TEST")
            assert session.execute(text(
                "SELECT count(*) FROM enforcement_controls WHERE scope='TEST' AND active"
            )).scalar_one() == 3
    finally:
        with engine.begin() as connection:
            connection.execute(text("DELETE FROM enforcement_controls WHERE scope='TEST'"))
            connection.execute(text("DELETE FROM enforcement_imports WHERE scope='TEST'"))
        engine.dispose()
