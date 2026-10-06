"""#470 handler logic, tested against a fake in-memory VectorStore -- no
broker, no real Postgres. See mind.embeddings.index_consumer for the
handlers themselves and scripts/index_consumer.py for how they're wired to
real Kafka topics.
"""

import pytest

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.index_consumer import (
    handle_task_completed,
    handle_task_created,
    handle_task_updated,
)
from mind.messaging.task_completed_event_pb2 import TaskCompletedEvent
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent


class FakeVectorStore(VectorStore):
    """In-memory VectorStore double -- just enough of the real contract
    (task_id -> (vector, is_completed)) for the handler-logic tests above
    to exercise upsert/upsert_if_absent/mark_completed semantics."""

    def __init__(self) -> None:
        self.rows: dict[str, tuple[list[float], bool]] = {}

    async def open(self) -> None:
        pass

    async def close(self) -> None:
        pass

    async def upsert(self, task_id: str, vector: list[float]) -> None:
        is_completed = self.rows[task_id][1] if task_id in self.rows else False
        self.rows[task_id] = (vector, is_completed)

    async def upsert_if_absent(self, task_id: str, vector: list[float]) -> bool:
        if task_id in self.rows:
            return False
        self.rows[task_id] = (vector, False)
        return True

    async def mark_completed(self, task_id: str) -> bool:
        if task_id not in self.rows:
            return False
        vector, _ = self.rows[task_id]
        self.rows[task_id] = (vector, True)
        return True

    async def search(self, query_vector: list[float], k: int) -> list[SearchResult]:
        return [
            SearchResult(task_id=tid, similarity=1.0, is_completed=completed)
            for tid, (_vec, completed) in list(self.rows.items())[:k]
        ]

    async def delete(self, task_id: str) -> None:
        self.rows.pop(task_id, None)

    async def clear(self) -> None:
        self.rows.clear()


@pytest.fixture
def store():
    return FakeVectorStore()


async def test_handle_task_created_indexes_a_new_task(store):
    event = TaskCreatedEvent(id="task-1", title="Fix the login bug", description="On the payments page")
    await handle_task_created(store, event)
    assert "task-1" in store.rows


async def test_handle_task_created_does_not_overwrite_an_already_indexed_task(store):
    """A task already indexed via #353 must not be re-embedded/overwritten
    just because task.created was replayed."""
    event = TaskCreatedEvent(id="task-1", title="Fix the login bug", description="On the payments page")
    await store.upsert_if_absent("task-1", [0.0] * 256)
    original = store.rows["task-1"][0]

    await handle_task_created(store, event)

    assert store.rows["task-1"][0] == original


async def test_handle_task_created_skips_text_with_no_extractable_tokens(store):
    event = TaskCreatedEvent(id="task-1", title="", description="")
    await handle_task_created(store, event)  # should not raise
    assert "task-1" not in store.rows


async def test_handle_task_updated_creates_the_row_if_never_seen(store):
    """An update for a task_id this consumer never saw a task.created for
    self-heals by creating the row -- not an error."""
    event = TaskUpdatedEvent(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(store, event)
    assert "task-2" in store.rows


async def test_handle_task_updated_overwrites_the_existing_vector(store):
    await store.upsert("task-2", [0.0] * 256)
    event = TaskUpdatedEvent(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(store, event)
    assert store.rows["task-2"][0] != [0.0] * 256


async def test_handle_task_updated_skips_text_with_no_extractable_tokens(store):
    event = TaskUpdatedEvent(task_id="task-2", title="", description="")
    await handle_task_updated(store, event)  # should not raise
    assert "task-2" not in store.rows


async def test_handle_task_completed_marks_a_known_task(store):
    await store.upsert("task-3", [0.0] * 256)
    event = TaskCompletedEvent(task_id="task-3", is_completed=True)
    await handle_task_completed(store, event)
    assert store.rows["task-3"][1] is True


async def test_handle_task_completed_logs_and_skips_an_unknown_task(store):
    """No fetch-by-id API exists on Inbox's side, so an unknown task_id is
    log-and-skip, not a raised error."""
    event = TaskCompletedEvent(task_id="never-indexed", is_completed=True)
    await handle_task_completed(store, event)  # should not raise
    assert "never-indexed" not in store.rows
