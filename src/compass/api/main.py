from contextlib import asynccontextmanager
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass import __version__
from compass.api.along_route_search import router as along_route_search_router
from compass.api.auth import (
    ApiAuthenticationError,
    require_api_user,
)
from compass.api.auth import (
    router as auth_router,
)
from compass.api.contracts import ErrorResponse
from compass.api.predictive import router as predictive_router
from compass.api.ranking import router as ranking_router
from compass.api.routes import router as routes_router
from compass.api.search import router as search_router
from compass.api.stations import router as stations_router
from compass.api.system import router as system_router
from compass.api.traffic import router as traffic_router
from compass.config import Settings, get_api_settings, get_settings
from compass.db import get_session
from compass.freshness.service import load_data_freshness
from compass.logging import configure_logging
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import RoutingProvider
from compass.search.dependencies import close_destination_search_runtime
from compass.traffic.domain import TrafficHealthState
from compass.traffic.service import traffic_health_from_settings

configure_logging(get_settings().log_level)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    yield
    await close_destination_search_runtime()


app = FastAPI(
    title="Compass CNG API",
    version=__version__,
    description="CNG-aware navigation API foundations for Italy.",
    lifespan=lifespan,
)
protected_api_dependencies = [Depends(require_api_user)]
protected_api_responses = {
    401: {
        "description": "Missing or invalid Compass API credentials.",
        "model": ErrorResponse,
    }
}
app.include_router(auth_router)
app.include_router(
    routes_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)
app.include_router(
    search_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)
app.include_router(
    along_route_search_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)
app.include_router(
    ranking_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)
app.include_router(
    predictive_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)
app.include_router(
    stations_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)
app.include_router(
    system_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)
app.include_router(
    traffic_router,
    dependencies=protected_api_dependencies,
    responses=protected_api_responses,
)


@app.exception_handler(RequestValidationError)
async def request_validation_error(
    _request: object, _error: RequestValidationError
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={"code": "invalid_request", "message": "The request payload is invalid."},
    )


@app.exception_handler(ApiAuthenticationError)
async def api_authentication_error(
    _request: object, _error: ApiAuthenticationError
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_401_UNAUTHORIZED,
        headers={"WWW-Authenticate": 'Basic realm="Compass API", charset="UTF-8"'},
        content={
            "code": "invalid_credentials",
            "message": "Valid Compass API credentials are required.",
        },
    )


class HealthResponse(BaseModel):
    status: Literal["ok", "not_ready"]
    service: str = "compass-api"
    version: str = __version__
    database: Literal["not_checked", "ready", "unavailable"]
    routing: Literal["not_checked", "ready", "unavailable"]
    data: Literal["not_checked", "ready", "degraded", "unavailable"]
    traffic: Literal["not_checked"] | TrafficHealthState


@app.get("/health/live", response_model=HealthResponse, tags=["health"])
async def live() -> HealthResponse:
    return HealthResponse(
        status="ok",
        database="not_checked",
        routing="not_checked",
        data="not_checked",
        traffic="not_checked",
    )


@app.get("/health/ready", response_model=HealthResponse, tags=["health"])
async def ready(
    response: Response,
    session: Annotated[Session, Depends(get_session)],
    routing_provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> HealthResponse:
    database_state: Literal["ready", "unavailable"] = "ready"
    try:
        session.execute(text("SELECT 1"))
    except SQLAlchemyError:
        database_state = "unavailable"

    routing_state: Literal["ready", "unavailable"] = (
        "ready" if await routing_provider.is_ready() else "unavailable"
    )
    data_state: Literal["ready", "degraded", "unavailable"] = "unavailable"
    traffic_state = traffic_health_from_settings(settings).provider_status
    if database_state == "ready":
        try:
            freshness = load_data_freshness(
                session,
                mimit_threshold_seconds=settings.mimit_data_freshness_hours * 3600,
                osm_threshold_seconds=settings.osm_data_freshness_hours * 3600,
                reconciliation_threshold_seconds=(
                    settings.reconciliation_data_freshness_hours * 3600
                ),
            )
            data_state = freshness.overall_state
        except SQLAlchemyError:
            data_state = "unavailable"
    if (
        database_state == "unavailable"
        or routing_state == "unavailable"
        or data_state == "unavailable"
    ):
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthResponse(
            status="not_ready",
            database=database_state,
            routing=routing_state,
            data=data_state,
            traffic=traffic_state,
        )
    return HealthResponse(
        status="ok",
        database="ready",
        routing="ready",
        data=data_state,
        traffic=traffic_state,
    )
