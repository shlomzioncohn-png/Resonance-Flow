"""Shared throwaway-table helper for tests/benchmarks that need their
own dedicated pgvector table. `alembic upgrade head` only manages the
real `task_embeddings` table, so a test/benchmark-owned table (e.g.
`task_embeddings_test`, `task_embeddings_foundation_gate_bench`) has to
set itself up -- one shared helper instead of a copy per caller."""

import psycopg
from psycopg import sql

from mind.config import VectorStoreSettings
from mind.embeddings.embeddings import EMBEDDING_DIM


async def create_vector_store_test_table(settings: VectorStoreSettings) -> None:
    table = sql.Identifier(settings.table)
    async with await psycopg.AsyncConnection.connect(
        str(settings.dsn.get_secret_value()), autocommit=True
    ) as conn:
        await conn.execute("CREATE EXTENSION IF NOT EXISTS vector;")
        await conn.execute(
            sql.SQL(
                "CREATE TABLE IF NOT EXISTS {table} "
                "(task_id TEXT PRIMARY KEY, embedding VECTOR({dim}) NOT NULL, "
                "is_completed BOOLEAN NOT NULL DEFAULT FALSE, content_hash TEXT);"
            ).format(table=table, dim=sql.Literal(EMBEDDING_DIM))
        )
        await conn.execute(
            sql.SQL(
                "CREATE INDEX IF NOT EXISTS {idx} ON {table} USING hnsw (embedding vector_cosine_ops);"
            ).format(idx=sql.Identifier(f"{settings.table}_embedding_hnsw_idx"), table=table)
        )
