"""
pgvector-backed VectorStore. Only file that knows Postgres/pgvector
details -- a different backend would be a sibling implementing
base.VectorStore.

Uses psycopg3 (async), not psycopg2, so DB calls don't block the event
loop under FastAPI.
"""

import asyncio

from psycopg import sql
from psycopg_pool import AsyncConnectionPool

from mind.config import VectorStoreSettings
from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.embeddings import embed_async


class PgVectorStore(VectorStore):
    def __init__(
        self,
        settings: VectorStoreSettings,
        *,
        min_conn: int = 1,
        max_conn: int = 5,
        pool_timeout: float = 30.0,
    ) -> None:
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
        # Double-checked locking against concurrent first callers.
        if self._ready:
            return
        async with self._ready_lock:
            if self._ready:
                return
            await self._pool.open()
            await self._check_schema_migrated()
            self._ready = True

    def _table(self) -> sql.Identifier:
        return sql.Identifier(self._settings.table)

    async def _check_schema_migrated(self) -> None:
        # Schema is created ahead of time by `alembic upgrade head`
        # (migrations/) -- fail fast with a clear message instead of a
        # bare UndefinedTable on the first real query.
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute("SELECT to_regclass(%s);", (self._settings.table,))
            row = await cur.fetchone()
            if row is None or row[0] is None:
                raise RuntimeError(
                    f"Vector store table {self._settings.table!r} does not exist -- "
                    "schema not migrated. Run `alembic upgrade head` (or `make migrate`)."
                )

    async def upsert(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> None:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL(
                    """
                    INSERT INTO {table} (task_id, embedding, content_hash)
                    VALUES (%s, %s, %s)
                    ON CONFLICT (task_id) DO UPDATE SET
                        embedding = EXCLUDED.embedding, content_hash = EXCLUDED.content_hash;
                    """
                ).format(table=self._table()),
                (task_id, vector, content_hash),
            )

    async def upsert_if_absent(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> bool:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL(
                    """
                    INSERT INTO {table} (task_id, embedding, content_hash)
                    VALUES (%s, %s, %s)
                    ON CONFLICT (task_id) DO NOTHING;
                    """
                ).format(table=self._table()),
                (task_id, vector, content_hash),
            )
            return cur.rowcount > 0

    async def get_content_hash(self, task_id: str) -> str | None:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL("SELECT content_hash FROM {table} WHERE task_id = %s;").format(
                    table=self._table()
                ),
                (task_id,),
            )
            row = await cur.fetchone()
            return row[0] if row else None

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

    async def mark_incomplete(self, task_id: str) -> bool:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(
                sql.SQL("UPDATE {table} SET is_completed = FALSE WHERE task_id = %s;").format(
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
        """Test teardown only."""
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            await cur.execute(sql.SQL("TRUNCATE TABLE {table};").format(table=self._table()))

    def _search_query(self, *, exclude_completed: bool) -> sql.Composed:
        # ORDER BY the raw <=> expression, not the similarity alias --
        # pgvector can't match the HNSW index to an alias. WHERE NOT
        # is_completed (not a post-hoc Python filter) so a small k counts
        # only open candidates -- otherwise k rows of completed tasks would
        # leave nothing, even with a real open match just outside that window.
        where_clause = sql.SQL("WHERE NOT is_completed") if exclude_completed else sql.SQL("")
        return sql.SQL(
            """
            SELECT task_id, 1 - (embedding <=> %s::vector) AS similarity, is_completed
            FROM {table}
            {where_clause}
            ORDER BY embedding <=> %s::vector
            LIMIT %s;
            """
        ).format(table=self._table(), where_clause=where_clause)

    async def search(
        self, query_vector: list[float], k: int, *, exclude_completed: bool = False
    ) -> list[SearchResult]:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            # set_config(), not SET LOCAL -- that can't take a bound param.
            await cur.execute(
                "SELECT set_config('hnsw.ef_search', %s, true);",
                (str(self._settings.search_effort),),
            )
            await cur.execute(
                self._search_query(exclude_completed=exclude_completed), (query_vector, query_vector, k)
            )
            rows = await cur.fetchall()
            return [
                SearchResult(task_id=row[0], similarity=row[1], is_completed=row[2])
                for row in rows
            ]

    async def search_text(self, query: str, k: int) -> list[SearchResult]:
        """search(), embedding `query` as is_query=True first."""
        vector = await embed_async(query, is_query=True)
        return await self.search(vector, k)
