from datetime import date, datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
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
