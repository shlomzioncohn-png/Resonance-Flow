"""#355: DependencyAdapter, the on_matched glue between TaskCreationFlow and
DependencyPublisher, and its retry tracking for a target that isn't a task
yet. No broker: the sink is an in-memory list, and resolve_dependency_targets
(#245) is mocked, as in tests/test_dependencies_publish.py."""

from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest

from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.dependencies.adapter import _MAX_PENDING_AGE, DependencyAdapter
from mind.dependencies.publisher import DependencyPublisher
from mind.embeddings.base import SearchResult
from mind.task_creation.pending import PendingRequest
from mind.task_creation.reply import MatchedReply

TASK_A = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
TASK_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
TASK_C = "1e0f9a8b-7c6d-5e4f-4a3b-8c2d9a8b7c6d"
TENANT = UUID("7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a")


class ListSink:
    def __init__(self) -> None:
        self.sent: list[DependencyProposed] = []

    def send(self, payload: DependencyProposed) -> None:
        self.sent.append(payload)


@pytest.fixture
def sink() -> ListSink:
    return ListSink()


@pytest.fixture
def publisher(sink: ListSink) -> DependencyPublisher:
    return DependencyPublisher(store=object(), sink=sink)  # type: ignore[arg-type]


@pytest.fixture
def adapter(publisher: DependencyPublisher) -> DependencyAdapter:
    return DependencyAdapter(publisher)


def _resolved(*per_mention: list[SearchResult]):
    """Same convention as test_dependencies_publish.py's helper of the same name."""
    return patch(
        "mind.dependencies.publisher.resolve_dependency_targets",
        AsyncMock(return_value=list(per_mention)),
    )


def _matched(task_id: str, *, mentions: tuple[str, ...] = ()) -> MatchedReply:
    return MatchedReply(
        correlation_id=uuid4(),
        task_id=task_id,
        tenant_id=TENANT,
        request=PendingRequest(
            bridge_correlation_id=uuid4(),
            revision=0,
            task_index=0,
            dependency_mentions=mentions,
        ),
    )


async def test_a_fully_resolved_task_is_never_added_to_pending(adapter, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("the DB upgrade",)))

    [event] = sink.sent
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)
    assert TASK_A not in adapter._pending


async def test_an_unresolved_task_stays_pending_and_a_later_call_for_another_task_resolves_it(
    adapter, sink
):
    with _resolved([]):  # the dependency's own task does not exist yet
        await adapter.on_task_created(_matched(TASK_A, mentions=("a task that doesn't exist yet",)))

    assert sink.sent == []
    assert TASK_A in adapter._pending

    # TASK_C is created next, with no dependencies of its own -- but it
    # happens to be the target TASK_A was waiting for.
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await adapter.on_task_created(_matched(TASK_C))

    [event] = sink.sent
    assert event.task_id == UUID(TASK_A)
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)
    assert TASK_A not in adapter._pending


async def test_a_task_with_no_mentions_is_never_added_to_pending(adapter, sink):
    await adapter.on_task_created(_matched(TASK_A))

    assert sink.sent == []
    assert adapter._pending == {}


async def test_a_still_unresolved_retry_stays_pending(adapter, sink):
    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("a task that doesn't exist yet",)))

    with _resolved([]):  # still nothing for TASK_A's mention
        await adapter.on_task_created(_matched(TASK_C))

    assert sink.sent == []
    assert TASK_A in adapter._pending


# --- finding 1: an unrelated pending task's failure must not block the current reply ---


async def test_an_unrelated_pending_tasks_failure_does_not_block_the_current_reply(adapter, sink):
    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("waiting on A's target",)))
    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_B, mentions=("waiting on B's target",)))

    attempted: list[str] = []

    async def flaky(_store, _mentions, *, k, exclude_task_id):
        attempted.append(exclude_task_id)
        if exclude_task_id == TASK_A:
            raise ConnectionError("transient DB error")
        return [[]]

    with patch("mind.dependencies.publisher.resolve_dependency_targets", flaky):
        # TASK_C has no mentions of its own; this call exists only to trigger
        # the retry sweep over TASK_A and TASK_B.
        await adapter.on_task_created(_matched(TASK_C))

    assert TASK_B in attempted  # retried despite TASK_A's failure
    assert TASK_A in adapter._pending  # the failing retry changed nothing for it


# --- finding 2: _pending is capped and expires -------------------------------


async def test_pending_is_capped_and_a_new_entry_is_dropped_once_full(adapter, sink, monkeypatch):
    monkeypatch.setattr("mind.dependencies.adapter._MAX_PENDING", 2)

    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("a",)))
    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_B, mentions=("b",)))
    assert len(adapter._pending) == 2

    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_C, mentions=("c",)))

    assert TASK_C not in adapter._pending
    assert len(adapter._pending) == 2


async def test_a_pending_entry_expires_after_max_pending_age_and_is_never_retried(publisher, sink):
    now = datetime(2026, 1, 1, tzinfo=UTC)
    adapter = DependencyAdapter(publisher, clock=lambda: now)

    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("a task that doesn't exist yet",)))
    assert TASK_A in adapter._pending

    now = now + _MAX_PENDING_AGE + timedelta(seconds=1)
    adapter._clock = lambda: now

    mock_resolve = AsyncMock(return_value=[[]])
    with patch("mind.dependencies.publisher.resolve_dependency_targets", mock_resolve):
        await adapter.retry_pending()

    assert TASK_A not in adapter._pending
    mock_resolve.assert_not_awaited()


# --- finding 3: retries fire from the overdue timer too, not just on_matched ---


async def test_retry_pending_resolves_a_pending_task_without_any_matched_reply(adapter, sink):
    with _resolved([]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("a task that doesn't exist yet",)))
    assert TASK_A in adapter._pending

    # Called directly, as the overdue timer would -- not via on_task_created/on_matched.
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await adapter.retry_pending()

    [event] = sink.sent
    assert event.task_id == UUID(TASK_A)
    assert TASK_A not in adapter._pending


# --- finding 4: _pending is keyed by the normalized task id ------------------


async def test_publish_normalizes_the_task_id_so_case_does_not_create_two_entries(adapter, sink):
    canonical = str(uuid4())

    with _resolved([]):
        await adapter._publish(canonical.upper(), TENANT, ("a",))
    with _resolved([]):
        await adapter._publish(canonical, TENANT, ("a",))

    assert list(adapter._pending.keys()) == [canonical]
