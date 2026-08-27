import hashlib
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from compass.etl.mimit import MimitParseError, dataset_observed_at, parse_cng_prices, parse_stations
from compass.etl.osm import (
    OSM_CONTENT_IDENTITY_VERSION,
    cng_feature_collection_sha256,
    parse_cng_features,
)
from compass.models import (
    IngestionRun,
    RawMimitCngPrice,
    RawMimitStation,
    RawOsmCngFeature,
    RawSourceSnapshot,
)


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _combined_sha256(*contents: bytes) -> str:
    digest = hashlib.sha256()
    for content in contents:
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def _completed_run(session: Session, source_name: str, digest: str) -> IngestionRun | None:
    return session.scalar(
        select(IngestionRun).where(
            IngestionRun.source_name == source_name,
            IngestionRun.snapshot_sha256 == digest,
            IngestionRun.status == "completed",
        )
    )


def _snapshot(
    session: Session,
    *,
    source_name: str,
    content: bytes,
    source_url: str,
    content_type: str,
    fetched_at: datetime,
    source_observed_at: datetime | None,
) -> RawSourceSnapshot:
    digest = _sha256(content)
    existing = session.scalar(
        select(RawSourceSnapshot).where(
            RawSourceSnapshot.source_name == source_name,
            RawSourceSnapshot.sha256 == digest,
        )
    )
    if existing:
        return existing
    snapshot = RawSourceSnapshot(
        source_name=source_name,
        sha256=digest,
        source_url=source_url,
        content_type=content_type,
        fetched_at=fetched_at,
        source_observed_at=source_observed_at,
        content=content,
    )
    session.add(snapshot)
    session.flush()
    return snapshot


def _result(run: IngestionRun, *, reused: bool) -> dict[str, Any]:
    return {
        "run_id": run.id,
        "source": run.source_name,
        "snapshot_sha256": run.snapshot_sha256,
        "status": run.status,
        "reused": reused,
        "source_observed_at": (
            run.source_observed_at.astimezone(UTC).isoformat() if run.source_observed_at else None
        ),
        "metrics": run.metrics,
    }


def ingest_mimit(
    session: Session,
    *,
    stations_content: bytes,
    prices_content: bytes,
    stations_url: str,
    prices_url: str,
    stations_content_type: str = "text/csv",
    prices_content_type: str = "text/csv",
    fetched_at: datetime | None = None,
) -> dict[str, Any]:
    digest = _combined_sha256(stations_content, prices_content)
    if existing := _completed_run(session, "mimit_cng", digest):
        return _result(existing, reused=True)

    fetched_at = fetched_at or datetime.now(UTC)
    stations = parse_stations(stations_content)
    prices = parse_cng_prices(prices_content)
    if (
        stations.dataset_date is not None
        and prices.dataset_date is not None
        and stations.dataset_date != prices.dataset_date
    ):
        raise MimitParseError(
            "MIMIT station and price extraction dates do not match: "
            f"{stations.dataset_date} != {prices.dataset_date}"
        )
    observed_at = dataset_observed_at(prices.dataset_date or stations.dataset_date)
    station_snapshot = _snapshot(
        session,
        source_name="mimit_active_stations",
        content=stations_content,
        source_url=stations_url,
        content_type=stations_content_type,
        fetched_at=fetched_at,
        source_observed_at=dataset_observed_at(stations.dataset_date),
    )
    price_snapshot = _snapshot(
        session,
        source_name="mimit_prices_at_8",
        content=prices_content,
        source_url=prices_url,
        content_type=prices_content_type,
        fetched_at=fetched_at,
        source_observed_at=dataset_observed_at(prices.dataset_date),
    )
    cng_station_ids = {record.station_id for record in prices.records}
    selected_stations = [
        record for record in stations.records if record.station_id in cng_station_ids
    ]
    imported_station_ids = {record.station_id for record in selected_stations}
    missing_station_ids = sorted(cng_station_ids - imported_station_ids)
    metrics: dict[str, Any] = {
        "station_rows_seen": stations.rows_seen,
        "price_rows_seen": prices.rows_seen,
        "cng_station_records": len(selected_stations),
        "cng_price_records": len(prices.records),
        "cng_price_station_ids": len(cng_station_ids),
        "cng_station_ids_missing_from_registry": len(missing_station_ids),
        "missing_station_id_sample": missing_station_ids[:10],
    }
    run = IngestionRun(
        source_name="mimit_cng",
        snapshot_sha256=digest,
        status="running",
        source_observed_at=observed_at,
        metrics={},
    )
    session.add(run)
    session.flush()

    session.add_all(
        RawMimitStation(
            ingestion_run_id=run.id,
            snapshot_id=station_snapshot.id,
            source_row_number=record.row_number,
            dataset_date=record.dataset_date,
            mimit_station_id=record.station_id,
            manager=record.manager,
            brand=record.brand,
            station_type=record.station_type,
            name=record.name,
            address=record.address,
            municipality=record.municipality,
            province=record.province,
            latitude=record.latitude,
            longitude=record.longitude,
            raw_record=record.raw_record,
        )
        for record in selected_stations
    )
    session.add_all(
        RawMimitCngPrice(
            ingestion_run_id=run.id,
            snapshot_id=price_snapshot.id,
            source_row_number=record.row_number,
            dataset_date=record.dataset_date,
            mimit_station_id=record.station_id,
            source_fuel_name=record.source_fuel_name,
            fuel_type="cng",
            unit_price=record.unit_price,
            currency="EUR",
            unit="kg",
            is_self_service=record.is_self_service,
            price_observed_at=record.observed_at,
            raw_record=record.raw_record,
        )
        for record in prices.records
    )
    run.status = "completed"
    run.completed_at = datetime.now(UTC)
    run.metrics = metrics
    session.commit()
    return _result(run, reused=False)


def ingest_osm(
    session: Session,
    *,
    content: bytes,
    source_url: str,
    content_type: str = "application/json",
    fetched_at: datetime | None = None,
) -> dict[str, Any]:
    features, observed_at = parse_cng_features(content)
    digest = cng_feature_collection_sha256(features.records)
    if existing := _completed_run(session, "osm_cng", digest):
        return _result(existing, reused=True)

    fetched_at = fetched_at or datetime.now(UTC)
    snapshot = _snapshot(
        session,
        source_name="osm_overpass_cng",
        content=content,
        source_url=source_url,
        content_type=content_type,
        fetched_at=fetched_at,
        source_observed_at=observed_at,
    )
    metrics: dict[str, Any] = {
        "elements_seen": features.rows_seen,
        "cng_features": len(features.records),
        "features_without_coordinates": sum(
            feature.latitude is None or feature.longitude is None for feature in features.records
        ),
        "content_identity_version": OSM_CONTENT_IDENTITY_VERSION,
    }
    run = IngestionRun(
        source_name="osm_cng",
        snapshot_sha256=digest,
        status="running",
        source_observed_at=observed_at,
        metrics={},
    )
    session.add(run)
    session.flush()
    session.add_all(
        RawOsmCngFeature(
            ingestion_run_id=run.id,
            snapshot_id=snapshot.id,
            osm_type=feature.osm_type,
            osm_id=feature.osm_id,
            latitude=feature.latitude,
            longitude=feature.longitude,
            name=feature.tags.get("name"),
            opening_hours=feature.tags.get("opening_hours"),
            phone=feature.tags.get("phone") or feature.tags.get("contact:phone"),
            brand=feature.tags.get("brand"),
            operator=feature.tags.get("operator"),
            tags=feature.tags,
            raw_element=feature.raw_element,
        )
        for feature in features.records
    )
    run.status = "completed"
    run.completed_at = datetime.now(UTC)
    run.metrics = metrics
    session.commit()
    return _result(run, reused=False)
