import hashlib
import json
from dataclasses import asdict
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from geoalchemy2.elements import WKTElement
from sqlalchemy import delete, func, select, update
from sqlalchemy.orm import Session

from compass.config import Settings, get_settings
from compass.models import (
    IngestionRun,
    OsmFeature,
    RawMimitCngPrice,
    RawMimitStation,
    RawOsmCngFeature,
    RawSourceSnapshot,
    ReconciliationCandidate,
    ReconciliationResult,
    ReconciliationRun,
    Station,
    StationCurrentPrice,
    StationMatchOverride,
    StationOsmLink,
    StationPrice,
)
from compass.normalization.values import clean_text, normalize_text, valid_italy_coordinates
from compass.reconciliation.domain import (
    ALGORITHM_VERSION,
    CandidateInput,
    MatchDecision,
    ReconciliationPolicy,
    ScoredCandidate,
    decide_match,
    name_similarity,
    resolve_osm_link_conflicts,
)


class NormalizationError(RuntimeError):
    pass


def policy_from_settings(settings: Settings | None = None) -> ReconciliationPolicy:
    settings = settings or get_settings()
    return ReconciliationPolicy(
        max_distance_meters=settings.reconciliation_max_distance_meters,
        auto_match_distance_meters=settings.reconciliation_auto_match_distance_meters,
        named_match_distance_meters=settings.reconciliation_named_match_distance_meters,
        name_similarity_threshold=settings.reconciliation_name_similarity_threshold,
        ambiguity_score_margin=settings.reconciliation_ambiguity_score_margin,
    )


def _latest_completed_run(session: Session, source_name: str) -> IngestionRun:
    run = session.scalar(
        select(IngestionRun)
        .where(IngestionRun.source_name == source_name, IngestionRun.status == "completed")
        .order_by(IngestionRun.id.desc())
        .limit(1)
    )
    if run is None:
        raise NormalizationError(f"no completed {source_name} ingestion run is available")
    return run


def _point(latitude: Decimal | None, longitude: Decimal | None) -> WKTElement | None:
    if not valid_italy_coordinates(latitude, longitude):
        return None
    return WKTElement(f"POINT({longitude} {latitude})", srid=4326)


def _normalize_stations(
    session: Session, mimit_run: IngestionRun, normalized_at: datetime
) -> tuple[dict[str, Station], dict[str, int]]:
    raw_stations = list(
        session.scalars(
            select(RawMimitStation)
            .where(RawMimitStation.ingestion_run_id == mimit_run.id)
            .order_by(RawMimitStation.id)
        )
    )
    existing = {
        station.mimit_station_id: station for station in session.scalars(select(Station)).all()
    }
    session.execute(update(Station).values(is_active=False, updated_at=normalized_at))
    created = 0
    invalid_coordinates = 0
    current: dict[str, Station] = {}
    for raw in raw_stations:
        station = existing.get(raw.mimit_station_id)
        if station is None:
            station = Station(
                mimit_station_id=raw.mimit_station_id,
                current_raw_mimit_station_id=raw.id,
                field_provenance={},
                created_at=normalized_at,
                updated_at=normalized_at,
            )
            session.add(station)
            existing[raw.mimit_station_id] = station
            created += 1
        location = _point(raw.latitude, raw.longitude)
        if location is None:
            invalid_coordinates += 1
        provenance = {
            field: {"source": "mimit", "raw_record_id": raw.id}
            for field in (
                "name",
                "address",
                "municipality",
                "province",
                "brand",
                "manager",
                "station_type",
                "location",
            )
        }
        station.current_raw_mimit_station_id = raw.id
        station.name = clean_text(raw.name)
        station.normalized_name = normalize_text(raw.name)
        station.address = clean_text(raw.address)
        station.normalized_address = normalize_text(raw.address)
        station.municipality = clean_text(raw.municipality)
        station.province = clean_text(raw.province)
        station.brand = clean_text(raw.brand)
        station.manager = clean_text(raw.manager)
        station.station_type = clean_text(raw.station_type)
        station.location = location
        station.location_source = "mimit" if location is not None else None
        station.is_active = True
        station.source_observed_at = mimit_run.source_observed_at
        station.field_provenance = provenance
        station.updated_at = normalized_at
        current[station.mimit_station_id] = station
    session.flush()
    return current, {
        "stations_seen": len(raw_stations),
        "stations_created": created,
        "stations_updated": len(raw_stations) - created,
        "stations_without_valid_coordinates": invalid_coordinates,
    }


def _normalize_prices(
    session: Session,
    mimit_run: IngestionRun,
    stations: dict[str, Station],
    normalized_at: datetime,
) -> dict[str, int]:
    raw_prices = list(
        session.scalars(
            select(RawMimitCngPrice)
            .where(RawMimitCngPrice.ingestion_run_id == mimit_run.id)
            .order_by(RawMimitCngPrice.id)
        )
    )
    snapshot_ids = {raw.snapshot_id for raw in raw_prices}
    snapshot_times = {
        snapshot.id: snapshot.fetched_at
        for snapshot in session.scalars(
            select(RawSourceSnapshot).where(RawSourceSnapshot.id.in_(snapshot_ids))
        )
    }
    station_ids = [station.id for station in stations.values()]
    existing_prices = list(
        session.scalars(select(StationPrice).where(StationPrice.station_id.in_(station_ids)))
    )
    price_by_key = {
        (
            price.station_id,
            price.fuel_type,
            price.service_mode,
            price.observed_at,
            price.unit_price,
            price.currency,
            price.unit,
        ): price
        for price in existing_prices
    }
    created = 0
    skipped_missing_station = 0
    for raw in raw_prices:
        station = stations.get(raw.mimit_station_id)
        if station is None:
            skipped_missing_station += 1
            continue
        service_mode = "self" if raw.is_self_service else "served"
        key = (
            station.id,
            raw.fuel_type,
            service_mode,
            raw.price_observed_at,
            raw.unit_price,
            raw.currency,
            raw.unit,
        )
        price = price_by_key.get(key)
        ingested_at = snapshot_times.get(raw.snapshot_id, normalized_at)
        if price is None:
            price = StationPrice(
                station_id=station.id,
                current_raw_mimit_price_id=raw.id,
                fuel_type=raw.fuel_type,
                unit_price=raw.unit_price,
                currency=raw.currency,
                unit=raw.unit,
                service_mode=service_mode,
                observed_at=raw.price_observed_at,
                ingested_at=ingested_at,
                source_name="mimit",
            )
            session.add(price)
            price_by_key[key] = price
            created += 1
        else:
            price.current_raw_mimit_price_id = raw.id
            price.ingested_at = max(price.ingested_at, ingested_at)
    session.flush()

    session.execute(delete(StationCurrentPrice))
    latest: dict[tuple[int, str, str], StationPrice] = {}
    all_active_prices = session.scalars(
        select(StationPrice).where(StationPrice.station_id.in_(station_ids))
    )
    for price in all_active_prices:
        key = (price.station_id, price.fuel_type, price.service_mode)
        current = latest.get(key)
        if current is None or (price.observed_at, price.ingested_at, price.id) > (
            current.observed_at,
            current.ingested_at,
            current.id,
        ):
            latest[key] = price
    session.add_all(
        StationCurrentPrice(
            station_id=station_id,
            fuel_type=fuel_type,
            service_mode=service_mode,
            station_price_id=price.id,
            updated_at=normalized_at,
        )
        for (station_id, fuel_type, service_mode), price in latest.items()
    )
    session.flush()
    return {
        "price_rows_seen": len(raw_prices),
        "price_history_rows_created": created,
        "price_rows_reused": len(raw_prices) - created - skipped_missing_station,
        "price_rows_missing_station": skipped_missing_station,
        "current_price_rows": len(latest),
    }


def _normalize_osm_features(
    session: Session, osm_run: IngestionRun, normalized_at: datetime
) -> tuple[dict[tuple[str, int], OsmFeature], dict[str, int]]:
    raw_features = list(
        session.scalars(
            select(RawOsmCngFeature)
            .where(RawOsmCngFeature.ingestion_run_id == osm_run.id)
            .order_by(RawOsmCngFeature.id)
        )
    )
    existing = {
        (feature.osm_type, feature.osm_id): feature
        for feature in session.scalars(select(OsmFeature)).all()
    }
    session.execute(update(OsmFeature).values(is_active=False, updated_at=normalized_at))
    created = 0
    invalid_coordinates = 0
    current: dict[tuple[str, int], OsmFeature] = {}
    for raw in raw_features:
        identity = (raw.osm_type, raw.osm_id)
        feature = existing.get(identity)
        if feature is None:
            feature = OsmFeature(
                osm_type=raw.osm_type,
                osm_id=raw.osm_id,
                current_raw_osm_feature_id=raw.id,
                tags={},
                created_at=normalized_at,
                updated_at=normalized_at,
            )
            session.add(feature)
            existing[identity] = feature
            created += 1
        location = _point(raw.latitude, raw.longitude)
        if location is None:
            invalid_coordinates += 1
        preferred_name = raw.name or raw.brand or raw.operator
        feature.current_raw_osm_feature_id = raw.id
        feature.name = clean_text(raw.name)
        feature.normalized_name = normalize_text(preferred_name)
        feature.opening_hours = clean_text(raw.opening_hours)
        feature.phone = clean_text(raw.phone)
        feature.brand = clean_text(raw.brand)
        feature.operator = clean_text(raw.operator)
        feature.location = location
        feature.tags = raw.tags
        feature.is_active = True
        feature.source_observed_at = osm_run.source_observed_at
        feature.updated_at = normalized_at
        current[identity] = feature
    session.flush()
    return current, {
        "osm_features_seen": len(raw_features),
        "osm_features_created": created,
        "osm_features_updated": len(raw_features) - created,
        "osm_features_without_valid_coordinates": invalid_coordinates,
    }


def _override_configuration(
    session: Session, policy: ReconciliationPolicy
) -> tuple[dict[str, Any], str, dict[int, StationMatchOverride]]:
    overrides = list(
        session.scalars(select(StationMatchOverride).order_by(StationMatchOverride.station_id))
    )
    osm_by_id = {
        feature.id: feature
        for feature in session.scalars(
            select(OsmFeature).where(
                OsmFeature.id.in_(
                    [override.osm_feature_id for override in overrides if override.osm_feature_id]
                )
            )
        )
    }
    serialized_overrides = [
        {
            "station_id": override.station_id,
            "action": override.action,
            "osm_type": (
                osm_by_id[override.osm_feature_id].osm_type if override.osm_feature_id else None
            ),
            "osm_id": osm_by_id[override.osm_feature_id].osm_id
            if override.osm_feature_id
            else None,
            "reason": override.reason,
            "created_by": override.created_by,
        }
        for override in overrides
    ]
    configuration = {"policy": asdict(policy), "overrides": serialized_overrides}
    digest = hashlib.sha256(
        json.dumps(configuration, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    return configuration, digest, {override.station_id: override for override in overrides}


def _candidate_inputs(
    session: Session,
    stations: list[Station],
    osm_features: dict[tuple[str, int], OsmFeature],
    policy: ReconciliationPolicy,
) -> tuple[dict[int, list[CandidateInput]], int]:
    candidate_map: dict[int, list[CandidateInput]] = {station.id: [] for station in stations}
    display_names = {
        feature.id: feature.name or feature.brand or feature.operator
        for feature in osm_features.values()
    }
    distance = func.ST_Distance(Station.location, OsmFeature.location)
    rows = session.execute(
        select(Station.id, OsmFeature.id, distance.label("distance_meters"))
        .select_from(Station)
        .join(
            OsmFeature,
            func.ST_DWithin(Station.location, OsmFeature.location, policy.max_distance_meters),
        )
        .where(
            Station.is_active.is_(True),
            OsmFeature.is_active.is_(True),
            Station.location.is_not(None),
            OsmFeature.location.is_not(None),
        )
    )
    count = 0
    for station_id, osm_feature_id, distance_meters in rows:
        candidate_map[station_id].append(
            CandidateInput(
                osm_feature_id=osm_feature_id,
                distance_meters=float(distance_meters),
                name=display_names[osm_feature_id],
            )
        )
        count += 1
    return candidate_map, count


def _manual_decision(
    session: Session,
    station: Station,
    override: StationMatchOverride,
    auto_decision: MatchDecision,
    osm_features_by_id: dict[int, OsmFeature],
) -> MatchDecision:
    if override.action == "unmatch":
        return MatchDecision(
            status="unmatched",
            selected_osm_feature_id=None,
            match_method="manual_override",
            confidence=1.0,
            distance_meters=None,
            name_similarity=None,
            reason=f"manual_unmatch: {override.reason}",
            candidates=auto_decision.candidates,
        )
    target = osm_features_by_id.get(override.osm_feature_id or -1)
    if target is None or not target.is_active:
        return MatchDecision(
            status="unmatched",
            selected_osm_feature_id=None,
            match_method="manual_override",
            confidence=None,
            distance_meters=None,
            name_similarity=None,
            reason="manual_link_target_not_present_in_current_osm_snapshot",
            candidates=auto_decision.candidates,
        )
    distance_value = session.scalar(
        select(func.ST_Distance(Station.location, OsmFeature.location))
        .select_from(Station)
        .join(OsmFeature, OsmFeature.id == target.id)
        .where(
            Station.id == station.id,
            Station.location.is_not(None),
            OsmFeature.location.is_not(None),
        )
    )
    distance_meters = float(distance_value) if distance_value is not None else None
    similarity = name_similarity(station.name, target.name or target.brand or target.operator)
    candidates = list(auto_decision.candidates)
    if distance_meters is not None and not any(
        candidate.osm_feature_id == target.id for candidate in candidates
    ):
        candidates.append(
            ScoredCandidate(
                osm_feature_id=target.id,
                distance_meters=round(distance_meters, 2),
                name_similarity=similarity,
                score=1.0,
                eligible=True,
            )
        )
    candidates.sort(key=lambda item: (-item.score, item.distance_meters, item.osm_feature_id))
    return MatchDecision(
        status="matched",
        selected_osm_feature_id=target.id,
        match_method="manual_override",
        confidence=1.0,
        distance_meters=round(distance_meters, 2) if distance_meters is not None else None,
        name_similarity=similarity,
        reason=f"manual_link: {override.reason}",
        candidates=tuple(candidates),
    )


def _decimal(value: float | None, places: int = 4) -> Decimal | None:
    if value is None:
        return None
    return Decimal(f"{value:.{places}f}")


def normalize_and_reconcile(
    session: Session, policy: ReconciliationPolicy | None = None
) -> dict[str, Any]:
    policy = policy or policy_from_settings()
    normalized_at = datetime.now(UTC)
    mimit_run = _latest_completed_run(session, "mimit_cng")
    osm_run = _latest_completed_run(session, "osm_cng")
    stations_by_mimit_id, station_metrics = _normalize_stations(session, mimit_run, normalized_at)
    price_metrics = _normalize_prices(session, mimit_run, stations_by_mimit_id, normalized_at)
    osm_features, osm_metrics = _normalize_osm_features(session, osm_run, normalized_at)
    configuration, configuration_hash, overrides = _override_configuration(session, policy)
    existing_run = session.scalar(
        select(ReconciliationRun).where(
            ReconciliationRun.mimit_ingestion_run_id == mimit_run.id,
            ReconciliationRun.osm_ingestion_run_id == osm_run.id,
            ReconciliationRun.algorithm_version == ALGORITHM_VERSION,
            ReconciliationRun.configuration_sha256 == configuration_hash,
            ReconciliationRun.status == "completed",
        )
    )
    if existing_run is not None:
        session.commit()
        return _run_result(existing_run, reused=True)

    stations = sorted(stations_by_mimit_id.values(), key=lambda item: item.id)
    candidate_map, candidate_pair_count = _candidate_inputs(session, stations, osm_features, policy)
    osm_features_by_id = {feature.id: feature for feature in osm_features.values()}
    decisions: dict[int, MatchDecision] = {}
    for station in stations:
        auto_decision = decide_match(station.name, candidate_map[station.id], policy)
        override = overrides.get(station.id)
        decisions[station.id] = (
            _manual_decision(session, station, override, auto_decision, osm_features_by_id)
            if override is not None
            else auto_decision
        )
    decisions = resolve_osm_link_conflicts(decisions)

    run = ReconciliationRun(
        mimit_ingestion_run_id=mimit_run.id,
        osm_ingestion_run_id=osm_run.id,
        algorithm_version=ALGORITHM_VERSION,
        configuration_sha256=configuration_hash,
        status="running",
        configuration=configuration,
        metrics={},
    )
    session.add(run)
    session.flush()
    session.execute(delete(StationOsmLink))
    matched_osm_feature_ids: set[int] = set()
    for station in stations:
        decision = decisions[station.id]
        result = ReconciliationResult(
            reconciliation_run_id=run.id,
            station_id=station.id,
            selected_osm_feature_id=decision.selected_osm_feature_id,
            status=decision.status,
            match_method=decision.match_method,
            confidence=_decimal(decision.confidence),
            distance_meters=_decimal(decision.distance_meters, places=2),
            name_similarity=_decimal(decision.name_similarity),
            candidate_count=len(decision.candidates),
            decision_reason=decision.reason,
        )
        session.add(result)
        session.flush()
        session.add_all(
            ReconciliationCandidate(
                reconciliation_result_id=result.id,
                osm_feature_id=candidate.osm_feature_id,
                rank=rank,
                distance_meters=_decimal(candidate.distance_meters, places=2),
                name_similarity=_decimal(candidate.name_similarity),
                score=_decimal(candidate.score),
                eligible=candidate.eligible,
            )
            for rank, candidate in enumerate(decision.candidates, start=1)
        )
        if decision.status == "matched" and decision.selected_osm_feature_id is not None:
            matched_osm_feature_ids.add(decision.selected_osm_feature_id)
            session.add(
                StationOsmLink(
                    station_id=station.id,
                    osm_feature_id=decision.selected_osm_feature_id,
                    reconciliation_result_id=result.id,
                    match_method=decision.match_method,
                    confidence=_decimal(decision.confidence) or Decimal("0"),
                    distance_meters=_decimal(decision.distance_meters, places=2),
                    is_manual=decision.match_method == "manual_override",
                    updated_at=normalized_at,
                )
            )
    metrics: dict[str, Any] = {
        **station_metrics,
        **price_metrics,
        **osm_metrics,
        "candidate_pairs": candidate_pair_count,
        "matched": sum(decision.status == "matched" for decision in decisions.values()),
        "ambiguous": sum(decision.status == "ambiguous" for decision in decisions.values()),
        "unmatched": sum(decision.status == "unmatched" for decision in decisions.values()),
        "manual_links": sum(
            decision.status == "matched" and decision.match_method == "manual_override"
            for decision in decisions.values()
        ),
        "manual_unmatches": sum(
            decision.status == "unmatched" and decision.match_method == "manual_override"
            for decision in decisions.values()
        ),
        "osm_features_unmatched": len(osm_features) - len(matched_osm_feature_ids),
    }
    run.status = "completed"
    run.completed_at = datetime.now(UTC)
    run.metrics = metrics
    session.commit()
    return _run_result(run, reused=False)


def _run_result(run: ReconciliationRun, *, reused: bool) -> dict[str, Any]:
    return {
        "reconciliation_run_id": run.id,
        "status": run.status,
        "reused": reused,
        "algorithm_version": run.algorithm_version,
        "configuration_sha256": run.configuration_sha256,
        "mimit_ingestion_run_id": run.mimit_ingestion_run_id,
        "osm_ingestion_run_id": run.osm_ingestion_run_id,
        "metrics": run.metrics,
    }


def set_match_override(
    session: Session,
    *,
    mimit_station_id: str,
    action: str,
    reason: str,
    created_by: str,
    osm_type: str | None = None,
    osm_id: int | None = None,
) -> dict[str, Any]:
    station = session.scalar(select(Station).where(Station.mimit_station_id == mimit_station_id))
    if station is None:
        raise NormalizationError(f"normalized MIMIT station {mimit_station_id} does not exist")
    if action == "clear":
        deleted = session.execute(
            delete(StationMatchOverride).where(StationMatchOverride.station_id == station.id)
        ).rowcount
        session.commit()
        return {"mimit_station_id": mimit_station_id, "action": "clear", "deleted": bool(deleted)}
    if action not in {"link", "unmatch"}:
        raise NormalizationError("override action must be link, unmatch, or clear")
    target: OsmFeature | None = None
    if action == "link":
        if osm_type is None or osm_id is None:
            raise NormalizationError("link override requires --osm-type and --osm-id")
        target = session.scalar(
            select(OsmFeature).where(OsmFeature.osm_type == osm_type, OsmFeature.osm_id == osm_id)
        )
        if target is None:
            raise NormalizationError(f"normalized OSM feature {osm_type}/{osm_id} does not exist")
    existing = session.get(StationMatchOverride, station.id)
    now = datetime.now(UTC)
    if existing is None:
        existing = StationMatchOverride(
            station_id=station.id,
            action=action,
            reason=reason,
            created_by=created_by,
            created_at=now,
            updated_at=now,
        )
        session.add(existing)
    existing.action = action
    existing.osm_feature_id = target.id if target is not None else None
    existing.reason = reason
    existing.created_by = created_by
    existing.updated_at = now
    session.commit()
    return {
        "mimit_station_id": mimit_station_id,
        "action": action,
        "osm_type": target.osm_type if target is not None else None,
        "osm_id": target.osm_id if target is not None else None,
        "reason": reason,
        "created_by": created_by,
    }
