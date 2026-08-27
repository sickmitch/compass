from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any


@dataclass(frozen=True)
class MimitStationRecord:
    row_number: int
    dataset_date: date | None
    station_id: str
    manager: str | None
    brand: str | None
    station_type: str | None
    name: str | None
    address: str | None
    municipality: str | None
    province: str | None
    latitude: Decimal | None
    longitude: Decimal | None
    raw_record: dict[str, str]


@dataclass(frozen=True)
class MimitPriceRecord:
    row_number: int
    dataset_date: date | None
    station_id: str
    source_fuel_name: str
    unit_price: Decimal
    is_self_service: bool
    observed_at: datetime
    raw_record: dict[str, str]


@dataclass(frozen=True)
class OsmCngFeature:
    osm_type: str
    osm_id: int
    latitude: Decimal | None
    longitude: Decimal | None
    tags: dict[str, Any]
    raw_element: dict[str, Any]


@dataclass(frozen=True)
class ParsedDataset[T]:
    records: list[T]
    dataset_date: date | None
    rows_seen: int
