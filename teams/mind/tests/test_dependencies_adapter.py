"""#355: DependencyAdapter, the on_matched glue between TaskCreationFlow and
DependencyPublisher. One shot, no retry -- see DependencyPublisher's module
docstring for why. No broker: the sink is an in-memory list, and
resolve_dependency_targets (#245) is mocked, as in
tests/test_dependencies_publish.py."""

from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest

from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.dependencies.adapter import DependencyAdapter
from mind.dependencies.publisher import DependencyPublisher
from mind.embeddings.confidence import ScoredResult
from mind.task_creation.pending import PendingRequest
from mind.task_creation.reply import MatchedReply

TASK_A = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
TASK_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
TENANT = UUID("7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a")


class ListSink:
    def __init__(self) -> None:
        self.sent: list[DependencyProposed] = []

    def send(self, payload: DependencyProposed) -> None:
        self.sent.append(payload)


@pytest.fixture
def sink() -> ListSink:
    return ListSink()


class _FakeIndex:
    """for_tenant() returns itself -- the scoped store is never touched
    directly in these tests, resolve_dependency_targets (the only thing
    that would use it) is mocked below."""

    def for_tenant(self, tenant_id: UUID) -> "_FakeIndex":
        return self


@pytest.fixture
def publisher(sink: ListSink) -> DependencyPublisher:
    return DependencyPublisher(index=_FakeIndex(), sink=sink)  # type: ignore[arg-type]


@pytest.fixture
def adapter(publisher: DependencyPublisher) -> DependencyAdapter:
    return DependencyAdapter(publisher)


def _resolved(*per_mention: list[ScoredResult]):
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
            tenant_id=TENANT,
            dependency_mentions=mentions,
        ),
    )


async def test_on_task_created_publishes_a_resolved_mention(adapter, sink):
    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("the DB upgrade",)))

    [event] = sink.sent
    assert str(event.task_id) == TASK_A
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)


async def test_on_task_created_with_no_mentions_does_nothing(adapter, sink):
    await adapter.on_task_created(_matched(TASK_A))

    assert sink.sent == []


async def test_on_task_created_with_an_unresolved_mention_logs_and_does_not_raise(
    adapter, sink, caplog: pytest.LogCaptureFixture
):
    with _resolved([]), caplog.at_level("WARNING"):
        await adapter.on_task_created(_matched(TASK_A, mentions=("doesn't exist yet",)))

    assert sink.sent == []
    assert "did not resolve" in caplog.text


async def test_on_task_created_with_an_unparseable_task_id_logs_and_returns(
    adapter, sink, caplog: pytest.LogCaptureFixture
):
    with caplog.at_level("WARNING"):
        await adapter.on_task_created(_matched("not-a-uuid", mentions=("the DB upgrade",)))

    assert sink.sent == []
    assert "not-a-uuid" in caplog.text


async def test_on_task_created_passes_the_matched_tenant_id_through(adapter, sink):
    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]):
        await adapter.on_task_created(_matched(TASK_A, mentions=("the DB upgrade",)))

    [event] = sink.sent
    assert event.tenant_id == TENANT
