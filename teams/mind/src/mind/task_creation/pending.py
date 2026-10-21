"""Creation requests waiting for task.created: `PendingStore`, and `PendingRequests` in memory.

Why async, the in-memory limits, what a Postgres store must handle, and the
locking: README.md, "The pending store, and why the interface is async".
"""

import threading
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol
from uuid import UUID


@dataclass(frozen=True)
class PendingRequest:
    """What Mind needs to recognise the reply, and to update the task later."""

    bridge_correlation_id: UUID
    revision: int  # for a later update flow: a higher one means the content changed
    task_index: int
    tenant_id: UUID
    dependency_mentions: tuple[str, ...] = ()
    sent_at: datetime = field(default_factory=lambda: datetime.now(UTC))


class PendingStore(Protocol):
    """Where requests wait for their reply, keyed by correlation_id."""

    async def add(self, correlation_id: UUID, request: PendingRequest) -> bool:
        """Register a request; False when it was registered already (a retry)."""
        ...

    async def get(self, correlation_id: UUID) -> PendingRequest | None: ...

    async def renew(self, correlation_id: UUID, request: PendingRequest) -> None:
        """Replace an entry still pending after a retry went out; a no-op once
        it is gone, so an entry already answered is never brought back."""
        ...

    async def discard(self, correlation_id: UUID) -> None: ...

    async def mark_reported(self, correlation_id: UUID) -> bool:
        """True the first time a still-pending request is reported overdue;
        False after that, and for one no longer pending. Check and mark are one step."""
        ...

    async def all(self) -> list[tuple[UUID, PendingRequest]]: ...


class PendingRequests:
    """`PendingStore` in memory. Every method holds one thread lock: three
    threads use it (README.md, "Three threads, one store")."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[UUID, PendingRequest] = {}
        self._reported: set[UUID] = set()

    async def add(self, correlation_id: UUID, request: PendingRequest) -> bool:
        with self._lock:
            if correlation_id in self._by_id:
                return False
            self._by_id[correlation_id] = request
            return True

    async def get(self, correlation_id: UUID) -> PendingRequest | None:
        with self._lock:
            return self._by_id.get(correlation_id)

    async def renew(self, correlation_id: UUID, request: PendingRequest) -> None:
        with self._lock:
            if correlation_id not in self._by_id:
                return
            self._by_id[correlation_id] = request
            self._reported.discard(correlation_id)

    async def discard(self, correlation_id: UUID) -> None:
        with self._lock:
            self._by_id.pop(correlation_id, None)
            self._reported.discard(correlation_id)

    async def mark_reported(self, correlation_id: UUID) -> bool:
        with self._lock:
            if correlation_id not in self._by_id or correlation_id in self._reported:
                return False
            self._reported.add(correlation_id)
            return True

    async def all(self) -> list[tuple[UUID, PendingRequest]]:
        with self._lock:
            return list(self._by_id.items())

    def ids(self) -> set[UUID]:
        """For tests only, and not part of `PendingStore`: a synchronous look,
        for a fake producer that must see the store at the moment it publishes."""

        with self._lock:
            return set(self._by_id)


def _in_memory_fits(store: PendingRequests) -> PendingStore:
    """Checked by mypy, never called."""

    return store
