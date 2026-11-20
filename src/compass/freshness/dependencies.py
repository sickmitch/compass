from typing import Annotated

from fastapi import Depends
from sqlalchemy.orm import Session

from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.freshness.domain import DataFreshnessReport
from compass.freshness.service import load_data_freshness


async def get_data_freshness_report(
    session: Annotated[Session, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> DataFreshnessReport:
    return load_data_freshness(
        session,
        mimit_threshold_seconds=settings.mimit_data_freshness_hours * 3600,
        osm_threshold_seconds=settings.osm_data_freshness_hours * 3600,
        reconciliation_threshold_seconds=(settings.reconciliation_data_freshness_hours * 3600),
    )
