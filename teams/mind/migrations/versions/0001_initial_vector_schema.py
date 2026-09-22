"""initial vector schema

Written by hand, not by `alembic revision --autogenerate`: pgvector's
`VECTOR(n)` type isn't understood by SQLAlchemy/Alembic's reflection, so
autogenerate wouldn't produce it correctly. Raw `op.execute()` with
explicit SQL is the reliable option here.

Assumes the `vector` extension is already enabled -- see
`db/init/01-enable-pgvector.sql`. That step deliberately stays outside
Alembic: it needs superuser, and the whole point of this migration is
that the app's own DB role doesn't need DDL permissions, let alone
superuser, at runtime.

Revision ID: 0001
Revises:
Create Date: 2026-09-22

"""

from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0001"
down_revision: str | None = None
branch_labels: str | None = None
depends_on: str | None = None

# Baked in on purpose, not read from mind.config.Settings: a migration
# must be deterministic regardless of whatever the environment happens to
# have configured when it runs. If embedding_dim ever needs to change,
# that's a new migration (ALTER COLUMN ... TYPE VECTOR(new_dim), with a
# real plan for re-embedding existing rows) -- not a config edit. Changing
# VectorStoreSettings.embedding_dim alone, without a matching migration,
# would silently disagree with this table's actual column width; nothing
# catches that mismatch except the database rejecting the next insert.
TABLE = "task_embeddings"
EMBEDDING_DIM = 256


def upgrade() -> None:
    op.execute(
        f"""
        CREATE TABLE {TABLE} (
            task_id TEXT PRIMARY KEY,
            embedding VECTOR({EMBEDDING_DIM}) NOT NULL
        );
        """
    )
    # Built directly (not CONCURRENTLY): the table is empty at migration
    # time, so there's no lock contention to avoid yet. If this migration
    # is ever copied as a template for a table that already holds data,
    # use CONCURRENTLY instead -- it can't run inside the transaction
    # Alembic wraps this in, so that needs
    # `with op.get_context().autocommit_block():`.
    op.execute(
        f"""
        CREATE INDEX {TABLE}_embedding_hnsw_idx
        ON {TABLE} USING hnsw (embedding vector_cosine_ops);
        """
    )


def downgrade() -> None:
    op.execute(f"DROP INDEX IF EXISTS {TABLE}_embedding_hnsw_idx;")
    op.execute(f"DROP TABLE IF EXISTS {TABLE};")
