"""Requires a dedicated test database; never point TEST_DATABASE_URL at production."""
import json
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
            # Real spatial join, including an obsolete graph proof that must not leak.
            from compass.enforcement.packages import read_package

            session.execute(text("""
                INSERT INTO enforcement_associations
                    (source_key,import_id,graph_identity,state,reason,evidence)
                VALUES ('osm:relation:900000000004',:id,'old:1','matched','fixture','{}')
                ON CONFLICT(source_key) DO UPDATE SET graph_identity='old:1',
                    import_id=excluded.import_id
            """), {"id": first["import_id"]})
            package = read_package(session, "current:2", 45, 9, 1000)
            controls = {c.source_key: c for c in package.controls}
            assert "osm:node:900000000001" in controls
            control = controls["osm:relation:900000000004"]
            assert control.association_state == "stale"
            assert control.directed_path is None
            assert not control.alert_eligible
            outside = read_package(session, "current:2", 0, 0, 100)
            assert not outside.controls
            session.rollback()
            with pytest.raises(ValueError):
                ingest(session, DownloadedPayload(b'{"remark":"timeout","elements":[]}',
                       data.source_url, data.content_type, data.fetched_at), "TEST")
            assert session.execute(text(
                "SELECT count(*) FROM enforcement_controls WHERE scope='TEST' AND active"
            )).scalar_one() == 3
            session.rollback()
            mixed = json.loads(body)
            # A present but unusable camera must not retire its previous valid record.
            mixed["elements"][1]["lat"] = 91
            result = ingest(session, DownloadedPayload(
                json.dumps(mixed).encode(), data.source_url, data.content_type, data.fetched_at,
            ), "TEST")
            assert result["count"] == 2
            assert result["rejected_count"] == 1
            assert session.execute(text(
                "SELECT count(*) FROM enforcement_controls WHERE scope='TEST' AND active"
            )).scalar_one() == 3
            rejected = session.execute(text(
                "SELECT rejected FROM enforcement_imports WHERE id=:id"
            ), {"id": result["import_id"]}).scalar_one()
            assert rejected[0]["source_key"] == "osm:node:900000000002"
    finally:
        with engine.begin() as connection:
            connection.execute(text("DELETE FROM enforcement_controls WHERE scope='TEST'"))
            connection.execute(text("DELETE FROM enforcement_imports WHERE scope='TEST'"))
        engine.dispose()
