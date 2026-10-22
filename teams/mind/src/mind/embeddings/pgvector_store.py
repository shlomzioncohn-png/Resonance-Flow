"""
pgvector-backed VectorIndex. Only file that knows Postgres/pgvector
details -- a different backend would be a sibling implementing
base.VectorIndex and base.VectorStore.

Uses psycopg3 (async), not psycopg2, so DB calls don't block the event
loop under FastAPI.
"""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import LiteralString
from uuid import UUID

from psycopg import AsyncCursor, sql
from psycopg.rows import TupleRow
from psycopg_pool import AsyncConnectionPool

from mind.config import VectorStoreSettings
from mind.embeddings.base import SearchResult, VectorIndex, VectorStore
from mind.embeddings.embeddings import embed_async

logger = logging.getLogger(__name__)


class PgVectorStore(VectorIndex):
    """Owns the connection pool. Use for_tenant() for reads and writes."""

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

    @asynccontextmanager
    async def _cursor(self) -> AsyncIterator[AsyncCursor[TupleRow]]:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            yield cur

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

    def for_tenant(self, tenant_id: UUID) -> "PgTenantStore":
        return PgTenantStore(self, tenant_id)

    async def tenant_of(self, task_id: str) -> UUID | None:
        async with self._cursor() as cur:
            await cur.execute(
                sql.SQL("SELECT tenant_id FROM {table} WHERE task_id = %s;").format(
                    table=self._table()
                ),
                (task_id,),
            )
            row = await cur.fetchone()
            return row[0] if row else None

    async def clear(self) -> None:
        """Test teardown only. Every tenant."""
        async with self._cursor() as cur:
            await cur.execute(sql.SQL("TRUNCATE TABLE {table};").format(table=self._table()))


class PgTenantStore(VectorStore):
    """One tenant's rows: every statement carries `tenant_id = %s`."""

    def __init__(self, index: PgVectorStore, tenant_id: UUID) -> None:
        self._index = index
        self._tenant_id = tenant_id

    async def _execute(self, query: LiteralString, *params: object) -> int:
        async with self._index._cursor() as cur:
            await cur.execute(sql.SQL(query).format(table=self._index._table()), params)
            return cur.rowcount

    async def _fetch_one(self, query: LiteralString, *params: object) -> TupleRow | None:
        async with self._index._cursor() as cur:
            await cur.execute(sql.SQL(query).format(table=self._index._table()), params)
            return await cur.fetchone()

    async def upsert(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> None:
        # A row indexed before #841 has no tenant and takes this one.
        # Another tenant's row is never overwritten.
        written = await self._execute(
            """
            INSERT INTO {table} AS t (task_id, tenant_id, embedding, content_hash)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (task_id) DO UPDATE SET
                tenant_id = EXCLUDED.tenant_id,
                embedding = EXCLUDED.embedding,
                content_hash = EXCLUDED.content_hash
            WHERE t.tenant_id IS NULL OR t.tenant_id = EXCLUDED.tenant_id;
            """,
            task_id,
            self._tenant_id,
            vector,
            content_hash,
        )
        if not written:
            logger.warning(
                "vector store: task %s is indexed under another tenant, not overwritten", task_id
            )

    async def upsert_if_absent(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> bool:
        inserted = await self._execute(
            """
            INSERT INTO {table} (task_id, tenant_id, embedding, content_hash)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (task_id) DO NOTHING;
            """,
            task_id,
            self._tenant_id,
            vector,
            content_hash,
        )
        return inserted > 0

    async def get_vector(self, task_id: str) -> list[float] | None:
        row = await self._fetch_one(
            "SELECT embedding FROM {table} WHERE task_id = %s AND tenant_id = %s;",
            task_id,
            self._tenant_id,
        )
        # No registered adapter for `vector` -- comes back as text, e.g. "[0.1,0.2]" (valid JSON).
        return json.loads(row[0]) if row else None

    async def get_content_hash(self, task_id: str) -> str | None:
        row = await self._fetch_one(
            "SELECT content_hash FROM {table} WHERE task_id = %s AND tenant_id = %s;",
            task_id,
            self._tenant_id,
        )
        return row[0] if row else None

    async def mark_completed(self, task_id: str) -> bool:
        updated = await self._execute(
            "UPDATE {table} SET is_completed = TRUE WHERE task_id = %s AND tenant_id = %s;",
            task_id,
            self._tenant_id,
        )
        return updated > 0

    async def mark_incomplete(self, task_id: str) -> bool:
        updated = await self._execute(
            "UPDATE {table} SET is_completed = FALSE WHERE task_id = %s AND tenant_id = %s;",
            task_id,
            self._tenant_id,
        )
        return updated > 0

    async def delete(self, task_id: str) -> None:
        await self._execute(
            "DELETE FROM {table} WHERE task_id = %s AND tenant_id = %s;",
            task_id,
            self._tenant_id,
        )

    def _search_query(self, *, exclude_completed: bool, exclude_task_id: bool) -> sql.Composed:
        # ORDER BY the raw <=> expression -- pgvector can't match the HNSW
        # index to an alias. Conditions go in WHERE, not a post-hoc filter,
        # so a small k only ever counts qualifying rows.
        conditions = [sql.SQL("tenant_id = %s")]
        if exclude_completed:
            conditions.append(sql.SQL("NOT is_completed"))
        if exclude_task_id:
            conditions.append(sql.SQL("task_id != %s"))
        return sql.SQL(
            """
            SELECT task_id, 1 - (embedding <=> %s::vector) AS similarity, is_completed
            FROM {table}
            WHERE {conditions}
            ORDER BY embedding <=> %s::vector
            LIMIT %s;
            """
        ).format(table=self._index._table(), conditions=sql.SQL(" AND ").join(conditions))

    async def search(
        self,
        query_vector: list[float],
        k: int,
        *,
        exclude_completed: bool = False,
        exclude_task_id: str | None = None,
    ) -> list[SearchResult]:
        async with self._index._cursor() as cur:
            # set_config(), not SET LOCAL -- that can't take a bound param.
            await cur.execute(
                "SELECT set_config('hnsw.ef_search', %s, true);",
                (str(self._index._settings.search_effort),),
            )
            # Always on: the tenant condition can leave fewer than k matching
            # rows inside the ef_search window. Keeps Postgres scanning until
            # it has k (pgvector >=0.8).
            await cur.execute("SELECT set_config('hnsw.iterative_scan', 'strict_order', true);")
            params: list[object] = [query_vector, self._tenant_id]
            if exclude_task_id is not None:
                params.append(exclude_task_id)
            params.extend([query_vector, k])
            await cur.execute(
                self._search_query(
                    exclude_completed=exclude_completed, exclude_task_id=exclude_task_id is not None
                ),
                tuple(params),
            )
            rows = await cur.fetchall()
            return [
                SearchResult(task_id=row[0], similarity=row[1], is_completed=row[2]) for row in rows
            ]

    async def search_text(self, query: str, k: int) -> list[SearchResult]:
        """search(), embedding `query` as is_query=True first."""
        vector = await embed_async(query, is_query=True)
        return await self.search(vector, k)
