"""
pgvector-backed implementation of VectorStore.

This is the ONLY file in the codebase that is allowed to know pgvector /
Postgres-specific details (SQL, connection strings, the `<=>` operator,
psycopg2, etc). If we ever swap databases, this file gets replaced by e.g.
`qdrant_store.py` and nothing outside `factory.py` needs to change. Moved
from the PoC (`Choose a vector database/mind_vector_poc/mind_vector/pgvector_store.py`).
"""

from psycopg2 import pool

from mind.embeddings.base import VectorStore
from mind.embeddings.config import HNSW_EF_SEARCH, PG_DSN, PG_TABLE
from mind.embeddings.generate import EMBEDDING_DIM


class PgVectorStore(VectorStore):
    def __init__(self, dsn: str = PG_DSN, min_conn: int = 1, max_conn: int = 5):
        self._pool = pool.SimpleConnectionPool(min_conn, max_conn, dsn)
        self._ensure_schema()

    def _ensure_schema(self) -> None:
        conn = self._pool.getconn()
        try:
            with conn, conn.cursor() as cur:
                cur.execute("CREATE EXTENSION IF NOT EXISTS vector;")
                cur.execute(
                    f"""
                    CREATE TABLE IF NOT EXISTS {PG_TABLE} (
                        task_id TEXT PRIMARY KEY,
                        embedding VECTOR({EMBEDDING_DIM}) NOT NULL
                    );
                    """
                )
                # HNSW index for fast approximate cosine-distance search.
                cur.execute(
                    f"""
                    CREATE INDEX IF NOT EXISTS {PG_TABLE}_embedding_hnsw_idx
                    ON {PG_TABLE} USING hnsw (embedding vector_cosine_ops);
                    """
                )
        finally:
            self._pool.putconn(conn)

    def upsert(self, task_id: str, vector: list[float]) -> None:
        conn = self._pool.getconn()
        try:
            with conn, conn.cursor() as cur:
                cur.execute(
                    f"""
                    INSERT INTO {PG_TABLE} (task_id, embedding)
                    VALUES (%s, %s)
                    ON CONFLICT (task_id) DO UPDATE SET embedding = EXCLUDED.embedding;
                    """,
                    (task_id, vector),
                )
        finally:
            self._pool.putconn(conn)

    def search(self, query_vector: list[float], k: int) -> list[str]:
        conn = self._pool.getconn()
        try:
            with conn, conn.cursor() as cur:
                # pgvector's default hnsw.ef_search (40) trades recall for
                # speed more aggressively than this table's latency budget
                # requires -- see config.HNSW_EF_SEARCH and the PoC's
                # scale+recall evaluation history for the measurement
                # (61% -> 86% ANN-vs-exact recall@10 at negligible cost).
                cur.execute("SET LOCAL hnsw.ef_search = %s;", (HNSW_EF_SEARCH,))
                cur.execute(
                    f"""
                    SELECT task_id
                    FROM {PG_TABLE}
                    ORDER BY embedding <=> %s::vector
                    LIMIT %s;
                    """,
                    (query_vector, k),
                )
                return [row[0] for row in cur.fetchall()]
        finally:
            self._pool.putconn(conn)
