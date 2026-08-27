from alembic import command
from alembic.config import Config

from compass.config import get_settings


def test_offline_migration_accepts_percent_in_database_password(monkeypatch, capsys) -> None:
    monkeypatch.setenv(
        "DATABASE_URL",
        "postgresql+psycopg://compass:sicKm17cH93%@db:5432/compass",
    )
    get_settings.cache_clear()
    try:
        command.upgrade(Config("alembic.ini"), "head", sql=True)
    finally:
        get_settings.cache_clear()

    generated_sql = capsys.readouterr().out
    assert "CREATE EXTENSION IF NOT EXISTS postgis" in generated_sql
    assert "CREATE TABLE ingestion_runs" in generated_sql
