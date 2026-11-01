"""#918: `PendingDependencyStore` backed by Postgres (`pending_dependencies`,
migration 0005) -- survives a restart, unlike `PendingDependencies`.

Same pool-per-loop reasoning as `task_creation.postgres_pending_store`:
construct one instance per thread/loop that touches it, not one shared
across the publisher's thread and the index consumer's.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from uuid import UUID

from psycopg import AsyncCursor
from psycopg.rows import TupleRow
from psycopg_pool import AsyncConnectionPool

from mind.config import Settings
from mind.dependencies.pending import PendingDependencies, PendingDependencyStore

_TABLE = "pending_dependencies"


class PostgresPendingDependencyStore:
    """`PendingDependencyStore` (see pending.py) backed by
    `pending_dependencies` (migration 0005)."""

    def __init__(self, dsn: str, *, min_conn: int = 1, max_conn: int = 5) -> None:
        self._pool = AsyncConnectionPool(dsn, min_size=min_conn, max_size=max_conn, open=False)
        self._ready = False
        self._ready_lock = asyncio.Lock()

    async def open(self) -> None:
        await self._ensure_ready()

    async def close(self) -> None:
        if self._ready:
            await self._pool.close()
            self._ready = False

    async def _ensure_ready(self) -> None:
        if self._ready:
            return
        async with self._ready_lock:
            if self._ready:
                return
            await self._pool.open()
            self._ready = True

    @asynccontextmanager
    async def _cursor(self) -> AsyncIterator[AsyncCursor[TupleRow]]:
        await self._ensure_ready()
        async with self._pool.connection() as conn, conn.cursor() as cur:
            yield cur

    async def add(self, task_id: UUID, tenant_id: UUID, mention: str) -> None:
        async with self._cursor() as cur:
            await cur.execute(
                f"""
                INSERT INTO {_TABLE} (task_id, tenant_id, mention)
                VALUES (%s, %s, %s)
                ON CONFLICT (task_id, mention) DO NOTHING;
                """,
                (task_id, tenant_id, mention),
            )

    async def discard(self, task_id: UUID, mention: str) -> None:
        async with self._cursor() as cur:
            await cur.execute(
                f"DELETE FROM {_TABLE} WHERE task_id = %s AND mention = %s;",
                (task_id, mention),
            )

    async def all_for_tenant(self, tenant_id: UUID) -> list[tuple[UUID, str]]:
        async with self._cursor() as cur:
            await cur.execute(
                f"SELECT task_id, mention FROM {_TABLE} WHERE tenant_id = %s;",
                (tenant_id,),
            )
            rows = await cur.fetchall()
            return [(row[0], row[1]) for row in rows]


def _postgres_fits(store: PostgresPendingDependencyStore) -> PendingDependencyStore:
    """Checked by mypy, never called."""

    return store


def pending_dependency_store(settings: Settings) -> PendingDependencyStore:
    """`PendingDependencies` or `PostgresPendingDependencyStore`, by
    `settings.pending_store_backend` -- the one factory both
    `index_consumer_runner.py` and `landed_consumer_runner.py` use,
    instead of each keeping its own copy.

    WARNING: `"memory"` only makes sense if both call sites above run in
    the same process. They don't -- each is its own OS process -- so with
    `"memory"` the two `PendingDependencies()` instances this returns are
    unrelated objects in separate address spaces: a mention one process
    adds is simply never visible to the other, and dependency retry
    (#918) silently never fires. `"memory"` is a real, working choice for
    `task_creation.postgres_pending_store`'s equivalent factory, since
    that store is only ever shared within `landed_consumer_runner.py`'s
    one process -- it is not a safe default here.
    """
    if settings.pending_store_backend == "memory":
        return PendingDependencies()
    return PostgresPendingDependencyStore(str(settings.vector_store.dsn.get_secret_value()))
