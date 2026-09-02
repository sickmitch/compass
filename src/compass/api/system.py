from dataclasses import asdict
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import Field
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.freshness.service import load_data_freshness
from compass.traffic.domain import TrafficHealthState
from compass.traffic.service import traffic_health_from_settings

router = APIRouter(prefix="/api/v1", tags=["operations"])


class DataSourceFreshnessResponse(StrictModel):
    source_name: str
    state: Literal["fresh", "stale", "future_observation", "missing"]
    source_observed_at: datetime | None
    completed_at: datetime | None
    age_seconds: float | None = Field(default=None)
    freshness_threshold_seconds: float = Field(gt=0)


class DataFreshnessResponse(StrictModel):
    evaluated_at: datetime
    overall_state: Literal["ready", "degraded", "unavailable"]
    sources: list[DataSourceFreshnessResponse] = Field(min_length=3, max_length=3)
    traffic_state: TrafficHealthState


@router.get(
    "/data-freshness",
    response_model=DataFreshnessResponse,
    responses={503: {"model": ErrorResponse, "description": "Freshness data unavailable."}},
)
async def data_freshness(
    session: Annotated[Session, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> DataFreshnessResponse | JSONResponse:
    try:
        report = load_data_freshness(
            session,
            mimit_threshold_seconds=settings.mimit_data_freshness_hours * 3600,
            osm_threshold_seconds=settings.osm_data_freshness_hours * 3600,
            reconciliation_threshold_seconds=(settings.reconciliation_data_freshness_hours * 3600),
        )
    except SQLAlchemyError:
        return error_response(
            503,
            "data_freshness_unavailable",
            "Data freshness could not be evaluated.",
        )
    return DataFreshnessResponse(
        evaluated_at=report.evaluated_at,
        overall_state=report.overall_state,
        sources=[
            DataSourceFreshnessResponse.model_validate(asdict(item))
            for item in (report.mimit, report.osm, report.reconciliation)
        ],
        traffic_state=traffic_health_from_settings(settings).provider_status,
    )
