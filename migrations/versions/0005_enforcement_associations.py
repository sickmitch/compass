"""Versioned source-to-directed-road association evidence."""
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE enforcement_associations (
            source_key text PRIMARY KEY REFERENCES enforcement_controls(source_key),
            import_id bigint NOT NULL REFERENCES enforcement_imports(id),
            graph_identity text NOT NULL,
            state text NOT NULL,
            reason text NOT NULL,
            evidence jsonb NOT NULL,
            matched_at timestamptz NOT NULL DEFAULT now()
        )
    """)


def downgrade() -> None:
    op.execute("DROP TABLE enforcement_associations")
