import csv
import io
import re
import unicodedata
from datetime import date, datetime, time
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo

from compass.etl.types import MimitPriceRecord, MimitStationRecord, ParsedDataset

ROME = ZoneInfo("Europe/Rome")
DATE_PATTERNS = ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y")


class MimitParseError(ValueError):
    pass


def _decode(payload: bytes) -> str:
    try:
        return payload.decode("utf-8-sig")
    except UnicodeDecodeError:
        return payload.decode("cp1252")


def _key(value: str) -> str:
    value = unicodedata.normalize("NFKD", value)
    value = "".join(char for char in value if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]", "", value.casefold())


def _text(value: str | None) -> str | None:
    cleaned = (value or "").strip()
    return cleaned or None


def _parse_date(value: str) -> date | None:
    for candidate in re.findall(r"\d{4}-\d{2}-\d{2}|\d{2}[/-]\d{2}[/-]\d{4}", value):
        for pattern in DATE_PATTERNS:
            try:
                return datetime.strptime(candidate, pattern).date()
            except ValueError:
                pass
    return None


def _table(
    payload: bytes, required_headers: set[str]
) -> tuple[list[dict[str, str]], date | None, int]:
    text = _decode(payload)
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        raise MimitParseError("MIMIT payload is empty")

    dataset_date = _parse_date("\n".join(lines[:3]))
    header_index = -1
    delimiter = "|"
    for index, line in enumerate(lines[:10]):
        candidate_delimiter = "|" if line.count("|") >= line.count(";") else ";"
        headers = {_key(item) for item in next(csv.reader([line], delimiter=candidate_delimiter))}
        if required_headers.issubset(headers):
            header_index = index
            delimiter = candidate_delimiter
            break
    if header_index < 0:
        raise MimitParseError(
            f"MIMIT header missing required columns: {', '.join(sorted(required_headers))}"
        )

    reader = csv.DictReader(io.StringIO("\n".join(lines[header_index:])), delimiter=delimiter)
    rows: list[dict[str, str]] = []
    for row in reader:
        if not row or not any((value or "").strip() for value in row.values()):
            continue
        rows.append({_key(key): (value or "").strip() for key, value in row.items() if key})
    return rows, dataset_date, header_index + 2


def _coordinate(value: str | None, minimum: Decimal, maximum: Decimal) -> Decimal | None:
    if not _text(value):
        return None
    try:
        coordinate = Decimal(value.strip().replace(",", "."))
    except InvalidOperation:
        return None
    return coordinate if minimum <= coordinate <= maximum else None


def parse_stations(payload: bytes) -> ParsedDataset[MimitStationRecord]:
    rows, dataset_date, first_data_row = _table(payload, {"idimpianto", "comune", "provincia"})
    records: list[MimitStationRecord] = []
    for offset, row in enumerate(rows):
        station_id = (row.get("idimpianto") or "").strip()
        if not station_id:
            raise MimitParseError(f"station row {first_data_row + offset} has no idImpianto")
        records.append(
            MimitStationRecord(
                row_number=first_data_row + offset,
                dataset_date=dataset_date,
                station_id=station_id,
                manager=_text(row.get("gestore")),
                brand=_text(row.get("bandiera")),
                station_type=_text(row.get("tipoimpianto")),
                name=_text(row.get("nomeimpianto")),
                address=_text(row.get("indirizzo")),
                municipality=_text(row.get("comune")),
                province=_text(row.get("provincia")),
                latitude=_coordinate(row.get("latitudine"), Decimal("-90"), Decimal("90")),
                longitude=_coordinate(row.get("longitudine"), Decimal("-180"), Decimal("180")),
                raw_record=row,
            )
        )
    return ParsedDataset(records=records, dataset_date=dataset_date, rows_seen=len(rows))


def _is_cng(fuel_name: str) -> bool:
    normalized = _key(fuel_name)
    if "lng" in normalized or "gnl" in normalized or "liquefatto" in normalized:
        return False
    return normalized == "cng" or "metano" in normalized or "gasnaturalecompresso" in normalized


def _parse_observed_at(value: str, row_number: int) -> datetime:
    candidate = value.strip()
    for pattern in ("%d/%m/%Y %H:%M:%S", "%Y-%m-%d %H:%M:%S", "%d/%m/%Y %H:%M"):
        try:
            return datetime.strptime(candidate, pattern).replace(tzinfo=ROME)
        except ValueError:
            pass
    raise MimitParseError(f"price row {row_number} has invalid dtComu: {candidate!r}")


def parse_cng_prices(payload: bytes) -> ParsedDataset[MimitPriceRecord]:
    rows, dataset_date, first_data_row = _table(
        payload, {"idimpianto", "desccarburante", "prezzo", "isself", "dtcomu"}
    )
    records: list[MimitPriceRecord] = []
    for offset, row in enumerate(rows):
        row_number = first_data_row + offset
        fuel_name = (row.get("desccarburante") or "").strip()
        if not _is_cng(fuel_name):
            continue
        station_id = (row.get("idimpianto") or "").strip()
        if not station_id:
            raise MimitParseError(f"price row {row_number} has no idImpianto")
        try:
            unit_price = Decimal((row.get("prezzo") or "").strip().replace(",", "."))
        except InvalidOperation as error:
            raise MimitParseError(f"price row {row_number} has invalid price") from error
        if unit_price <= 0:
            raise MimitParseError(f"price row {row_number} has non-positive price")
        self_value = (row.get("isself") or "").strip()
        if self_value not in {"0", "1"}:
            raise MimitParseError(f"price row {row_number} has invalid isSelf")
        records.append(
            MimitPriceRecord(
                row_number=row_number,
                dataset_date=dataset_date,
                station_id=station_id,
                source_fuel_name=fuel_name,
                unit_price=unit_price,
                is_self_service=self_value == "1",
                observed_at=_parse_observed_at(row.get("dtcomu") or "", row_number),
                raw_record=row,
            )
        )
    return ParsedDataset(records=records, dataset_date=dataset_date, rows_seen=len(rows))


def dataset_observed_at(dataset_date: date | None) -> datetime | None:
    if dataset_date is None:
        return None
    return datetime.combine(dataset_date, time(hour=8), tzinfo=ROME)
