"""#355: task.dependencies.proposed is published once per newly-resolved
dependency edge, after the real task_id is known; never twice for the same
edge, and never at all with nothing to say. No broker: the sink is an
in-memory list, and resolve_dependency_targets (#245) is mocked -- its own
behavior is tests/test_dependency_resolution.py's job, not this file's."""

import asyncio
from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError

from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.dependencies.event import build_dependency_event
from mind.dependencies.publisher import DependencyPublisher
from mind.embeddings.base import SearchResult

TASK_A = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
TASK_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
TASK_C = "1e0f9a8b-7c6d-5e4f-4a3b-8c2d9a8b7c6d"
TENANT = UUID("7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a")


class ListSink:
    def __init__(self) -> None:
        self.sent: list[DependencyProposed] = []

    def send(self, payload: DependencyProposed) -> None:
        self.sent.append(payload)


class FailsOnceSink(ListSink):
    def __init__(self) -> None:
        super().__init__()
        self._failed = False

    def send(self, payload: DependencyProposed) -> None:
        if not self._failed:
            self._failed = True
            raise RuntimeError("broker down")
        super().send(payload)


@pytest.fixture
def sink() -> ListSink:
    return ListSink()


@pytest.fixture
def publisher(sink: ListSink) -> DependencyPublisher:
    # store is never touched directly in these tests -- resolve_dependency_targets
    # (the only thing that would use it) is mocked below.
    return DependencyPublisher(store=object(), sink=sink)  # type: ignore[arg-type]


def _resolved(*per_mention: list[SearchResult]):
    """Patches resolve_dependency_targets to return `per_mention` -- one
    SearchResult list per dependency_mentions entry, in order, as #245
    itself returns."""
    return patch(
        "mind.dependencies.publisher.resolve_dependency_targets",
        AsyncMock(return_value=list(per_mention)),
    )


async def test_a_task_with_one_resolved_dependency_publishes_one_edge(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    [event] = sink.sent
    assert event.task_id == UUID(TASK_A)
    assert event.tenant_id == TENANT
    assert len(event.dependencies) == 1
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)
    assert event.dependencies[0].confidence == 0.9


async def test_a_task_with_no_dependency_mentions_publishes_nothing(publisher, sink):
    with patch("mind.dependencies.publisher.resolve_dependency_targets") as mock_resolve:
        await publisher.publish_dependencies(task_id=TASK_A, tenant_id=TENANT, dependency_mentions=[])

    assert sink.sent == []
    mock_resolve.assert_not_called()


async def test_a_duplicate_task_created_reply_does_not_republish_the_same_edge(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert len(sink.sent) == 1


async def test_a_failed_send_does_not_mark_the_edge_as_already_sent():
    sink = FailsOnceSink()
    publisher = DependencyPublisher(store=object(), sink=sink)  # type: ignore[arg-type]

    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        with pytest.raises(RuntimeError, match="broker down"):
            await publisher.publish_dependencies(
                task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
            )
        assert sink.sent == []

        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    [event] = sink.sent
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)


async def test_two_concurrent_calls_for_the_same_task_publish_the_edge_once(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await asyncio.gather(
            publisher.publish_dependencies(
                task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
            ),
            publisher.publish_dependencies(
                task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
            ),
        )

    assert len(sink.sent) == 1


async def test_an_unresolved_target_is_not_dropped_and_resolves_on_a_later_call(publisher, sink):
    with _resolved([]):  # the dependency's own task does not exist yet
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["a task that doesn't exist yet"]
        )
    assert sink.sent == []

    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):  # now it does
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["a task that doesn't exist yet"]
        )

    [event] = sink.sent
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)


async def test_a_later_call_publishes_only_the_newly_resolved_edge(publisher, sink):
    """Two mentions: one resolves immediately, the other only on a later call.
    The later call must add the new edge without re-sending the first one."""
    mentions = ["the DB upgrade", "a task that doesn't exist yet"]

    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)], []):
        await publisher.publish_dependencies(task_id=TASK_A, tenant_id=TENANT, dependency_mentions=mentions)
    [first] = sink.sent
    assert [e.depends_on_task_id for e in first.dependencies] == [UUID(TASK_B)]

    with _resolved(
        [SearchResult(task_id=TASK_B, similarity=0.9)],
        [SearchResult(task_id=TASK_C, similarity=0.8)],
    ):
        await publisher.publish_dependencies(task_id=TASK_A, tenant_id=TENANT, dependency_mentions=mentions)

    assert len(sink.sent) == 2
    [second] = [e for e in sink.sent[1].dependencies]
    assert second.depends_on_task_id == UUID(TASK_C)


async def test_resolve_is_called_with_exclude_task_id(publisher, sink):
    """The task's own row should be excluded at the search level, not just
    filtered out afterward by the self-id check below."""
    with patch(
        "mind.dependencies.publisher.resolve_dependency_targets",
        AsyncMock(return_value=[[SearchResult(task_id=TASK_B, similarity=0.9)]]),
    ) as mock_resolve:
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    mock_resolve.assert_awaited_once_with(
        publisher._store, ["the DB upgrade"], exclude_task_id=TASK_A
    )


async def test_a_mention_resolving_to_the_tasks_own_id_is_not_a_self_dependency(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_A, similarity=0.95)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["something about this very task"]
        )

    assert sink.sent == []


async def test_two_mentions_resolving_to_the_same_target_publish_one_edge_at_the_higher_confidence(
    publisher, sink
):
    with _resolved(
        [SearchResult(task_id=TASK_B, similarity=0.7)],
        [SearchResult(task_id=TASK_B, similarity=0.95)],
    ):
        await publisher.publish_dependencies(
            task_id=TASK_A,
            tenant_id=TENANT,
            dependency_mentions=["vague reference", "the DB upgrade specifically"],
        )

    [event] = sink.sent
    assert len(event.dependencies) == 1
    assert event.dependencies[0].confidence == 0.95


async def test_a_non_uuid_resolved_target_is_skipped_not_fatal(publisher, sink):
    with _resolved([SearchResult(task_id="not-a-uuid", similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["something"]
        )

    assert sink.sent == []


def test_dependency_proposed_rejects_an_empty_dependencies_list():
    with pytest.raises(ValidationError):
        DependencyProposed(event_id=uuid4(), task_id=UUID(TASK_A), tenant_id=TENANT, dependencies=[])


def test_build_dependency_event_raises_on_no_resolved_edges():
    with pytest.raises(ValidationError):
        build_dependency_event(UUID(TASK_A), TENANT, {})


async def test_a_mention_with_two_candidates_above_threshold_publishes_only_the_best(publisher, sink):
    """#355 finding 1: 'login bug' vs 'logout bug' can both clear the
    threshold for one mention -- only the best (first) candidate is an edge."""
    with _resolved(
        [
            SearchResult(task_id=TASK_B, similarity=0.82),
            SearchResult(task_id=TASK_C, similarity=0.71),
        ]
    ):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["fix the login bug"]
        )

    [event] = sink.sent
    assert len(event.dependencies) == 1
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)


async def test_a_cancelled_call_does_not_lose_track_of_a_completed_send():
    import time

    class SlowSink(ListSink):
        def send(self, payload):
            time.sleep(0.05)
            super().send(payload)

    slow_sink = SlowSink()
    slow_publisher = DependencyPublisher(store=object(), sink=slow_sink)  # type: ignore[arg-type]

    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        task = asyncio.ensure_future(
            slow_publisher.publish_dependencies(
                task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
            )
        )
        await asyncio.sleep(0.01)  # let it reach the send, still in flight
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.sleep(0.1)  # let the background send actually finish

    assert len(slow_sink.sent) == 1

    # a second call for the same edge must not duplicate it
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await slow_publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    assert len(slow_sink.sent) == 1


async def test_a_task_that_publishes_nothing_gets_no_published_entry(publisher, sink):
    with _resolved([]):  # the mention never resolves
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["a task that doesn't exist yet"]
        )

    assert sink.sent == []
    assert TASK_A not in publisher._published


async def test_the_same_target_in_different_case_is_deduped_across_mentions(publisher, sink):
    """#355 finding 8: dedupe is by UUID value, not raw string -- differently
    cased representations of the same id are the same edge."""
    with _resolved(
        [SearchResult(task_id=TASK_B, similarity=0.7)],
        [SearchResult(task_id=TASK_B.upper(), similarity=0.95)],
    ):
        await publisher.publish_dependencies(
            task_id=TASK_A,
            tenant_id=TENANT,
            dependency_mentions=["vague reference", "the DB upgrade specifically"],
        )

    [event] = sink.sent
    assert len(event.dependencies) == 1
    assert event.dependencies[0].confidence == 0.95
