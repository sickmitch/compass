from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Literal


@dataclass(frozen=True, slots=True)
class StationCurrentPriceRecord:
    unit_price: Decimal
    currency: str
    unit: str
    service_mode: Literal["self", "served"]
    observed_at: datetime
    ingested_at: datetime
    source_name: str


@dataclass(frozen=True, slots=True)
class StationOsmEnrichment:
    osm_type: str
    osm_id: int
    name: str | None
    opening_hours: str | None
    phone: str | None
    brand: str | None
    operator: str | None
    source_observed_at: datetime | None
    match_method: str
    confidence: float
    distance_meters: float | None
    is_manual: bool


@dataclass(frozen=True, slots=True)
class StationDetail:
    station_id: int
    mimit_station_id: str
    name: str | None
    address: str | None
    municipality: str | None
    province: str | None
    brand: str | None
    manager: str | None
    station_type: str | None
    latitude: float | None
    longitude: float | None
    location_source: str | None
    is_active: bool
    source_observed_at: datetime | None
    updated_at: datetime
    current_cng_prices: tuple[StationCurrentPriceRecord, ...]
    osm: StationOsmEnrichment | None
