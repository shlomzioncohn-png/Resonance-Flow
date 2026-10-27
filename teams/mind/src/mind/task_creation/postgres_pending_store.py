"""#918: `PendingStore` backed by Postgres -- survives a restart, unlike
`PendingRequests`. See task_creation/README.md, "The pending store, and
why the interface is async".

One instance is shared across the pipeline, the listener and the
overdue timer (`TaskCreationFlow` takes exactly one `pending`), each
running on its own thread and its own event loop (README.md, "Three
threads, one store") -- but an async connection pool belongs to the loop
that opened it, so one pool cannot serve all three. Instead this keeps
one pool per loop, opened lazily the first time each loop calls in
(`_pool_for_this_loop`), guarded by a plain `threading.Lock` since the
loops themselves run on different OS threads -- an `asyncio.Lock` only
protects against concurrent coroutines on *one* loop, not three loops on
three threads at once.

`renew` and `mark_reported` get their check-and-write guarantee from a
single statement (`UPDATE ... WHERE ...`), not a read followed by a
write: a row already gone (discarded because it was answered) simply
matches no rows, so both are naturally a no-op on it, matching
`PendingRequests`' own behavior exactly.
"""

from __future__ import annotations

import asyncio
import threading
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from uuid import UUID

from psycopg import AsyncCursor
from psycopg.rows import TupleRow
from psycopg_pool import AsyncConnectionPool

from mind.task_creation.pending import PendingRequest, PendingStore

_TABLE = "pending_creation_requests"


def _to_request(row: TupleRow) -> PendingRequest:
    bridge_correlation_id, revision, task_index, tenant_id, dependency_mentions, sent_at = row
    return PendingRequest(
        bridge_correlation_id=bridge_correlation_id,
        revision=revision,
        task_index=task_index,
        tenant_id=tenant_id,
        dependency_mentions=tuple(dependency_mentions),
        sent_at=sent_at,
    )


class PostgresPendingStore:
    """`PendingStore` (see pending.py) backed by `pending_creation_requests`
    (migration 0005). Safe to share across several threads, each on its
    own event loop -- see the module docstring."""

    def __init__(self, dsn: str, *, min_conn: int = 1, max_conn: int = 5) -> None:
        self._dsn = dsn
        self._min_conn = min_conn
        self._max_conn = max_conn
        self._pools_lock = threading.Lock()
        self._pools: dict[asyncio.AbstractEventLoop, AsyncConnectionPool] = {}

    async def open(self) -> None:
        """Opens this loop's pool now, rather than lazily on the first
        call. Only this loop's pool: each caller opens its own."""
        await self._pool_for_this_loop()

    async def close(self) -> None:
        """Closes only the calling loop's own pool, the same restriction
        as opening one: `AsyncConnectionPool.close()` schedules work on
        the loop that owns it, and raises if that loop is no longer the
        one running (or is already closed). Each loop that opened a
        pool here is responsible for closing it itself."""
        loop = asyncio.get_running_loop()
        with self._pools_lock:
            pool = self._pools.pop(loop, None)
        if pool is not None:
            await pool.close()

    async def _pool_for_this_loop(self) -> AsyncConnectionPool:
        loop = asyncio.get_running_loop()
        with self._pools_lock:
            pool = self._pools.get(loop)
        if pool is not None:
            return pool
        new_pool = AsyncConnectionPool(
            self._dsn, min_size=self._min_conn, max_size=self._max_conn, open=False
        )
        await new_pool.open()
        with self._pools_lock:
            existing = self._pools.get(loop)
            if existing is not None:
                # Another coroutine on this same loop opened one first.
                await new_pool.close()
                return existing
            self._pools[loop] = new_pool
        return new_pool

    @asynccontextmanager
    async def _cursor(self) -> AsyncIterator[AsyncCursor[TupleRow]]:
        pool = await self._pool_for_this_loop()
        async with pool.connection() as conn, conn.cursor() as cur:
            yield cur

    async def add(self, correlation_id: UUID, request: PendingRequest) -> bool:
        async with self._cursor() as cur:
            await cur.execute(
                f"""
                INSERT INTO {_TABLE}
                    (correlation_id, bridge_correlation_id, revision, task_index,
                     tenant_id, dependency_mentions, sent_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (correlation_id) DO NOTHING;
                """,
                (
                    correlation_id,
                    request.bridge_correlation_id,
                    request.revision,
                    request.task_index,
                    request.tenant_id,
                    list(request.dependency_mentions),
                    request.sent_at,
                ),
            )
            return cur.rowcount > 0

    async def get(self, correlation_id: UUID) -> PendingRequest | None:
        async with self._cursor() as cur:
            await cur.execute(
                f"""
                SELECT bridge_correlation_id, revision, task_index, tenant_id,
                       dependency_mentions, sent_at
                FROM {_TABLE} WHERE correlation_id = %s;
                """,
                (correlation_id,),
            )
            row = await cur.fetchone()
            return None if row is None else _to_request(row)

    async def renew(self, correlation_id: UUID, request: PendingRequest) -> None:
        async with self._cursor() as cur:
            await cur.execute(
                f"""
                UPDATE {_TABLE}
                SET bridge_correlation_id = %s, revision = %s, task_index = %s,
                    tenant_id = %s, dependency_mentions = %s, sent_at = %s,
                    reported_at = NULL
                WHERE correlation_id = %s;
                """,
                (
                    request.bridge_correlation_id,
                    request.revision,
                    request.task_index,
                    request.tenant_id,
                    list(request.dependency_mentions),
                    request.sent_at,
                    correlation_id,
                ),
            )

    async def discard(self, correlation_id: UUID) -> None:
        async with self._cursor() as cur:
            await cur.execute(f"DELETE FROM {_TABLE} WHERE correlation_id = %s;", (correlation_id,))

    async def mark_reported(self, correlation_id: UUID) -> bool:
        async with self._cursor() as cur:
            await cur.execute(
                f"""
                UPDATE {_TABLE} SET reported_at = now()
                WHERE correlation_id = %s AND reported_at IS NULL;
                """,
                (correlation_id,),
            )
            return cur.rowcount > 0

    async def all(self) -> list[tuple[UUID, PendingRequest]]:
        async with self._cursor() as cur:
            await cur.execute(
                f"""
                SELECT correlation_id, bridge_correlation_id, revision, task_index,
                       tenant_id, dependency_mentions, sent_at
                FROM {_TABLE};
                """
            )
            rows = await cur.fetchall()
            return [(row[0], _to_request(row[1:])) for row in rows]


def _postgres_fits(store: PostgresPendingStore) -> PendingStore:
    """Checked by mypy, never called."""

    return store
