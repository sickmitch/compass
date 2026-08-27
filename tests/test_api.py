import asyncio
from collections.abc import AsyncIterator

import httpx
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from compass.api.main import app
from compass.db import get_session


def _get(path: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path)

    return asyncio.run(request())


def test_liveness_does_not_claim_dependency_readiness() -> None:
    response = _get("/health/live")
    assert response.status_code == 200
    assert response.json()["database"] == "not_checked"


def test_readiness_checks_database() -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")

    async def override_session() -> AsyncIterator[Session]:
        with Session(engine) as session:
            yield session

    app.dependency_overrides[get_session] = override_session
    try:
        response = _get("/health/ready")
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

    assert response.status_code == 200
    assert response.json()["database"] == "ready"
