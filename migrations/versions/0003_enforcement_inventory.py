"""Add source-preserving enforcement inventory; no inferred road associations."""
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE enforcement_imports (
            id bigserial PRIMARY KEY,
            source text NOT NULL,
            scope text NOT NULL,
            sha256 text NOT NULL,
            source_url text NOT NULL,
            fetched_at timestamptz NOT NULL,
            imported_at timestamptz NOT NULL DEFAULT now(),
            record_count integer NOT NULL,
            UNIQUE (source, scope, sha256)
        );
        CREATE TABLE enforcement_controls (
            source_key text PRIMARY KEY,
            source text NOT NULL,
            scope text NOT NULL,
            kind text NOT NULL CHECK (kind IN ('fixed', 'average_speed')),
            location geography(Point,4326) NOT NULL,
            direction_degrees double precision CHECK
                (direction_degrees >= 0 AND direction_degrees < 360),
            speed_limit_kph double precision CHECK (speed_limit_kph > 0),
            raw jsonb NOT NULL,
            import_id bigint NOT NULL REFERENCES enforcement_imports(id),
            fetched_at timestamptz NOT NULL,
            active boolean NOT NULL DEFAULT true
        );
        CREATE INDEX ix_enforcement_location ON enforcement_controls USING gist(location);
        CREATE INDEX ix_enforcement_scope ON enforcement_controls(source, scope);
    """)


def downgrade() -> None:
    op.execute("DROP TABLE enforcement_controls; DROP TABLE enforcement_imports")
