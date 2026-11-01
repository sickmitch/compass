from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.api.contracts import StrictModel, error_response
from compass.db import get_session

router = APIRouter(prefix="/api/v1/enforcement", tags=["enforcement"])


class Control(StrictModel):
    source_key: str
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
                   speed_limit_kph,fetched_at
            FROM enforcement_controls
            WHERE active AND ST_DWithin(location,
                ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,:radius)
            ORDER BY ST_Distance(location,
                ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography),source_key
            LIMIT 501
        """), {"lat": latitude, "lon": longitude, "radius": radius_meters}).mappings().all()
    except SQLAlchemyError:
        return error_response(503, "enforcement_unavailable", "Enforcement inventory unavailable.")
    return Inventory(controls=[Control(**row) for row in rows[:500]], truncated=len(rows) > 500)
