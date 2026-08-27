from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Response, status
from pydantic import BaseModel
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass import __version__
from compass.config import get_settings
from compass.db import get_session
from compass.logging import configure_logging

configure_logging(get_settings().log_level)

app = FastAPI(
    title="Compass CNG API",
    version=__version__,
    description="Health scaffolding for the Compass CNG navigation platform.",
)


class HealthResponse(BaseModel):
    status: Literal["ok", "not_ready"]
    service: str = "compass-api"
    version: str = __version__
    database: Literal["not_checked", "ready", "unavailable"]


@app.get("/health/live", response_model=HealthResponse, tags=["health"])
async def live() -> HealthResponse:
    return HealthResponse(status="ok", database="not_checked")


@app.get("/health/ready", response_model=HealthResponse, tags=["health"])
async def ready(
    response: Response, session: Annotated[Session, Depends(get_session)]
) -> HealthResponse:
    try:
        session.execute(text("SELECT 1"))
    except SQLAlchemyError:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthResponse(status="not_ready", database="unavailable")
    return HealthResponse(status="ok", database="ready")
