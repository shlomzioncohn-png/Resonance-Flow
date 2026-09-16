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

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.config import EMBEDDING_DIM, HNSW_EF_SEARCH, PG_TABLE, get_settings


def _require_dsn() -> str:
    # Deferred fail-fast: importing this module never requires a DSN (see
    # config.Settings); constructing a PgVectorStore does.
    dsn = get_settings().vector_store_dsn
    if not dsn:
        raise RuntimeError(
            "MIND_VECTOR_STORE_DSN is not set. Required to connect to the "
            "vector store; code that only needs embed() doesn't need it."
        )
    return dsn


class PgVectorStore(VectorStore):
    def __init__(
        self,
        dsn: str | None = None,
        min_conn: int = 1,
        max_conn: int = 5,
        *,
        pool_timeout: float = 30.0,
    ) -> None:
        # No I/O here -- opening the pool and creating the schema are async,
        # so both happen lazily on first use (see _ensure_ready), unless
        # open() is called explicitly first.
        self._pool = AsyncConnectionPool(
            dsn or _require_dsn(),
            min_size=min_conn,
            max_size=max_conn,
            timeout=pool_timeout,
            open=False,
        )
        self._ready = False
        self._ready_lock = asyncio.Lock()

    async def open(self) -> None:
        await self._ensure_ready()

    async def close(self) -> None:
        if self._ready:
            await self._pool.close()
            self._ready = False

    async def _ensure_ready(self) -> None:
        # Double-checked locking: two concurrent callers could otherwise
        # both see _ready is False and both try to open the pool at once.
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

    async def search(self, query_vector: list[float], k: int) -> list[SearchResult]:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            # set_config(), not "SET LOCAL ... = %s": Postgres rejects a
            # bound parameter inside a SET statement, and set_config() is a
            # plain function call that accepts one. is_local=true keeps the
            # same transaction-scoped semantics SET LOCAL would have had.
            await cur.execute(
                "SELECT set_config('hnsw.ef_search', %s, true);",
                (str(HNSW_EF_SEARCH),),
            )
            # `<=>` is cosine distance; `1 - distance` gives cosine
            # similarity, comparable against DUPLICATE_SIMILARITY_THRESHOLD.
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
            return [SearchResult(task_id=row[0], similarity=row[1]) for row in rows]
