import hashlib
import json
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

from compass.etl.types import OsmCngFeature, ParsedDataset


class OsmParseError(ValueError):
    pass


OSM_CONTENT_IDENTITY_VERSION = "osm-cng-v1"


def build_cng_query(area_iso3166_1: str) -> str:
    safe_code = area_iso3166_1.strip().upper()
    if len(safe_code) != 2 or not safe_code.isalpha():
        raise ValueError("Overpass ISO 3166-1 area code must contain exactly two letters")
    return f'''[out:json][timeout:180];
area["ISO3166-1"="{safe_code}"][admin_level=2]->.searchArea;
nwr["amenity"="fuel"]["fuel:cng"="yes"](area.searchArea);
out center tags;'''


def _decimal(value: Any) -> Decimal | None:
    if value is None:
        return None
    try:
        return Decimal(str(value))
    except InvalidOperation:
        return None


def parse_cng_features(payload: bytes) -> tuple[ParsedDataset[OsmCngFeature], datetime | None]:
    try:
        document = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OsmParseError("Overpass response is not valid JSON") from error
    if not isinstance(document, dict) or not isinstance(document.get("elements"), list):
        raise OsmParseError("Overpass response has no elements array")

    timestamp = document.get("osm3s", {}).get("timestamp_osm_base")
    observed_at: datetime | None = None
    if timestamp:
        try:
            observed_at = datetime.fromisoformat(str(timestamp).replace("Z", "+00:00")).astimezone(
                UTC
            )
        except ValueError as error:
            raise OsmParseError("Overpass response has an invalid OSM timestamp") from error

    records: list[OsmCngFeature] = []
    seen: set[tuple[str, int]] = set()
    for element in document["elements"]:
        if not isinstance(element, dict):
            continue
        osm_type = str(element.get("type", ""))
        osm_id = element.get("id")
        tags = element.get("tags") or {}
        if osm_type not in {"node", "way", "relation"} or not isinstance(osm_id, int):
            continue
        if tags.get("amenity") != "fuel" or tags.get("fuel:cng") != "yes":
            continue
        identity = (osm_type, osm_id)
        if identity in seen:
            continue
        seen.add(identity)
        center = element.get("center") or {}
        records.append(
            OsmCngFeature(
                osm_type=osm_type,
                osm_id=osm_id,
                latitude=_decimal(element.get("lat", center.get("lat"))),
                longitude=_decimal(element.get("lon", center.get("lon"))),
                tags=dict(tags),
                raw_element=element,
            )
        )
    return (
        ParsedDataset(records=records, dataset_date=None, rows_seen=len(document["elements"])),
        observed_at,
    )


def cng_feature_collection_sha256(features: list[OsmCngFeature]) -> str:
    """Hash logical CNG feature content, excluding volatile Overpass metadata."""
    canonical_features = [
        {
            "osm_type": feature.osm_type,
            "osm_id": feature.osm_id,
            "latitude": str(feature.latitude) if feature.latitude is not None else None,
            "longitude": str(feature.longitude) if feature.longitude is not None else None,
            "tags": feature.tags,
        }
        for feature in sorted(features, key=lambda item: (item.osm_type, item.osm_id))
    ]
    canonical_payload = json.dumps(
        canonical_features,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    digest = hashlib.sha256()
    digest.update(OSM_CONTENT_IDENTITY_VERSION.encode("ascii"))
    digest.update(b"\0")
    digest.update(canonical_payload)
    return digest.hexdigest()
