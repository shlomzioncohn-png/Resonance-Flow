"""#355: DependencyAdapter, the on_matched glue between TaskCreationFlow and
DependencyPublisher, and its retry tracking for a target that isn't a task
yet. No broker: the sink is an in-memory list, and resolve_dependency_targets
(#245) is mocked, as in tests/test_dependencies_publish.py."""

from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest

from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.dependencies.adapter import DependencyAdapter
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
