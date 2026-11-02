from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.api.contracts import StrictModel, error_response
from compass.config import get_settings
from compass.db import get_session
from compass.enforcement.identity import physical_control_key
from compass.enforcement.road_context import (
    RoadContext,
    RoadContextRequest,
    RoadContextUnavailable,
    match_context,
)

router = APIRouter(prefix="/api/v1/enforcement", tags=["enforcement"])


class Control(StrictModel):
    source_key: str
    physical_control_key: str | None = None
    kind: Literal["fixed", "average_speed"]
    latitude: float
    longitude: float
    direction_degrees: float | None
    speed_limit_kph: float | None
    fetched_at: datetime
    association_state: Literal["unmatched"] = "unmatched"
    # Inventory is not yet a directed-road match and must not trigger driving alerts.
    alert_eligible: Literal[False] = False


class Inventory(StrictModel):
    controls: list[Control]
    truncated: bool
    attribution: str = "© OpenStreetMap contributors — ODbL 1.0"


@router.get("", response_model=Inventory)
def inventory(
    latitude: Annotated[float, Query(ge=-90, le=90)],
    longitude: Annotated[float, Query(ge=-180, le=180)],
    session: Annotated[Session, Depends(get_session)],
    radius_meters: Annotated[int, Query(ge=100, le=10_000)] = 2_000,
):
    try:
        rows = session.execute(text("""
            SELECT source_key,kind,ST_Y(location::geometry) AS latitude,
                   ST_X(location::geometry) AS longitude,direction_degrees,
                   speed_limit_kph,fetched_at,raw
            FROM enforcement_controls
            WHERE active AND ST_DWithin(location,
                ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,:radius)
            ORDER BY ST_Distance(location,
                ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography),source_key
            LIMIT 501
        """), {"lat": latitude, "lon": longitude, "radius": radius_meters}).mappings().all()
    except SQLAlchemyError:
        return error_response(503, "enforcement_unavailable", "Enforcement inventory unavailable.")
    controls = [
        Control(**{key: value for key, value in row.items() if key != "raw"},
                physical_control_key=physical_control_key(
                    row["source_key"], row["kind"], row["raw"],
                ))
        for row in rows[:500]
    ]
    return Inventory(controls=controls, truncated=len(rows) > 500)


@router.post("/road-context", response_model=RoadContext)
async def road_context(body: RoadContextRequest, request: Request, response: Response):
    response.headers["Cache-Control"] = "no-store"
    try:
        return await match_context(body, request.app.state.routing_http_client, get_settings())
    except RoadContextUnavailable:
        return error_response(503, "road_context_unavailable", "Road context unavailable.")
