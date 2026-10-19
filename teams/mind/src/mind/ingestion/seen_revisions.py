"""Which Bridge items were already processed, and how a new arrival compares.

Each item (`correlation_id`) is processed once, at the first revision that
arrives. A later revision is Bridge updating the item; acting on it means
finding and updating the tasks the earlier revision produced, which is a
feature of its own, so it is skipped for now (#349).
"""

from enum import Enum
from typing import Protocol
from uuid import UUID


class Arrival(str, Enum):
    NEW = "NEW"
    DUPLICATE = "DUPLICATE"  # the revision already processed, sent again
    OUT_OF_ORDER = "OUT_OF_ORDER"  # older than the revision already processed
    UPDATE = "UPDATE"  # newer: Bridge changed the item; not supported yet

    @property
    def should_process(self) -> bool:
        return self is Arrival.NEW


def classify_arrival(processed: int | None, arriving: int) -> Arrival:
    """`processed` is the revision already processed for this item, if any."""

    if processed is None:
        return Arrival.NEW
    if arriving == processed:
        return Arrival.DUPLICATE
    if arriving < processed:
        return Arrival.OUT_OF_ORDER
    return Arrival.UPDATE


class RevisionStore(Protocol):
    """The revision actually processed per item, nothing else.

    A skipped arrival (duplicate, out of order, update) is never recorded, so
    every later arrival is still compared with the processed one: after 2 is
    processed, a 4 and then a 3 are both UPDATE. In memory today; a persistent
    store only needs these two methods.
    """

    def processed_revision(self, correlation_id: UUID) -> int | None: ...

    def mark_processed(self, correlation_id: UUID, revision: int) -> None: ...


class InMemoryRevisionStore:
    """Forgets everything on restart: Bridge's redelivered items are then processed again."""

    def __init__(self) -> None:
        self._processed: dict[UUID, int] = {}

    def processed_revision(self, correlation_id: UUID) -> int | None:
        return self._processed.get(correlation_id)

    def mark_processed(self, correlation_id: UUID, revision: int) -> None:
        self._processed[correlation_id] = revision
