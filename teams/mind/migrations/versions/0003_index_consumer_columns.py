"""index consumer columns: is_completed, content_hash

#470's index consumer needs these on task_embeddings:
- is_completed: DONE/REJECTED/CANCELLED, so a duplicate/dependency search
  doesn't surface a finished task as a live match.
- content_hash: lets the consumer skip re-embedding title/description
  text it's already indexed (e.g. Chain's scheduling updates, which
  publish task.updated without changing either field).

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-12

"""

from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | None = None
depends_on: str | None = None

TABLE = "task_embeddings"


def upgrade() -> None:
    op.execute(f"ALTER TABLE {TABLE} ADD COLUMN is_completed BOOLEAN NOT NULL DEFAULT FALSE;")
    op.execute(f"ALTER TABLE {TABLE} ADD COLUMN content_hash TEXT;")


def downgrade() -> None:
    op.execute(f"ALTER TABLE {TABLE} DROP COLUMN content_hash;")
    op.execute(f"ALTER TABLE {TABLE} DROP COLUMN is_completed;")
