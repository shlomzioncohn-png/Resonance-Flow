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
    # Deferred fail-fast: config.py's Settings deliberately allows a
    # missing DSN so *importing* this module (or mind.embeddings as a
    # whole) never requires a database. This is where that gets enforced
    # instead -- only once something actually tries to talk to Postgres,
    # i.e. only when a PgVectorStore is actually constructed.
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
        # No I/O here. Opening connections and creating the schema are
        # async operations, and __init__ can't be async -- so both happen
        # lazily, once, on first real use (see `_ensure_ready`), unless
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

    async def search(self, query_vector: list[float], k: int) -> list[SearchResult]:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            # pgvector's default hnsw.ef_search (40) favors speed over
            # recall more than this table's latency budget requires --
            # see config.HNSW_EF_SEARCH for the measured tradeoff
            # (raising it to 400 improves ANN-vs-exact recall@10 from
            # 61% to 86% at negligible extra latency).
            #
            # set_config(name, value, is_local=true) -- NOT
            # "SET LOCAL ... = %s" -- because Postgres does not accept a
            # bound parameter in a SET statement. psycopg2 got away with
            # "SET LOCAL ... = %s" because it substituted %s into the SQL
            # text client-side before sending it; psycopg3 sends the SQL
            # and the parameter separately (server-side binding), so
            # Postgres itself would see "SET LOCAL hnsw.ef_search = $1"
            # and reject it with a syntax error. set_config() is a normal
            # function call, so it *can* take a bound parameter, and
            # is_local=true gives the exact same "only this transaction"
            # semantics SET LOCAL has. See
            # test_search_issues_set_local_ef_search_before_the_select in
            # tests/test_vector_store.py, which asserts search() itself
            # issues this, not just that Postgres would honor it if asked
            # -- though that test mocks the cursor, so it doesn't by
            # itself prove Postgres accepts this syntax; that still needs
            # a real pgvector run before this is trusted.
            #
            # Still scoped to one transaction: psycopg connections default
            # to autocommit=False, so this and the SELECT below run in the
            # same transaction. If autocommit were ever turned on for this
            # pool, is_local=true would become a silent no-op.
            await cur.execute(
                "SELECT set_config('hnsw.ef_search', %s, true);",
                (str(HNSW_EF_SEARCH),),
            )
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
            return [SearchResult(task_id=row[0], similarity=row[1]) for row in rows]
