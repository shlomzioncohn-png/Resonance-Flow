"""#470 handler logic, tested against a fake in-memory VectorStore --
no broker, no real Postgres."""

import pytest

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.index_consumer import (
    handle_task_completed,
    handle_task_created,
    handle_task_updated,
)
from mind.messaging.task_completed_event_pb2 import TaskCompletedEvent
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskStatus
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent


class FakeVectorStore(VectorStore):
    """In-memory VectorStore double: task_id -> (vector, is_completed, content_hash)."""

    def __init__(self) -> None:
        self.rows: dict[str, tuple[list[float], bool, str | None]] = {}

    async def open(self) -> None:
        pass

    async def close(self) -> None:
        pass

    async def upsert(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> None:
        is_completed = self.rows[task_id][1] if task_id in self.rows else False
        self.rows[task_id] = (vector, is_completed, content_hash)

    async def upsert_if_absent(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> bool:
        if task_id in self.rows:
            return False
        self.rows[task_id] = (vector, False, content_hash)
        return True

    async def mark_completed(self, task_id: str) -> bool:
        if task_id not in self.rows:
            return False
        vector, _, content_hash = self.rows[task_id]
        self.rows[task_id] = (vector, True, content_hash)
        return True

    async def mark_incomplete(self, task_id: str) -> bool:
        if task_id not in self.rows:
            return False
        vector, _, content_hash = self.rows[task_id]
        self.rows[task_id] = (vector, False, content_hash)
        return True

    async def get_content_hash(self, task_id: str) -> str | None:
        return self.rows[task_id][2] if task_id in self.rows else None

    async def search(self, query_vector: list[float], k: int) -> list[SearchResult]:
        return [
            SearchResult(task_id=tid, similarity=1.0, is_completed=completed)
            for tid, (_vec, completed, _hash) in list(self.rows.items())[:k]
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
    """A task already indexed via #353 must not be re-embedded on replay."""
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
    """An update for an unseen task_id self-heals by creating the row."""
    event = TaskUpdatedEvent(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(store, event)
    assert "task-2" in store.rows


async def test_handle_task_updated_overwrites_the_existing_vector(store):
    await store.upsert("task-2", [0.0] * 256)
    event = TaskUpdatedEvent(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(store, event)
    assert store.rows["task-2"][0] != [0.0] * 256


async def test_handle_task_updated_skips_reembedding_when_text_is_unchanged(store):
    """Chain's scheduling updates also publish task.updated without
    changing title/description -- re-embedding unchanged text is wasted
    work (and wrong for anything expecting a stable vector)."""
    event = TaskUpdatedEvent(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(store, event)
    first_vector = store.rows["task-2"][0]

    await handle_task_updated(store, event)  # identical text again
    assert store.rows["task-2"][0] is first_vector


async def test_handle_task_updated_skips_text_with_no_extractable_tokens(store):
    event = TaskUpdatedEvent(task_id="task-2", title="", description="")
    await handle_task_updated(store, event)  # should not raise
    assert "task-2" not in store.rows


async def test_handle_task_updated_marks_completed_when_status_is_done(store):
    """Inbox's real completion path (PATCH .../status) only ever publishes
    task.updated -- this is what actually has to carry completion."""
    event = TaskUpdatedEvent(
        task_id="task-2", title="Write release notes", description="For v2.3",
        status=TaskStatus.TASK_STATUS_DONE,
    )
    await handle_task_updated(store, event)
    assert store.rows["task-2"][1] is True


async def test_handle_task_updated_reopens_a_previously_done_task(store):
    """A DONE task moved back to an active status must reset is_completed,
    not stay stuck True forever."""
    done_event = TaskUpdatedEvent(
        task_id="task-2", title="Write release notes", description="For v2.3",
        status=TaskStatus.TASK_STATUS_DONE,
    )
    await handle_task_updated(store, done_event)
    assert store.rows["task-2"][1] is True

    reopened_event = TaskUpdatedEvent(
        task_id="task-2", title="Write release notes", description="For v2.3",
        status=TaskStatus.TASK_STATUS_IN_PROGRESS,
    )
    await handle_task_updated(store, reopened_event)
    assert store.rows["task-2"][1] is False


@pytest.mark.parametrize(
    "status", [TaskStatus.TASK_STATUS_REJECTED, TaskStatus.TASK_STATUS_CANCELLED]
)
async def test_handle_task_updated_marks_completed_for_rejected_or_cancelled(store, status):
    """Neither should keep surfacing as an active duplicate/dependency match."""
    event = TaskUpdatedEvent(
        task_id="task-2", title="Write release notes", description="For v2.3", status=status
    )
    await handle_task_updated(store, event)
    assert store.rows["task-2"][1] is True


async def test_handle_task_created_marks_completed_when_status_is_done(store):
    """A historical replay can create a task that's already finished."""
    event = TaskCreatedEvent(
        id="task-1", title="Fix the login bug", description="On the payments page",
        status=TaskStatus.TASK_STATUS_DONE,
    )
    await handle_task_created(store, event)
    assert store.rows["task-1"][1] is True


async def test_handle_task_completed_marks_a_known_task(store):
    await store.upsert("task-3", [0.0] * 256)
    event = TaskCompletedEvent(task_id="task-3", is_completed=True)
    await handle_task_completed(store, event)
    assert store.rows["task-3"][1] is True


async def test_handle_task_completed_reopens_on_false(store):
    """Honor the event's actual is_completed value -- don't assume True
    just because this topic fired."""
    await store.upsert("task-3", [0.0] * 256)
    await store.mark_completed("task-3")

    event = TaskCompletedEvent(task_id="task-3", is_completed=False)
    await handle_task_completed(store, event)
    assert store.rows["task-3"][1] is False


async def test_handle_task_completed_logs_and_skips_an_unknown_task(store):
    """Unknown task_id is log-and-skip, not a raised error."""
    event = TaskCompletedEvent(task_id="never-indexed", is_completed=True)
    await handle_task_completed(store, event)  # should not raise
    assert "never-indexed" not in store.rows
