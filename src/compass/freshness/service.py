from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from compass.freshness.domain import DataFreshnessReport, DataSourceFreshness
from compass.models import IngestionRun, ReconciliationRun


def load_data_freshness(
    session: Session,
    *,
    mimit_threshold_seconds: float,
    osm_threshold_seconds: float,
    reconciliation_threshold_seconds: float,
    evaluated_at: datetime | None = None,
) -> DataFreshnessReport:
    evaluated_at = _aware_utc(evaluated_at or datetime.now(UTC))
    mimit_row = session.execute(
        select(IngestionRun.source_observed_at, IngestionRun.completed_at)
        .where(
            IngestionRun.source_name == "mimit_cng",
            IngestionRun.status == "completed",
        )
        .order_by(IngestionRun.completed_at.desc(), IngestionRun.id.desc())
        .limit(1)
    ).one_or_none()
    osm_row = session.execute(
        select(IngestionRun.source_observed_at, IngestionRun.completed_at)
        .where(
            IngestionRun.source_name == "osm_cng",
            IngestionRun.status == "completed",
        )
        .order_by(IngestionRun.completed_at.desc(), IngestionRun.id.desc())
        .limit(1)
    ).one_or_none()
    reconciliation_completed_at = session.scalar(
        select(ReconciliationRun.completed_at)
        .where(ReconciliationRun.status == "completed")
        .order_by(ReconciliationRun.completed_at.desc(), ReconciliationRun.id.desc())
        .limit(1)
    )

    mimit = _evaluate(
        "mimit_cng",
        source_observed_at=mimit_row.source_observed_at if mimit_row else None,
        completed_at=mimit_row.completed_at if mimit_row else None,
        threshold_seconds=mimit_threshold_seconds,
        evaluated_at=evaluated_at,
    )
    osm = _evaluate(
        "osm_cng",
        source_observed_at=osm_row.source_observed_at if osm_row else None,
        completed_at=osm_row.completed_at if osm_row else None,
        threshold_seconds=osm_threshold_seconds,
        evaluated_at=evaluated_at,
    )
    reconciliation = _evaluate(
        "reconciliation",
        source_observed_at=reconciliation_completed_at,
        completed_at=reconciliation_completed_at,
        threshold_seconds=reconciliation_threshold_seconds,
        evaluated_at=evaluated_at,
    )
    required_missing = mimit.state == "missing" or reconciliation.state == "missing"
    if required_missing:
        overall_state = "unavailable"
    elif any(item.state != "fresh" for item in (mimit, osm, reconciliation)):
        overall_state = "degraded"
    else:
        overall_state = "ready"
    return DataFreshnessReport(
        evaluated_at=evaluated_at,
        overall_state=overall_state,
        mimit=mimit,
        osm=osm,
        reconciliation=reconciliation,
    )


def _evaluate(
    source_name: str,
    *,
    source_observed_at: datetime | None,
    completed_at: datetime | None,
    threshold_seconds: float,
    evaluated_at: datetime,
) -> DataSourceFreshness:
    reference = source_observed_at or completed_at
    if reference is None:
        state = "missing"
        age_seconds = None
    else:
        age_seconds = (evaluated_at - _aware_utc(reference)).total_seconds()
        if age_seconds < 0:
            state = "future_observation"
        elif age_seconds <= threshold_seconds:
            state = "fresh"
        else:
            state = "stale"
    return DataSourceFreshness(
        source_name=source_name,
        state=state,
        source_observed_at=source_observed_at,
        completed_at=completed_at,
        age_seconds=age_seconds,
        freshness_threshold_seconds=threshold_seconds,
    )


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)
