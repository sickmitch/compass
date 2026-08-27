from datetime import date, datetime
from decimal import Decimal
from typing import Any

from geoalchemy2 import Geography
from geoalchemy2.elements import WKBElement
from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

ID_TYPE = BigInteger().with_variant(Integer, "sqlite")


class Base(DeclarativeBase):
    pass


class RawSourceSnapshot(Base):
    __tablename__ = "raw_source_snapshots"
    __table_args__ = (UniqueConstraint("source_name", "sha256", name="uq_snapshot_source_hash"),)

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    source_name: Mapped[str] = mapped_column(String(64), nullable=False)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    source_url: Mapped[str] = mapped_column(Text, nullable=False)
    content_type: Mapped[str] = mapped_column(String(128), nullable=False)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    source_observed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    content: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)


class IngestionRun(Base):
    __tablename__ = "ingestion_runs"
    __table_args__ = (
        UniqueConstraint("source_name", "snapshot_sha256", name="uq_run_source_hash"),
        Index("ix_ingestion_runs_started_at", "started_at"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    source_name: Mapped[str] = mapped_column(String(64), nullable=False)
    snapshot_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    source_observed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    metrics: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    error_message: Mapped[str | None] = mapped_column(Text)


class RawMimitStation(Base):
    __tablename__ = "raw_mimit_stations"
    __table_args__ = (
        UniqueConstraint("ingestion_run_id", "source_row_number", name="uq_mimit_station_run_row"),
        Index("ix_raw_mimit_stations_station_id", "mimit_station_id"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    ingestion_run_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("ingestion_runs.id", ondelete="CASCADE"), nullable=False
    )
    snapshot_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("raw_source_snapshots.id", ondelete="RESTRICT"), nullable=False
    )
    source_row_number: Mapped[int] = mapped_column(Integer, nullable=False)
    dataset_date: Mapped[date | None] = mapped_column(Date)
    mimit_station_id: Mapped[str] = mapped_column(String(32), nullable=False)
    manager: Mapped[str | None] = mapped_column(Text)
    brand: Mapped[str | None] = mapped_column(Text)
    station_type: Mapped[str | None] = mapped_column(Text)
    name: Mapped[str | None] = mapped_column(Text)
    address: Mapped[str | None] = mapped_column(Text)
    municipality: Mapped[str | None] = mapped_column(Text)
    province: Mapped[str | None] = mapped_column(Text)
    latitude: Mapped[Decimal | None] = mapped_column(Numeric(9, 6))
    longitude: Mapped[Decimal | None] = mapped_column(Numeric(9, 6))
    raw_record: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)


class RawMimitCngPrice(Base):
    __tablename__ = "raw_mimit_cng_prices"
    __table_args__ = (
        UniqueConstraint("ingestion_run_id", "source_row_number", name="uq_mimit_price_run_row"),
        Index("ix_raw_mimit_cng_prices_station_id", "mimit_station_id"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    ingestion_run_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("ingestion_runs.id", ondelete="CASCADE"), nullable=False
    )
    snapshot_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("raw_source_snapshots.id", ondelete="RESTRICT"), nullable=False
    )
    source_row_number: Mapped[int] = mapped_column(Integer, nullable=False)
    dataset_date: Mapped[date | None] = mapped_column(Date)
    mimit_station_id: Mapped[str] = mapped_column(String(32), nullable=False)
    source_fuel_name: Mapped[str] = mapped_column(Text, nullable=False)
    fuel_type: Mapped[str] = mapped_column(String(16), nullable=False, default="cng")
    unit_price: Mapped[Decimal] = mapped_column(Numeric(8, 3), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="EUR")
    unit: Mapped[str] = mapped_column(String(8), nullable=False, default="kg")
    is_self_service: Mapped[bool] = mapped_column(nullable=False)
    price_observed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    raw_record: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)


class RawOsmCngFeature(Base):
    __tablename__ = "raw_osm_cng_features"
    __table_args__ = (
        UniqueConstraint(
            "ingestion_run_id", "osm_type", "osm_id", name="uq_osm_feature_run_identity"
        ),
        Index("ix_raw_osm_cng_features_identity", "osm_type", "osm_id"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    ingestion_run_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("ingestion_runs.id", ondelete="CASCADE"), nullable=False
    )
    snapshot_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("raw_source_snapshots.id", ondelete="RESTRICT"), nullable=False
    )
    osm_type: Mapped[str] = mapped_column(String(16), nullable=False)
    osm_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    latitude: Mapped[Decimal | None] = mapped_column(Numeric(9, 6))
    longitude: Mapped[Decimal | None] = mapped_column(Numeric(9, 6))
    name: Mapped[str | None] = mapped_column(Text)
    opening_hours: Mapped[str | None] = mapped_column(Text)
    phone: Mapped[str | None] = mapped_column(Text)
    brand: Mapped[str | None] = mapped_column(Text)
    operator: Mapped[str | None] = mapped_column(Text)
    tags: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    raw_element: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)


class Station(Base):
    __tablename__ = "stations"
    __table_args__ = (
        Index("ix_stations_location_gist", "location", postgresql_using="gist"),
        Index("ix_stations_normalized_name", "normalized_name"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    mimit_station_id: Mapped[str] = mapped_column(String(32), unique=True, nullable=False)
    current_raw_mimit_station_id: Mapped[int] = mapped_column(
        ID_TYPE,
        ForeignKey("raw_mimit_stations.id", ondelete="RESTRICT"),
        unique=True,
        nullable=False,
    )
    name: Mapped[str | None] = mapped_column(Text)
    normalized_name: Mapped[str | None] = mapped_column(Text)
    address: Mapped[str | None] = mapped_column(Text)
    normalized_address: Mapped[str | None] = mapped_column(Text)
    municipality: Mapped[str | None] = mapped_column(Text)
    province: Mapped[str | None] = mapped_column(Text)
    brand: Mapped[str | None] = mapped_column(Text)
    manager: Mapped[str | None] = mapped_column(Text)
    station_type: Mapped[str | None] = mapped_column(Text)
    location: Mapped[WKBElement | None] = mapped_column(
        Geography(geometry_type="POINT", srid=4326, spatial_index=False), nullable=True
    )
    location_source: Mapped[str | None] = mapped_column(String(32))
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    source_observed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    field_provenance: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class OsmFeature(Base):
    __tablename__ = "osm_cng_features"
    __table_args__ = (
        UniqueConstraint("osm_type", "osm_id", name="uq_osm_cng_feature_identity"),
        Index("ix_osm_cng_features_location_gist", "location", postgresql_using="gist"),
        Index("ix_osm_cng_features_normalized_name", "normalized_name"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    osm_type: Mapped[str] = mapped_column(String(16), nullable=False)
    osm_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    current_raw_osm_feature_id: Mapped[int] = mapped_column(
        ID_TYPE,
        ForeignKey("raw_osm_cng_features.id", ondelete="RESTRICT"),
        unique=True,
        nullable=False,
    )
    name: Mapped[str | None] = mapped_column(Text)
    normalized_name: Mapped[str | None] = mapped_column(Text)
    opening_hours: Mapped[str | None] = mapped_column(Text)
    phone: Mapped[str | None] = mapped_column(Text)
    brand: Mapped[str | None] = mapped_column(Text)
    operator: Mapped[str | None] = mapped_column(Text)
    location: Mapped[WKBElement | None] = mapped_column(
        Geography(geometry_type="POINT", srid=4326, spatial_index=False), nullable=True
    )
    tags: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    source_observed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class StationPrice(Base):
    __tablename__ = "station_prices"
    __table_args__ = (
        UniqueConstraint(
            "station_id",
            "fuel_type",
            "service_mode",
            "observed_at",
            "unit_price",
            "currency",
            "unit",
            name="uq_station_price_semantic",
        ),
        CheckConstraint("unit_price > 0", name="ck_station_price_positive"),
        CheckConstraint("service_mode IN ('self', 'served')", name="ck_station_price_service_mode"),
        Index("ix_station_prices_station_observed", "station_id", "observed_at"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    station_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("stations.id", ondelete="CASCADE"), nullable=False
    )
    current_raw_mimit_price_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("raw_mimit_cng_prices.id", ondelete="RESTRICT"), nullable=False
    )
    fuel_type: Mapped[str] = mapped_column(String(16), nullable=False)
    unit_price: Mapped[Decimal] = mapped_column(Numeric(8, 3), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    unit: Mapped[str] = mapped_column(String(8), nullable=False)
    service_mode: Mapped[str] = mapped_column(String(16), nullable=False)
    observed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ingested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    source_name: Mapped[str] = mapped_column(String(32), nullable=False, default="mimit")


class StationCurrentPrice(Base):
    __tablename__ = "station_current_prices"
    __table_args__ = (UniqueConstraint("station_price_id", name="uq_current_station_price_row"),)

    station_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("stations.id", ondelete="CASCADE"), primary_key=True
    )
    fuel_type: Mapped[str] = mapped_column(String(16), primary_key=True)
    service_mode: Mapped[str] = mapped_column(String(16), primary_key=True)
    station_price_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("station_prices.id", ondelete="CASCADE"), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class ReconciliationRun(Base):
    __tablename__ = "reconciliation_runs"
    __table_args__ = (
        UniqueConstraint(
            "mimit_ingestion_run_id",
            "osm_ingestion_run_id",
            "algorithm_version",
            "configuration_sha256",
            name="uq_reconciliation_run_inputs",
        ),
        Index("ix_reconciliation_runs_completed_at", "completed_at"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    mimit_ingestion_run_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("ingestion_runs.id", ondelete="RESTRICT"), nullable=False
    )
    osm_ingestion_run_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("ingestion_runs.id", ondelete="RESTRICT"), nullable=False
    )
    algorithm_version: Mapped[str] = mapped_column(String(64), nullable=False)
    configuration_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    configuration: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    metrics: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    error_message: Mapped[str | None] = mapped_column(Text)


class ReconciliationResult(Base):
    __tablename__ = "reconciliation_results"
    __table_args__ = (
        UniqueConstraint("reconciliation_run_id", "station_id", name="uq_result_run_station"),
        CheckConstraint(
            "status IN ('matched', 'ambiguous', 'unmatched')",
            name="ck_reconciliation_result_status",
        ),
        CheckConstraint(
            "confidence IS NULL OR (confidence >= 0 AND confidence <= 1)",
            name="ck_reconciliation_result_confidence",
        ),
        Index("ix_reconciliation_results_status", "reconciliation_run_id", "status"),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    reconciliation_run_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("reconciliation_runs.id", ondelete="CASCADE"), nullable=False
    )
    station_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("stations.id", ondelete="CASCADE"), nullable=False
    )
    selected_osm_feature_id: Mapped[int | None] = mapped_column(
        ID_TYPE, ForeignKey("osm_cng_features.id", ondelete="RESTRICT")
    )
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    match_method: Mapped[str] = mapped_column(String(64), nullable=False)
    confidence: Mapped[Decimal | None] = mapped_column(Numeric(5, 4))
    distance_meters: Mapped[Decimal | None] = mapped_column(Numeric(10, 2))
    name_similarity: Mapped[Decimal | None] = mapped_column(Numeric(5, 4))
    candidate_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    decision_reason: Mapped[str] = mapped_column(Text, nullable=False)


class ReconciliationCandidate(Base):
    __tablename__ = "reconciliation_candidates"
    __table_args__ = (
        UniqueConstraint(
            "reconciliation_result_id",
            "osm_feature_id",
            name="uq_reconciliation_candidate_feature",
        ),
        CheckConstraint("score >= 0 AND score <= 1", name="ck_candidate_score"),
        CheckConstraint(
            "name_similarity >= 0 AND name_similarity <= 1",
            name="ck_candidate_name_similarity",
        ),
    )

    id: Mapped[int] = mapped_column(ID_TYPE, primary_key=True, autoincrement=True)
    reconciliation_result_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("reconciliation_results.id", ondelete="CASCADE"), nullable=False
    )
    osm_feature_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("osm_cng_features.id", ondelete="RESTRICT"), nullable=False
    )
    rank: Mapped[int] = mapped_column(Integer, nullable=False)
    distance_meters: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)
    name_similarity: Mapped[Decimal] = mapped_column(Numeric(5, 4), nullable=False)
    score: Mapped[Decimal] = mapped_column(Numeric(5, 4), nullable=False)
    eligible: Mapped[bool] = mapped_column(Boolean, nullable=False)


class StationOsmLink(Base):
    __tablename__ = "station_osm_links"
    __table_args__ = (
        UniqueConstraint("osm_feature_id", name="uq_station_osm_link_feature"),
        CheckConstraint(
            "confidence >= 0 AND confidence <= 1", name="ck_station_osm_link_confidence"
        ),
    )

    station_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("stations.id", ondelete="CASCADE"), primary_key=True
    )
    osm_feature_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("osm_cng_features.id", ondelete="RESTRICT"), nullable=False
    )
    reconciliation_result_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("reconciliation_results.id", ondelete="CASCADE"), nullable=False
    )
    match_method: Mapped[str] = mapped_column(String(64), nullable=False)
    confidence: Mapped[Decimal] = mapped_column(Numeric(5, 4), nullable=False)
    distance_meters: Mapped[Decimal | None] = mapped_column(Numeric(10, 2))
    is_manual: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class StationMatchOverride(Base):
    __tablename__ = "station_match_overrides"
    __table_args__ = (
        CheckConstraint("action IN ('link', 'unmatch')", name="ck_station_match_override_action"),
        CheckConstraint(
            "(action = 'link' AND osm_feature_id IS NOT NULL) OR "
            "(action = 'unmatch' AND osm_feature_id IS NULL)",
            name="ck_station_match_override_target",
        ),
    )

    station_id: Mapped[int] = mapped_column(
        ID_TYPE, ForeignKey("stations.id", ondelete="CASCADE"), primary_key=True
    )
    action: Mapped[str] = mapped_column(String(16), nullable=False)
    osm_feature_id: Mapped[int | None] = mapped_column(
        ID_TYPE, ForeignKey("osm_cng_features.id", ondelete="RESTRICT")
    )
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    created_by: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
