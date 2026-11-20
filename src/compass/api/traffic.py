from dataclasses import asdict
from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import Field

from compass.api.contracts import StrictModel
from compass.config import Settings, get_api_settings
from compass.traffic.domain import TrafficHealthState
from compass.traffic.service import traffic_health_from_settings

router = APIRouter(prefix="/api/v1", tags=["traffic"])


class TrafficHealthResponse(StrictModel):
    enabled: bool
    provider: str
    provider_status: TrafficHealthState
    traffic_aware_routing: bool
    last_fetch_started_at: datetime | None
    last_fetch_completed_at: datetime | None
    last_success_at: datetime | None
    provider_segments_received: int = Field(ge=0)
    segments_normalized: int = Field(ge=0)
    segments_matched: int = Field(ge=0)
    segments_unmatched: int = Field(ge=0)
    edges_updated: int = Field(ge=0)
    edges_expired: int = Field(ge=0)
    provider_api_errors: int = Field(ge=0)
    updater_consecutive_failures: int = Field(ge=0)
    managed_edge_count: int = Field(ge=0)
    feed_age_seconds: float | None = Field(default=None, ge=0)
    mapping_version: str | None
    valhalla_tileset_version: str | None
    traffic_extract_path: str | None
    message: str | None


@router.get("/traffic/health", response_model=TrafficHealthResponse)
async def traffic_health(
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> TrafficHealthResponse:
    return TrafficHealthResponse.model_validate(asdict(traffic_health_from_settings(settings)))
