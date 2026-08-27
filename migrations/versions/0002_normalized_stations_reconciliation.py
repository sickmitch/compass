"""Create normalized PostGIS station and reconciliation models.

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-27
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: str | Sequence[str] | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "stations",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("mimit_station_id", sa.String(length=32), nullable=False),
        sa.Column("current_raw_mimit_station_id", sa.BigInteger(), nullable=False),
        sa.Column("name", sa.Text(), nullable=True),
        sa.Column("normalized_name", sa.Text(), nullable=True),
        sa.Column("address", sa.Text(), nullable=True),
        sa.Column("normalized_address", sa.Text(), nullable=True),
        sa.Column("municipality", sa.Text(), nullable=True),
        sa.Column("province", sa.Text(), nullable=True),
        sa.Column("brand", sa.Text(), nullable=True),
        sa.Column("manager", sa.Text(), nullable=True),
        sa.Column("station_type", sa.Text(), nullable=True),
        sa.Column(
            "location",
            geoalchemy2.types.Geography(geometry_type="POINT", srid=4326, spatial_index=False),
            nullable=True,
        ),
        sa.Column("location_source", sa.String(length=32), nullable=True),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("source_observed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("field_provenance", sa.JSON(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["current_raw_mimit_station_id"], ["raw_mimit_stations.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("current_raw_mimit_station_id"),
        sa.UniqueConstraint("mimit_station_id"),
    )
    op.create_index("ix_stations_location_gist", "stations", ["location"], postgresql_using="gist")
    op.create_index("ix_stations_normalized_name", "stations", ["normalized_name"])

    op.create_table(
        "osm_cng_features",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("osm_type", sa.String(length=16), nullable=False),
        sa.Column("osm_id", sa.BigInteger(), nullable=False),
        sa.Column("current_raw_osm_feature_id", sa.BigInteger(), nullable=False),
        sa.Column("name", sa.Text(), nullable=True),
        sa.Column("normalized_name", sa.Text(), nullable=True),
        sa.Column("opening_hours", sa.Text(), nullable=True),
        sa.Column("phone", sa.Text(), nullable=True),
        sa.Column("brand", sa.Text(), nullable=True),
        sa.Column("operator", sa.Text(), nullable=True),
        sa.Column(
            "location",
            geoalchemy2.types.Geography(geometry_type="POINT", srid=4326, spatial_index=False),
            nullable=True,
        ),
        sa.Column("tags", sa.JSON(), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("source_observed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["current_raw_osm_feature_id"], ["raw_osm_cng_features.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("current_raw_osm_feature_id"),
        sa.UniqueConstraint("osm_type", "osm_id", name="uq_osm_cng_feature_identity"),
    )
    op.create_index(
        "ix_osm_cng_features_location_gist",
        "osm_cng_features",
        ["location"],
        postgresql_using="gist",
    )
    op.create_index("ix_osm_cng_features_normalized_name", "osm_cng_features", ["normalized_name"])

    op.create_table(
        "station_prices",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("station_id", sa.BigInteger(), nullable=False),
        sa.Column("current_raw_mimit_price_id", sa.BigInteger(), nullable=False),
        sa.Column("fuel_type", sa.String(length=16), nullable=False),
        sa.Column("unit_price", sa.Numeric(precision=8, scale=3), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False),
        sa.Column("unit", sa.String(length=8), nullable=False),
        sa.Column("service_mode", sa.String(length=16), nullable=False),
        sa.Column("observed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ingested_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("source_name", sa.String(length=32), nullable=False),
        sa.CheckConstraint(
            "service_mode IN ('self', 'served')", name="ck_station_price_service_mode"
        ),
        sa.CheckConstraint("unit_price > 0", name="ck_station_price_positive"),
        sa.ForeignKeyConstraint(["station_id"], ["stations.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["current_raw_mimit_price_id"], ["raw_mimit_cng_prices.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "station_id",
            "fuel_type",
            "service_mode",
            "observed_at",
            "unit_price",
            "currency",
            "unit",
            name="uq_station_price_semantic",
        ),
    )
    op.create_index(
        "ix_station_prices_station_observed", "station_prices", ["station_id", "observed_at"]
    )

    op.create_table(
        "station_current_prices",
        sa.Column("station_id", sa.BigInteger(), nullable=False),
        sa.Column("fuel_type", sa.String(length=16), nullable=False),
        sa.Column("service_mode", sa.String(length=16), nullable=False),
        sa.Column("station_price_id", sa.BigInteger(), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["station_id"], ["stations.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["station_price_id"], ["station_prices.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("station_id", "fuel_type", "service_mode"),
        sa.UniqueConstraint("station_price_id", name="uq_current_station_price_row"),
    )

    op.create_table(
        "reconciliation_runs",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("mimit_ingestion_run_id", sa.BigInteger(), nullable=False),
        sa.Column("osm_ingestion_run_id", sa.BigInteger(), nullable=False),
        sa.Column("algorithm_version", sa.String(length=64), nullable=False),
        sa.Column("configuration_sha256", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column(
            "started_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("configuration", sa.JSON(), nullable=False),
        sa.Column("metrics", sa.JSON(), nullable=False),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.ForeignKeyConstraint(
            ["mimit_ingestion_run_id"], ["ingestion_runs.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["osm_ingestion_run_id"], ["ingestion_runs.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "mimit_ingestion_run_id",
            "osm_ingestion_run_id",
            "algorithm_version",
            "configuration_sha256",
            name="uq_reconciliation_run_inputs",
        ),
    )
    op.create_index("ix_reconciliation_runs_completed_at", "reconciliation_runs", ["completed_at"])

    op.create_table(
        "reconciliation_results",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("reconciliation_run_id", sa.BigInteger(), nullable=False),
        sa.Column("station_id", sa.BigInteger(), nullable=False),
        sa.Column("selected_osm_feature_id", sa.BigInteger(), nullable=True),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("match_method", sa.String(length=64), nullable=False),
        sa.Column("confidence", sa.Numeric(precision=5, scale=4), nullable=True),
        sa.Column("distance_meters", sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column("name_similarity", sa.Numeric(precision=5, scale=4), nullable=True),
        sa.Column("candidate_count", sa.Integer(), nullable=False),
        sa.Column("decision_reason", sa.Text(), nullable=False),
        sa.CheckConstraint(
            "confidence IS NULL OR (confidence >= 0 AND confidence <= 1)",
            name="ck_reconciliation_result_confidence",
        ),
        sa.CheckConstraint(
            "status IN ('matched', 'ambiguous', 'unmatched')",
            name="ck_reconciliation_result_status",
        ),
        sa.ForeignKeyConstraint(
            ["reconciliation_run_id"], ["reconciliation_runs.id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["selected_osm_feature_id"], ["osm_cng_features.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(["station_id"], ["stations.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("reconciliation_run_id", "station_id", name="uq_result_run_station"),
    )
    op.create_index(
        "ix_reconciliation_results_status",
        "reconciliation_results",
        ["reconciliation_run_id", "status"],
    )

    op.create_table(
        "reconciliation_candidates",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("reconciliation_result_id", sa.BigInteger(), nullable=False),
        sa.Column("osm_feature_id", sa.BigInteger(), nullable=False),
        sa.Column("rank", sa.Integer(), nullable=False),
        sa.Column("distance_meters", sa.Numeric(precision=10, scale=2), nullable=False),
        sa.Column("name_similarity", sa.Numeric(precision=5, scale=4), nullable=False),
        sa.Column("score", sa.Numeric(precision=5, scale=4), nullable=False),
        sa.Column("eligible", sa.Boolean(), nullable=False),
        sa.CheckConstraint(
            "name_similarity >= 0 AND name_similarity <= 1",
            name="ck_candidate_name_similarity",
        ),
        sa.CheckConstraint("score >= 0 AND score <= 1", name="ck_candidate_score"),
        sa.ForeignKeyConstraint(["osm_feature_id"], ["osm_cng_features.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["reconciliation_result_id"], ["reconciliation_results.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "reconciliation_result_id",
            "osm_feature_id",
            name="uq_reconciliation_candidate_feature",
        ),
    )

    op.create_table(
        "station_match_overrides",
        sa.Column("station_id", sa.BigInteger(), nullable=False),
        sa.Column("action", sa.String(length=16), nullable=False),
        sa.Column("osm_feature_id", sa.BigInteger(), nullable=True),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("created_by", sa.String(length=128), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "action IN ('link', 'unmatch')", name="ck_station_match_override_action"
        ),
        sa.CheckConstraint(
            "(action = 'link' AND osm_feature_id IS NOT NULL) OR "
            "(action = 'unmatch' AND osm_feature_id IS NULL)",
            name="ck_station_match_override_target",
        ),
        sa.ForeignKeyConstraint(["osm_feature_id"], ["osm_cng_features.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["station_id"], ["stations.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("station_id"),
    )

    op.create_table(
        "station_osm_links",
        sa.Column("station_id", sa.BigInteger(), nullable=False),
        sa.Column("osm_feature_id", sa.BigInteger(), nullable=False),
        sa.Column("reconciliation_result_id", sa.BigInteger(), nullable=False),
        sa.Column("match_method", sa.String(length=64), nullable=False),
        sa.Column("confidence", sa.Numeric(precision=5, scale=4), nullable=False),
        sa.Column("distance_meters", sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column("is_manual", sa.Boolean(), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "confidence >= 0 AND confidence <= 1", name="ck_station_osm_link_confidence"
        ),
        sa.ForeignKeyConstraint(["osm_feature_id"], ["osm_cng_features.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["reconciliation_result_id"], ["reconciliation_results.id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(["station_id"], ["stations.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("station_id"),
        sa.UniqueConstraint("osm_feature_id", name="uq_station_osm_link_feature"),
    )


def downgrade() -> None:
    op.drop_table("station_osm_links")
    op.drop_table("station_match_overrides")
    op.drop_table("reconciliation_candidates")
    op.drop_table("reconciliation_results")
    op.drop_table("reconciliation_runs")
    op.drop_table("station_current_prices")
    op.drop_table("station_prices")
    op.drop_index("ix_osm_cng_features_normalized_name", table_name="osm_cng_features")
    op.drop_index(
        "ix_osm_cng_features_location_gist", table_name="osm_cng_features", postgresql_using="gist"
    )
    op.drop_table("osm_cng_features")
    op.drop_index("ix_stations_normalized_name", table_name="stations")
    op.drop_index("ix_stations_location_gist", table_name="stations", postgresql_using="gist")
    op.drop_table("stations")
