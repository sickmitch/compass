"""Preserve rejected OSM records without blocking valid inventory imports."""
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE enforcement_imports
        ADD COLUMN rejected jsonb NOT NULL DEFAULT '[]'::jsonb
    """)


def downgrade() -> None:
    op.execute("ALTER TABLE enforcement_imports DROP COLUMN rejected")
