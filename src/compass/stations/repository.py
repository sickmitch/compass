from decimal import Decimal

from sqlalchemy import bindparam, text
from sqlalchemy.orm import Session

from compass.stations.domain import (
    StationCurrentPriceRecord,
    StationDetail,
    StationOsmEnrichment,
    StationRoutePoint,
)


def load_station_detail(session: Session, mimit_station_id: str) -> StationDetail | None:
    """Load one station and all its current CNG service-mode prices in one query."""
    rows = session.execute(
        text(
            "SELECT s.id AS station_id, s.mimit_station_id, s.name, s.address, "
            "s.municipality, s.province, s.brand, s.manager, s.station_type, "
            "ST_Y(s.location::geometry) AS latitude, "
            "ST_X(s.location::geometry) AS longitude, "
            "s.location_source, s.is_active, s.source_observed_at, s.updated_at, "
            "p.id AS price_id, p.unit_price, p.currency, p.unit, p.service_mode, "
            "p.observed_at, p.ingested_at, p.source_name, "
            "o.id AS osm_feature_id, o.osm_type, o.osm_id, o.name AS osm_name, "
            "o.opening_hours, o.phone, o.brand AS osm_brand, o.operator, "
            "o.source_observed_at AS osm_source_observed_at, "
            "l.match_method, l.confidence, l.distance_meters, l.is_manual "
            "FROM stations AS s "
            "LEFT JOIN station_current_prices AS cp "
            "  ON cp.station_id = s.id AND cp.fuel_type = 'cng' "
            "LEFT JOIN station_prices AS p ON p.id = cp.station_price_id "
            "LEFT JOIN station_osm_links AS l ON l.station_id = s.id "
            "LEFT JOIN osm_cng_features AS o "
            "  ON o.id = l.osm_feature_id AND o.is_active "
            "WHERE s.mimit_station_id = :mimit_station_id "
            "ORDER BY p.service_mode, p.id"
        ),
        {"mimit_station_id": mimit_station_id},
    ).all()
    if not rows:
        return None

    first = rows[0]
    prices = tuple(
        StationCurrentPriceRecord(
            unit_price=Decimal(row.unit_price),
            currency=row.currency,
            unit=row.unit,
            service_mode=row.service_mode,
            observed_at=row.observed_at,
            ingested_at=row.ingested_at,
            source_name=row.source_name,
        )
        for row in rows
        if row.price_id is not None
    )
    osm = (
        StationOsmEnrichment(
            osm_type=first.osm_type,
            osm_id=int(first.osm_id),
            name=first.osm_name,
            opening_hours=first.opening_hours,
            phone=first.phone,
            brand=first.osm_brand,
            operator=first.operator,
            source_observed_at=first.osm_source_observed_at,
            match_method=first.match_method,
            confidence=float(first.confidence),
            distance_meters=(
                float(first.distance_meters) if first.distance_meters is not None else None
            ),
            is_manual=bool(first.is_manual),
        )
        if first.osm_feature_id is not None
        else None
    )
    return StationDetail(
        station_id=int(first.station_id),
        mimit_station_id=first.mimit_station_id,
        name=first.name,
        address=first.address,
        municipality=first.municipality,
        province=first.province,
        brand=first.brand,
        manager=first.manager,
        station_type=first.station_type,
        latitude=float(first.latitude) if first.latitude is not None else None,
        longitude=float(first.longitude) if first.longitude is not None else None,
        location_source=first.location_source,
        is_active=bool(first.is_active),
        source_observed_at=first.source_observed_at,
        updated_at=first.updated_at,
        current_cng_prices=prices,
        osm=osm,
    )


def load_station_route_points(
    session: Session,
    mimit_station_ids: tuple[str, ...],
) -> dict[str, StationRoutePoint]:
    """Load the routing fields for an ordered station itinerary in one query."""
    if not mimit_station_ids:
        return {}
    statement = text(
        "SELECT s.id AS station_id, s.mimit_station_id, s.name, s.municipality, "
        "s.province, ST_Y(s.location::geometry) AS latitude, "
        "ST_X(s.location::geometry) AS longitude, s.is_active "
        "FROM stations AS s "
        "WHERE s.mimit_station_id IN :mimit_station_ids"
    ).bindparams(bindparam("mimit_station_ids", expanding=True))
    rows = session.execute(
        statement,
        {"mimit_station_ids": mimit_station_ids},
    ).all()
    return {
        row.mimit_station_id: StationRoutePoint(
            station_id=int(row.station_id),
            mimit_station_id=row.mimit_station_id,
            name=row.name,
            municipality=row.municipality,
            province=row.province,
            latitude=float(row.latitude) if row.latitude is not None else None,
            longitude=float(row.longitude) if row.longitude is not None else None,
            is_active=bool(row.is_active),
        )
        for row in rows
    }
