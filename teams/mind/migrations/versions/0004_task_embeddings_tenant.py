"""task_embeddings.tenant_id (#841)

Every search is scoped to one tenant. Nullable for now: rows written
before this migration have no tenant, never match `tenant_id = ...`, and
get theirs on the next task.updated. A later migration can make it NOT NULL.

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-22

"""

from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | None = None
depends_on: str | None = None

TABLE = "task_embeddings"
INDEX = "task_embeddings_tenant_id_idx"


def upgrade() -> None:
    op.execute(f"ALTER TABLE {TABLE} ADD COLUMN tenant_id UUID;")
    op.execute(f"CREATE INDEX {INDEX} ON {TABLE} (tenant_id);")


def downgrade() -> None:
    op.execute(f"DROP INDEX {INDEX};")
    op.execute(f"ALTER TABLE {TABLE} DROP COLUMN tenant_id;")
