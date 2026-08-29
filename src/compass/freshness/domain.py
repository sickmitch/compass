from dataclasses import dataclass
from datetime import datetime
from typing import Literal

FreshnessState = Literal["fresh", "stale", "future_observation", "missing"]


@dataclass(frozen=True, slots=True)
class DataSourceFreshness:
    source_name: str
    state: FreshnessState
    source_observed_at: datetime | None
    completed_at: datetime | None
    age_seconds: float | None
    freshness_threshold_seconds: float


@dataclass(frozen=True, slots=True)
class DataFreshnessReport:
    evaluated_at: datetime
    overall_state: Literal["ready", "degraded", "unavailable"]
    mimit: DataSourceFreshness
    osm: DataSourceFreshness
    reconciliation: DataSourceFreshness
    traffic_state: Literal["not_configured"] = "not_configured"
