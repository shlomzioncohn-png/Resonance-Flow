"""The creation requests sent to Inbox that are still waiting for task.created.

`PendingStore` is what the sender, the reply handler and the overdue check rely
on; `PendingRequests` is its in-memory implementation. The interface is async
even though memory never waits, because a database call must not block the
loop it is awaited on.

What it does not settle: the store is used from three threads, each on its own
event loop — the #349 pipeline, the task.created listener, the overdue timer —
and an async Postgres pool belongs to the loop that opened it. A Postgres store
therefore has to work from several loops (a pool per loop, opened on first use
in each; or a connection per call), or the three sides have to share one loop.
That choice belongs to whoever builds it; nothing here makes it for them.

The in-memory one has two limits, and both are the reason for a database one:

- the sender (the #349 pipeline) and the task.created listener must run in the
  same process, or the listener sees an empty store and drops every reply as
  unknown;
- a restart forgets it. R3's index consumer still indexes every task.created on
  its own, but a reply that arrives after a restart is not matched.
"""

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol
from uuid import UUID


@dataclass(frozen=True)
class PendingRequest:
    """What Mind needs to recognise the reply, and to update the task later."""

    bridge_correlation_id: UUID
    # Kept for a later update flow: a higher revision of the same item means
    # its content changed, and the task it made should be updated, not remade.
    revision: int
    task_index: int
    sent_at: datetime = field(default_factory=lambda: datetime.now(UTC))


class PendingStore(Protocol):
    """Where requests wait for their reply, keyed by correlation_id."""

    async def add(self, correlation_id: UUID, request: PendingRequest) -> bool:
        """Register a request; False when it was registered already (a retry)."""
        ...

    async def get(self, correlation_id: UUID) -> PendingRequest | None: ...

    async def renew(self, correlation_id: UUID, request: PendingRequest) -> None:
        """Replace an entry after a retry went out: its clock restarts, its
        revision is the one Inbox now holds, and it may be reported again.

        Only an entry still pending. The reply to the retry can be matched —
        and the entry forgotten — before the sender gets here; renewing then
        must not bring it back, or it waits for ever and is reported overdue
        for a task that exists.
        """
        ...

    async def discard(self, correlation_id: UUID) -> None: ...

    async def mark_reported(self, correlation_id: UUID) -> bool:
        """True the first time a still-pending request is reported overdue.

        False after that, and False for one no longer pending: the timer reads
        its snapshot first, and a reply matched in between must not earn a
        warning. Check and mark are one step — in SQL, one UPDATE ... WHERE
        reported_at IS NULL — so nothing can be answered between them.
        """
        ...

    async def all(self) -> list[tuple[UUID, PendingRequest]]: ...


class PendingRequests:
    """`PendingStore` in memory. See the module docstring for its two limits."""

    def __init__(self) -> None:
        self._by_id: dict[UUID, PendingRequest] = {}
        # Overdue requests already reported, so each is reported once.
        self._reported: set[UUID] = set()

    async def add(self, correlation_id: UUID, request: PendingRequest) -> bool:
        if correlation_id in self._by_id:
            return False
        self._by_id[correlation_id] = request
        return True

    async def get(self, correlation_id: UUID) -> PendingRequest | None:
        return self._by_id.get(correlation_id)

    async def renew(self, correlation_id: UUID, request: PendingRequest) -> None:
        if correlation_id not in self._by_id:
            return
        self._by_id[correlation_id] = request
        self._reported.discard(correlation_id)

    async def discard(self, correlation_id: UUID) -> None:
        self._by_id.pop(correlation_id, None)
        self._reported.discard(correlation_id)

    async def mark_reported(self, correlation_id: UUID) -> bool:
        # No await between the check and the mark, so nothing else runs between them.
        if correlation_id not in self._by_id or correlation_id in self._reported:
            return False
        self._reported.add(correlation_id)
        return True

    async def all(self) -> list[tuple[UUID, PendingRequest]]:
        return list(self._by_id.items())

    def ids(self) -> set[UUID]:
        """For tests only, and not part of `PendingStore`: a synchronous look,
        for a fake producer that must see the store at the moment it publishes."""

        return set(self._by_id)


def _in_memory_fits(store: PendingRequests) -> PendingStore:
    """Checked by mypy, never called."""

    return store
