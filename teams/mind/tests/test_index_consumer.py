"""#470 handler logic, tested against a fake in-memory VectorStore --
no broker, no real Postgres."""

from typing import Any
from uuid import UUID

import pytest

from mind.embeddings.base import SearchResult, VectorIndex, VectorStore
from mind.embeddings.embeddings import task_text
from mind.embeddings.index_consumer import (
    _content_hash,
    handle_task_completed,
    handle_task_created,
    handle_task_updated,
)
from mind.messaging.task_completed_event_pb2 import TaskCompletedEvent
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskStatus
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent

TENANT = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

# task_id -> (vector, is_completed, content_hash, tenant_id)
_Rows = dict[str, tuple[list[float], bool, str | None, UUID | None]]


class FakeVectorIndex(VectorIndex):
    """In-memory double with PgVectorStore's tenant rules: a scoped store
    only sees its own rows, and upsert claims a row that has no tenant."""

    def __init__(self) -> None:
        self.rows: _Rows = {}

    def for_tenant(self, tenant_id: UUID) -> "FakeTenantStore":
        return FakeTenantStore(self, tenant_id)

    async def tenant_of(self, task_id: str) -> UUID | None:
        return self.rows[task_id][3] if task_id in self.rows else None


class FakeTenantStore(VectorStore):
    def __init__(self, index: FakeVectorIndex, tenant_id: UUID) -> None:
        self.index = index
        self.rows = index.rows
        self.tenant_id = tenant_id

    def _owns(self, task_id: str) -> bool:
        return task_id in self.rows and self.rows[task_id][3] == self.tenant_id

    def _set_completed(self, task_id: str, is_completed: bool) -> bool:
        if not self._owns(task_id):
            return False
        vector, _, content_hash, tenant_id = self.rows[task_id]
        self.rows[task_id] = (vector, is_completed, content_hash, tenant_id)
        return True

    async def upsert(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> None:
        if task_id in self.rows and self.rows[task_id][3] not in (None, self.tenant_id):
            return
        is_completed = self.rows[task_id][1] if task_id in self.rows else False
        self.rows[task_id] = (vector, is_completed, content_hash, self.tenant_id)

    async def upsert_if_absent(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> bool:
        if task_id in self.rows:
            return False
        self.rows[task_id] = (vector, False, content_hash, self.tenant_id)
        return True

    async def mark_completed(self, task_id: str) -> bool:
        return self._set_completed(task_id, True)

    async def mark_incomplete(self, task_id: str) -> bool:
        return self._set_completed(task_id, False)

    async def get_content_hash(self, task_id: str) -> str | None:
        return self.rows[task_id][2] if self._owns(task_id) else None

    async def get_vector(self, task_id: str) -> list[float] | None:
        return self.rows[task_id][0] if self._owns(task_id) else None

    async def search(
        self,
        query_vector: list[float],
        k: int,
        *,
        exclude_completed: bool = False,
        exclude_task_id: str | None = None,
    ) -> list[SearchResult]:
        rows = [
            (tid, completed)
            for tid, (_vec, completed, _hash, _tenant) in self.rows.items()
            if self._owns(tid)
            and (not exclude_completed or not completed)
            and tid != exclude_task_id
        ]
        return [
            SearchResult(task_id=tid, similarity=1.0, is_completed=completed)
            for tid, completed in rows[:k]
        ]

    async def delete(self, task_id: str) -> None:
        if self._owns(task_id):
            del self.rows[task_id]


def _created(**fields: Any) -> TaskCreatedEvent:
    return TaskCreatedEvent(tenant_id=str(TENANT), **fields)


def _updated(**fields: Any) -> TaskUpdatedEvent:
    return TaskUpdatedEvent(tenant_id=str(TENANT), **fields)


@pytest.fixture
def index():
    return FakeVectorIndex()


@pytest.fixture
def store(index):
    return index.for_tenant(TENANT)


async def test_handle_task_created_indexes_a_new_task(index, store):
    event = _created(id="task-1", title="Fix the login bug", description="On the payments page")
    await handle_task_created(index, event)
    assert "task-1" in store.rows


async def test_handle_task_created_does_not_overwrite_an_already_indexed_task(index, store):
    """A task already indexed via #353 must not be re-embedded on replay."""
    event = _created(id="task-1", title="Fix the login bug", description="On the payments page")
    await store.upsert_if_absent("task-1", [0.0] * 256)
    original = store.rows["task-1"][0]

    await handle_task_created(index, event)

    assert store.rows["task-1"][0] == original


async def test_handle_task_created_skips_text_with_no_extractable_tokens(index, store):
    event = _created(id="task-1", title="", description="")
    await handle_task_created(index, event)  # should not raise
    assert "task-1" not in store.rows


async def test_handle_task_updated_creates_the_row_if_never_seen(index, store):
    """An update for an unseen task_id self-heals by creating the row."""
    event = _updated(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(index, event)
    assert "task-2" in store.rows


# --- #918: on_indexed -------------------------------------------------------


async def test_on_indexed_fires_for_a_newly_inserted_task(index, store):
    calls = []

    async def record(task_id, tenant_id, event):
        calls.append(task_id)

    event = _created(id="task-1", title="Fix the login bug", description="On the payments page")
    await handle_task_created(index, event, [record])

    assert calls == ["task-1"]


async def test_on_indexed_fires_even_when_already_indexed(index, store):
    """#918: a retry must find an already-indexed target too, not just a
    task indexed for the first time by this exact call."""
    await store.upsert_if_absent("task-1", [0.0] * 256)
    calls = []

    async def record(task_id, tenant_id, event):
        calls.append(task_id)

    event = _created(id="task-1", title="Fix the login bug", description="On the payments page")
    await handle_task_created(index, event, [record])

    assert calls == ["task-1"]


async def test_on_indexed_does_not_fire_when_nothing_was_indexed(index, store):
    calls = []

    async def record(task_id, tenant_id, event):
        calls.append(task_id)

    event = _created(id="task-1", title="", description="")
    await handle_task_created(index, event, [record])

    assert calls == []


async def test_a_failing_on_indexed_callback_does_not_stop_the_others(index, store, caplog):
    calls = []

    async def failing(task_id, tenant_id, event):
        raise RuntimeError("boom")

    async def record(task_id, tenant_id, event):
        calls.append(task_id)

    event = _created(id="task-1", title="Fix the login bug", description="On the payments page")
    with caplog.at_level("ERROR"):
        await handle_task_created(index, event, [failing, record])

    assert calls == ["task-1"]
    assert "on_indexed callback failed" in caplog.text


async def test_handle_task_updated_overwrites_the_existing_vector(index, store):
    await store.upsert("task-2", [0.0] * 256)
    event = _updated(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(index, event)
    assert store.rows["task-2"][0] != [0.0] * 256


async def test_handle_task_updated_skips_reembedding_when_text_is_unchanged(index, store):
    """Unchanged text (e.g. a Chain scheduling update) shouldn't re-embed."""
    event = _updated(task_id="task-2", title="Write release notes", description="For v2.3")
    await handle_task_updated(index, event)
    first_vector = store.rows["task-2"][0]

    await handle_task_updated(index, event)  # identical text again
    assert store.rows["task-2"][0] is first_vector


async def test_handle_task_updated_skips_text_with_no_extractable_tokens(index, store):
    event = _updated(task_id="task-2", title="", description="")
    await handle_task_updated(index, event)  # should not raise
    assert "task-2" not in store.rows


async def test_handle_task_updated_marks_completed_when_status_is_done(index, store):
    """Inbox's real completion path (PATCH .../status) only ever publishes
    task.updated -- this is what actually has to carry completion."""
    event = _updated(
        task_id="task-2", title="Write release notes", description="For v2.3",
        status=TaskStatus.TASK_STATUS_DONE,
    )
    await handle_task_updated(index, event)
    assert store.rows["task-2"][1] is True


async def test_handle_task_updated_reopens_a_previously_done_task(index, store):
    """DONE -> active status must reset is_completed, not stay stuck True."""
    done_event = _updated(
        task_id="task-2", title="Write release notes", description="For v2.3",
        status=TaskStatus.TASK_STATUS_DONE,
    )
    await handle_task_updated(index, done_event)
    assert store.rows["task-2"][1] is True

    reopened_event = _updated(
        task_id="task-2", title="Write release notes", description="For v2.3",
        status=TaskStatus.TASK_STATUS_IN_PROGRESS,
    )
    await handle_task_updated(index, reopened_event)
    assert store.rows["task-2"][1] is False


@pytest.mark.parametrize(
    "status", [TaskStatus.TASK_STATUS_REJECTED, TaskStatus.TASK_STATUS_CANCELLED]
)
async def test_handle_task_updated_marks_completed_for_rejected_or_cancelled(index, store, status):
    """Neither should keep surfacing as an active duplicate/dependency match."""
    event = _updated(
        task_id="task-2", title="Write release notes", description="For v2.3", status=status
    )
    await handle_task_updated(index, event)
    assert store.rows["task-2"][1] is True


async def test_handle_task_created_marks_completed_when_status_is_done(index, store):
    """A historical replay can create a task that's already finished."""
    event = _created(
        id="task-1", title="Fix the login bug", description="On the payments page",
        status=TaskStatus.TASK_STATUS_DONE,
    )
    await handle_task_created(index, event)
    assert store.rows["task-1"][1] is True


async def test_handle_task_completed_marks_a_known_task(index, store):
    await store.upsert("task-3", [0.0] * 256)
    event = TaskCompletedEvent(task_id="task-3", is_completed=True)
    await handle_task_completed(index, event)
    assert store.rows["task-3"][1] is True


async def test_handle_task_completed_reopens_on_false(index, store):
    """Honor the event's actual is_completed value -- don't assume True
    just because this topic fired."""
    await store.upsert("task-3", [0.0] * 256)
    await store.mark_completed("task-3")

    event = TaskCompletedEvent(task_id="task-3", is_completed=False)
    await handle_task_completed(index, event)
    assert store.rows["task-3"][1] is False


async def test_handle_task_completed_logs_and_skips_an_unknown_task(index, store):
    """Unknown task_id is log-and-skip, not a raised error."""
    event = TaskCompletedEvent(task_id="never-indexed", is_completed=True)
    await handle_task_completed(index, event)  # should not raise
    assert "never-indexed" not in store.rows


async def test_handle_task_created_skips_a_tenant_id_that_is_not_a_uuid(index):
    event = TaskCreatedEvent(id="task-1", tenant_id="kamatech", title="Fix the login bug")
    await handle_task_created(index, event)  # should not raise
    assert "task-1" not in index.rows


async def test_handle_task_updated_skips_a_tenant_id_that_is_not_a_uuid(index):
    event = TaskUpdatedEvent(task_id="task-2", tenant_id="", title="Write release notes")
    await handle_task_updated(index, event)  # should not raise
    assert "task-2" not in index.rows


async def test_handle_task_created_writes_the_tenant(index):
    await handle_task_created(index, _created(id="task-1", title="Fix the login bug"))
    assert await index.tenant_of("task-1") == TENANT


async def test_handle_task_updated_sets_the_tenant_on_a_row_indexed_before_it_even_if_unchanged(
    index,
):
    """#841: a row from before tenant_id existed has the same text hash but
    no tenant. The next task.updated must still give it one."""
    text = task_text("Write release notes", "For v2.3")
    index.rows["task-2"] = ([0.0] * 256, False, _content_hash(text), None)

    await handle_task_updated(
        index, _updated(task_id="task-2", title="Write release notes", description="For v2.3")
    )

    assert await index.tenant_of("task-2") == TENANT


async def test_handle_task_completed_uses_the_tenant_of_the_indexed_row(index):
    """task.completed has no tenant_id: the row's own tenant is used."""
    other = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    await index.for_tenant(other).upsert("task-3", [0.0] * 256)

    await handle_task_completed(index, TaskCompletedEvent(task_id="task-3", is_completed=True))

    assert index.rows["task-3"][1] is True
