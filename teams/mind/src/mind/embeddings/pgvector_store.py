"""
pgvector-backed implementation of VectorStore.

This is the ONLY file in the codebase that is allowed to know pgvector /
Postgres-specific details (SQL, connection strings, the `<=>` operator,
psycopg, etc). If we ever swap databases, this file gets replaced by e.g.
`qdrant_store.py` and nothing outside `factory.py` needs to change.

Uses psycopg (v3), not psycopg2 -- FastAPI route handlers declared
`async def` run on the event loop, and a blocking psycopg2 call there
would stall every other request being served by that loop. psycopg3's
async API keeps the DB call itself non-blocking.
"""

import asyncio

from psycopg_pool import AsyncConnectionPool

from mind.embeddings.base import VectorStore
from mind.embeddings.config import EMBEDDING_DIM, HNSW_EF_SEARCH, PG_DSN, PG_TABLE


class PgVectorStore(VectorStore):
    def __init__(self, dsn: str = PG_DSN, min_conn: int = 1, max_conn: int = 5) -> None:
        # No I/O here. Opening connections and creating the schema are
        # async operations, and __init__ can't be async -- so both happen
        # lazily, once, on first real use (see `_ensure_ready`).
        self._pool = AsyncConnectionPool(dsn, min_size=min_conn, max_size=max_conn, open=False)
        self._ready = False
        self._ready_lock = asyncio.Lock()

    async def _ensure_ready(self) -> None:
        # Double-checked locking: two concurrent calls on the event loop
        # (e.g. two requests arriving before the first has finished
        # opening the pool) could otherwise both see `_ready is False` and
        # both try to open the pool / create the schema at once.
        if self._ready:
            return
        async with self._ready_lock:
            if self._ready:
                return
            await self._pool.open()
            await self._ensure_schema()
            self._ready = True

    async def _ensure_schema(self) -> None:
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute("CREATE EXTENSION IF NOT EXISTS vector;")
            await cur.execute(
                f"""
                    CREATE TABLE IF NOT EXISTS {PG_TABLE} (
                        task_id TEXT PRIMARY KEY,
                        embedding VECTOR({EMBEDDING_DIM}) NOT NULL
                    );
                    """
            )
            # HNSW index for fast approximate cosine-distance search.
            await cur.execute(
                f"""
                    CREATE INDEX IF NOT EXISTS {PG_TABLE}_embedding_hnsw_idx
                    ON {PG_TABLE} USING hnsw (embedding vector_cosine_ops);
                    """
            )

    async def upsert(self, task_id: str, vector: list[float]) -> None:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                f"""
                    INSERT INTO {PG_TABLE} (task_id, embedding)
                    VALUES (%s, %s)
                    ON CONFLICT (task_id) DO UPDATE SET embedding = EXCLUDED.embedding;
                    """,
                (task_id, vector),
            )

    async def delete(self, task_id: str) -> None:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(f"DELETE FROM {PG_TABLE} WHERE task_id = %s;", (task_id,))

    async def clear(self) -> None:
        """Remove every stored vector. Intended for test teardown, not production use."""
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(f"TRUNCATE TABLE {PG_TABLE};")

    async def search(self, query_vector: list[float], k: int) -> list[tuple[str, float]]:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            # pgvector's default hnsw.ef_search (40) favors speed over
            # recall more than this table's latency budget requires --
            # see config.HNSW_EF_SEARCH for the measured tradeoff
            # (raising it to 400 improves ANN-vs-exact recall@10 from
            # 61% to 86% at negligible extra latency).
            #
            # SET LOCAL only applies for the current transaction.
            # psycopg connections default to autocommit=False (like
            # psycopg2's did), so this SET and the SELECT below share
            # one transaction and the setting takes effect -- if
            # autocommit were ever turned on for this pool, SET LOCAL
            # would become a silent no-op. See
            # test_ef_search_setting_is_actually_applied in
            # tests/test_vector_store.py, which asserts this directly
            # instead of just trusting it.
            await cur.execute("SET LOCAL hnsw.ef_search = %s;", (HNSW_EF_SEARCH,))
            # `<=>` is pgvector's cosine *distance* (0 = identical);
            # `1 - distance` converts it to cosine *similarity* so
            # callers can compare it directly against
            # config.DUPLICATE_SIMILARITY_THRESHOLD. Ordering by the
            # `similarity` alias (descending) is equivalent to ordering
            # by distance ascending, and Postgres allows ORDER BY to
            # reference a SELECT-list alias directly.
            await cur.execute(
                f"""
                    SELECT task_id, 1 - (embedding <=> %s::vector) AS similarity
                    FROM {PG_TABLE}
                    ORDER BY similarity DESC
                    LIMIT %s;
                    """,
                (query_vector, k),
            )
            rows = await cur.fetchall()
            return [(row[0], row[1]) for row in rows]
