"""Dependency mentions waiting for their target to exist (#918, extending
#834/#355's "no retry" scope -- see README.md, "Retrying from the index
consumer").

`PendingDependencyStore` in memory (`PendingDependencies`) and its
Postgres-backed twin (`postgres_pending.py`) share the same shape:
`(task_id, mention)` keyed, looked up by `tenant_id` so a newly indexed
task only re-resolves mentions that could plausibly target it.
"""

import threading
from typing import Protocol
from uuid import UUID


class PendingDependencyStore(Protocol):
    """Where an unresolved dependency mention waits for a retry."""

    async def add(self, task_id: UUID, tenant_id: UUID, mention: str) -> None:
        """Register a still-unresolved mention; a no-op if already pending."""
        ...

    async def discard(self, task_id: UUID, mention: str) -> None:
        """Forget a mention once it resolves; a no-op if it wasn't pending."""
        ...

    async def all_for_tenant(self, tenant_id: UUID) -> list[tuple[UUID, str]]:
        """Every (task_id, mention) still pending for one tenant (#841)."""
        ...


class PendingDependencies:
    """`PendingDependencyStore` in memory. One thread lock, the same
    reasoning as `task_creation.pending.PendingRequests`: the publisher
    (on a task.created reply) and the index-consumer retry (on every
    task.created) can both touch this from their own thread --
    *when they run in the same process*.

    WARNING: in production they don't. `landed_consumer_runner.py` (the
    publisher) and `index_consumer_runner.py` (the retry) are two separate
    OS processes, so two `PendingDependencies()` instances never see each
    other's writes -- see `postgres_pending.pending_dependency_store`'s
    docstring. This class is only coherent as the backend for both roles
    inside a single process, e.g. a test wiring them together directly."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._mentions: dict[tuple[UUID, str], UUID] = {}

    async def add(self, task_id: UUID, tenant_id: UUID, mention: str) -> None:
        with self._lock:
            self._mentions[(task_id, mention)] = tenant_id

    async def discard(self, task_id: UUID, mention: str) -> None:
        with self._lock:
            self._mentions.pop((task_id, mention), None)

    async def all_for_tenant(self, tenant_id: UUID) -> list[tuple[UUID, str]]:
        with self._lock:
            return [key for key, tenant in self._mentions.items() if tenant == tenant_id]


def _in_memory_fits(store: PendingDependencies) -> PendingDependencyStore:
    """Checked by mypy, never called."""

    return store
