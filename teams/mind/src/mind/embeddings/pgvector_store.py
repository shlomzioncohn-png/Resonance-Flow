"""
pgvector-backed implementation of VectorStore.

This is the ONLY file in the codebase that is allowed to know pgvector /
Postgres-specific details (SQL, connection strings, the `<=>` operator,
psycopg, etc). If we ever swap databases, this file gets replaced by e.g.
`qdrant_store.py` implementing the same `base.VectorStore` contract --
callers construct it directly, wherever they currently construct
`PgVectorStore`.

Uses psycopg (v3), not psycopg2 -- FastAPI route handlers declared
`async def` run on the event loop, and a blocking psycopg2 call there
would stall every other request being served by that loop. psycopg3's
async API keeps the DB call itself non-blocking.
"""

import asyncio

from psycopg import sql
from psycopg_pool import AsyncConnectionPool

from mind.config import VectorStoreSettings
from mind.embeddings.base import SearchResult, VectorStore


class PgVectorStore(VectorStore):
    def __init__(
        self,
        settings: VectorStoreSettings,
        *,
        min_conn: int = 1,
        max_conn: int = 5,
        pool_timeout: float = 30.0,
    ) -> None:
        # No I/O here -- opening the pool and creating the schema are async,
        # so both happen lazily on first use (see _ensure_ready), unless
        # open() is called explicitly first.
        self._settings = settings
        self._pool = AsyncConnectionPool(
            str(settings.dsn.get_secret_value()),
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

    def _table(self) -> sql.Identifier:
        return sql.Identifier(self._settings.table)

    async def _ensure_schema(self) -> None:
        table = self._table()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute("CREATE EXTENSION IF NOT EXISTS vector;")
            await cur.execute(
                sql.SQL(
                    """
                    CREATE TABLE IF NOT EXISTS {table} (
                        task_id TEXT PRIMARY KEY,
                        embedding VECTOR({dim}) NOT NULL
                    );
                    """
                ).format(table=table, dim=sql.Literal(self._settings.embedding_dim))
            )
            await cur.execute(
                sql.SQL(
                    "ALTER TABLE {table} ADD COLUMN IF NOT EXISTS"
                    " is_completed BOOLEAN NOT NULL DEFAULT FALSE;"
                ).format(table=table)
            )
            # HNSW index for fast approximate cosine-distance search.
            await cur.execute(
                sql.SQL(
                    """
                    CREATE INDEX IF NOT EXISTS {idx}
                    ON {table} USING hnsw (embedding vector_cosine_ops);
                    """
                ).format(
                    idx=sql.Identifier(f"{self._settings.table}_embedding_hnsw_idx"),
                    table=table,
                )
            )

    async def upsert(self, task_id: str, vector: list[float]) -> None:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL(
                    """
                    INSERT INTO {table} (task_id, embedding)
                    VALUES (%s, %s)
                    ON CONFLICT (task_id) DO UPDATE SET embedding = EXCLUDED.embedding;
                    """
                ).format(table=self._table()),
                (task_id, vector),
            )

    async def upsert_if_absent(self, task_id: str, vector: list[float]) -> bool:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL(
                    """
                    INSERT INTO {table} (task_id, embedding)
                    VALUES (%s, %s)
                    ON CONFLICT (task_id) DO NOTHING;
                    """
                ).format(table=self._table()),
                (task_id, vector),
            )
            return cur.rowcount > 0

    async def mark_completed(self, task_id: str) -> bool:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL("UPDATE {table} SET is_completed = TRUE WHERE task_id = %s;").format(
                    table=self._table()
                ),
                (task_id,),
            )
            return cur.rowcount > 0

    async def delete(self, task_id: str) -> None:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL("DELETE FROM {table} WHERE task_id = %s;").format(table=self._table()),
                (task_id,),
            )

    async def clear(self) -> None:
        """Remove every stored vector. Intended for test teardown, not production use."""
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(sql.SQL("TRUNCATE TABLE {table};").format(table=self._table()))

    def _search_query(self) -> sql.Composed:
        # ORDER BY the raw distance expression (embedding <=> $1), not a
        # computed "similarity" alias -- pgvector can only match the HNSW
        # index to the distance operator itself, so ordering by an alias
        # (even one derived from it) forces a full table scan instead.
        return sql.SQL(
            """
            SELECT task_id, 1 - (embedding <=> %s::vector) AS similarity, is_completed
            FROM {table}
            ORDER BY embedding <=> %s::vector
            LIMIT %s;
            """
        ).format(table=self._table())

    async def search(self, query_vector: list[float], k: int) -> list[SearchResult]:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            # set_config(), not "SET LOCAL ... = %s": Postgres rejects a
            # bound parameter inside a SET statement, and set_config() is a
            # plain function call that accepts one. is_local=true keeps the
            # same transaction-scoped semantics SET LOCAL would have had.
            await cur.execute(
                "SELECT set_config('hnsw.ef_search', %s, true);",
                (str(self._settings.search_effort),),
            )
            await cur.execute(self._search_query(), (query_vector, query_vector, k))
            rows = await cur.fetchall()
            return [
                SearchResult(task_id=row[0], similarity=row[1], is_completed=row[2])
                for row in rows
            ]
