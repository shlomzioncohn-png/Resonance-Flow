"""bump embedding dim to 384 (BGE model)

0001 predates the real embedding model and used a 256-dim placeholder.
`embeddings.py`'s EMBEDDING_DIM is now 384 (BAAI/bge-small-en-v1.5).

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-11

"""

from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | None = None
depends_on: str | None = None

TABLE = "task_embeddings"
OLD_DIM = 256
NEW_DIM = 384


def upgrade() -> None:
    op.execute(f"TRUNCATE TABLE {TABLE};")  # old 256-dim vectors are not convertible to 384-dim
    op.execute(f"ALTER TABLE {TABLE} ALTER COLUMN embedding TYPE VECTOR({NEW_DIM});")


def downgrade() -> None:
    op.execute(f"TRUNCATE TABLE {TABLE};")
    op.execute(f"ALTER TABLE {TABLE} ALTER COLUMN embedding TYPE VECTOR({OLD_DIM});")
