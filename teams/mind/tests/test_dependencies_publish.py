"""#355: task.dependencies.proposed is published once per newly-resolved
dependency edge, after the real task_id is known; deduped for as long as
its sent-edges record survives the TTL/cap, and never published with
nothing to say. No broker: the sink is an in-memory list, and
resolve_dependency_targets (#245) is mocked -- its own behavior is
tests/test_dependency_resolution.py's job, not this file's."""

import asyncio
from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError

from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.dependencies.event import build_dependency_event
from mind.dependencies.publisher import (
    _EXPIRE_SWEEP_INTERVAL,
    _MAX_PUBLISHED_AGE,
    DependencyPublisher,
)
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
        call1 = asyncio.ensure_future(
            publisher.publish_dependencies(
                task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
            )
        )
        call2 = asyncio.ensure_future(
            publisher.publish_dependencies(
                task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
            )
        )
        await asyncio.gather(call1, call2)

    assert len(sink.sent) == 1
    # cleanup only happens once nothing is using the lock anymore
    assert TASK_A not in publisher._locks
    assert TASK_A not in publisher._lock_refs


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


async def test_publish_dependencies_returns_true_when_everything_resolves(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        resolved = await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert resolved is True


async def test_publish_dependencies_returns_false_when_a_mention_does_not_resolve(publisher, sink):
    with _resolved([]):
        resolved = await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["a task that doesn't exist yet"]
        )

    assert resolved is False


async def test_publish_dependencies_returns_false_when_only_some_mentions_resolve(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)], []):
        resolved = await publisher.publish_dependencies(
            task_id=TASK_A,
            tenant_id=TENANT,
            dependency_mentions=["the DB upgrade", "a task that doesn't exist yet"],
        )

    assert resolved is False


async def test_publish_dependencies_returns_true_for_an_empty_mentions_call(publisher, sink):
    with patch("mind.dependencies.publisher.resolve_dependency_targets") as mock_resolve:
        resolved = await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=[]
        )

    assert resolved is True
    mock_resolve.assert_not_called()


async def test_a_later_call_publishes_only_the_newly_resolved_edge(publisher, sink):
    """Two mentions: one resolves immediately, the other only on a later call.
    The later call must add the new edge without re-sending the first one."""
    mentions = ["the DB upgrade", "a task that doesn't exist yet"]

    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)], []):
        await publisher.publish_dependencies(task_id=TASK_A, tenant_id=TENANT, dependency_mentions=mentions)
    [first] = sink.sent
    assert [e.depends_on_task_id for e in first.dependencies] == [UUID(TASK_B)]

    with _resolved([SearchResult(task_id=TASK_C, similarity=0.8)]):
        await publisher.publish_dependencies(task_id=TASK_A, tenant_id=TENANT, dependency_mentions=mentions)

    assert len(sink.sent) == 2
    [second] = [e for e in sink.sent[1].dependencies]
    assert second.depends_on_task_id == UUID(TASK_C)


async def test_a_mention_already_sent_is_never_re_resolved_even_if_its_best_match_changes(publisher, sink):
    """#355 finding: once a mention has produced a sent edge, a retry with
    the same mention text must not re-resolve it, even if the underlying
    best match changed (e.g. a better task appeared, or the first one got
    completed) -- otherwise that retry would add a second edge for what is
    conceptually one dependency."""
    mentions = ["the DB upgrade"]

    with patch(
        "mind.dependencies.publisher.resolve_dependency_targets",
        AsyncMock(
            side_effect=[
                [[SearchResult(task_id=TASK_B, similarity=0.9)]],
                [[SearchResult(task_id=TASK_C, similarity=0.95)]],
            ]
        ),
    ) as mock_resolve:
        await publisher.publish_dependencies(task_id=TASK_A, tenant_id=TENANT, dependency_mentions=mentions)
        await publisher.publish_dependencies(task_id=TASK_A, tenant_id=TENANT, dependency_mentions=mentions)

    mock_resolve.assert_awaited_once()
    [event] = sink.sent
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)


async def test_resolve_is_called_with_k_one_and_exclude_task_id(publisher, sink):
    """The task's own row is excluded at the search level (#245's job, see
    test_dependency_resolution.py) -- this file only has to confirm the call
    asks for exactly one candidate per mention, excluding this task_id."""
    with patch(
        "mind.dependencies.publisher.resolve_dependency_targets",
        AsyncMock(return_value=[[SearchResult(task_id=TASK_B, similarity=0.9)]]),
    ) as mock_resolve:
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    mock_resolve.assert_awaited_once_with(
        publisher._store, ["the DB upgrade"], k=1, exclude_task_id=TASK_A
    )


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
    """A cancelled call whose send still reaches the sink must record the
    edge -- otherwise a later retry bundles it with newly-resolved mentions
    under a different event_id, and Chain's per-event_id dedup can't catch
    the resulting duplicate delivery."""
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
        # `task` only raises after its shielded wait for the real send outcome
        # completes, so the send is done and no thread is left running here.

    assert len(slow_sink.sent) == 1
    assert TASK_A in slow_publisher._published

    # a second call for the same edge must not duplicate it
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await slow_publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    assert len(slow_sink.sent) == 1


async def test_a_cancelled_call_whose_send_then_fails_logs_and_does_not_record(caplog):
    """The one extra shielded wait can also surface a genuine send failure
    (not a further cancellation) -- that must be logged, not swallowed, and
    must not record anything in `_published`."""
    import time

    class SlowFailingSink(ListSink):
        def send(self, payload):
            time.sleep(0.05)
            raise RuntimeError("broker down")

    slow_sink = SlowFailingSink()
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

    assert slow_sink.sent == []
    assert TASK_A not in slow_publisher._published
    assert "cancelled send failed" in caplog.text


async def test_a_second_cancellation_during_the_shielded_recovery_wait_propagates_promptly():
    """`shield` only protects `send_task` itself -- the await point that waits
    for its real outcome can be cancelled again. A second cancellation must
    not be absorbed into another wait (that could hang forever if the send
    never completes); it propagates immediately, unrecorded, bounded in time."""
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
        await asyncio.sleep(0.02)  # still in flight; cancel again mid-recovery wait
        task.cancel()
        async with asyncio.timeout(1):
            with pytest.raises(asyncio.CancelledError):
                await task

        # the second cancel propagated before the send (and its record) landed,
        # but the send itself keeps running in the background -- drain it so no
        # task/thread is left dangling once this test ends.
        async with asyncio.timeout(1):
            while not slow_sink.sent:
                await asyncio.sleep(0.01)

    assert slow_sink.sent  # the send still completed, unrecorded
    assert TASK_A not in slow_publisher._published


async def test_a_task_that_publishes_nothing_gets_no_published_entry(publisher, sink):
    with _resolved([]):  # the mention never resolves
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["a task that doesn't exist yet"]
        )

    assert sink.sent == []
    assert TASK_A not in publisher._published


async def test_a_task_that_publishes_nothing_leaves_no_lock_behind(publisher, sink):
    with _resolved([]):  # the mention never resolves
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["a task that doesn't exist yet"]
        )

    assert TASK_A not in publisher._locks
    assert TASK_A not in publisher._lock_refs


async def test_a_task_that_publishes_something_leaves_no_lock_behind(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert sink.sent  # sanity check: this call did publish
    assert TASK_A not in publisher._locks
    assert TASK_A not in publisher._lock_refs


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


async def test_a_redelivered_duplicate_after_a_restart_reuses_the_same_event_id(publisher, sink):
    """#355 finding 1: event_id must be derived from the data, not random, so
    Chain's event_id-based dedup survives a crash that wipes `_published`."""
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
        publisher._published.clear()  # simulate a restart losing the in-memory record
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert len(sink.sent) == 2
    assert sink.sent[1].event_id == sink.sent[0].event_id


async def test_different_edge_sets_for_the_same_task_get_different_event_ids(publisher, sink):
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    with _resolved([SearchResult(task_id=TASK_C, similarity=0.8)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["a different task"]
        )

    assert len(sink.sent) == 2
    assert sink.sent[0].event_id != sink.sent[1].event_id


async def test_blank_mentions_mixed_with_a_real_one_are_filtered_and_published(publisher, sink):
    """#355 finding 2: blank mentions must be dropped before resolution, not
    caught after -- otherwise one bad mention would sink the whole batch."""
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]) as mock_resolve:
        await publisher.publish_dependencies(
            task_id=TASK_A,
            tenant_id=TENANT,
            dependency_mentions=["", "   ", "the DB upgrade"],
        )

    mock_resolve.assert_awaited_once_with(
        publisher._store, ["the DB upgrade"], k=1, exclude_task_id=TASK_A
    )
    [event] = sink.sent
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)


async def test_all_blank_mentions_publish_nothing_and_skip_resolution(publisher, sink):
    with patch("mind.dependencies.publisher.resolve_dependency_targets") as mock_resolve:
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["", "  "]
        )

    assert sink.sent == []
    mock_resolve.assert_not_called()


async def test_the_same_task_in_different_case_is_recognized_as_already_sent(publisher, sink):
    """#355 finding 3: _published/_locks/_lock_refs must key off the
    normalized task_id -- a different-cased string for the same task is
    still the same task."""
    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
        await publisher.publish_dependencies(
            task_id=TASK_A.upper(), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert len(sink.sent) == 1


# --- #355 code review: _published is capped and expires ----------------------


async def test_a_published_entry_expires_after_max_published_age_and_is_re_sent(sink):
    now = 0.0
    publisher = DependencyPublisher(store=object(), sink=sink, clock=lambda: now)  # type: ignore[arg-type]

    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    assert TASK_A in publisher._published

    now = now + _MAX_PUBLISHED_AGE.total_seconds() + 1
    publisher._clock = lambda: now

    with _resolved([SearchResult(task_id=TASK_B, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert len(sink.sent) == 2  # expired, so re-sent instead of deduped


async def test_published_is_capped_and_evicts_the_oldest_entry_once_full(sink, monkeypatch):
    """#355 code review finding: a cap-full task must still get its edge
    recorded, evicting the oldest entry instead of silently dropping the
    new one -- a dropped record would resend the same edge on every retry."""
    monkeypatch.setattr("mind.dependencies.publisher._MAX_PUBLISHED", 2)
    now = 0.0
    publisher = DependencyPublisher(store=object(), sink=sink, clock=lambda: now)  # type: ignore[arg-type]
    target = str(uuid4())

    with _resolved([SearchResult(task_id=target, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    now = now + 1
    publisher._clock = lambda: now
    with _resolved([SearchResult(task_id=target, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_B, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    assert len(publisher._published) == 2

    now = now + 1
    publisher._clock = lambda: now
    with _resolved([SearchResult(task_id=target, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_C, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert TASK_A not in publisher._published  # oldest, evicted
    assert TASK_B in publisher._published
    assert TASK_C in publisher._published
    assert len(publisher._published) == 2


async def test_eviction_prefers_a_fully_resolved_entry_over_an_older_still_pending_one(
    sink, monkeypatch
):
    """#355 code review finding: a still-pending task's dedup record is the
    only one a future retry actually needs -- a fully-resolved task is never
    touched again, so eviction must sacrifice it first even when it is the
    newer entry by `last_sent_at`."""
    monkeypatch.setattr("mind.dependencies.publisher._MAX_PUBLISHED", 2)
    now = 0.0
    publisher = DependencyPublisher(store=object(), sink=sink, clock=lambda: now)  # type: ignore[arg-type]
    target = str(uuid4())

    with _resolved([SearchResult(task_id=target, similarity=0.9)], []):
        await publisher.publish_dependencies(
            task_id=TASK_B,
            tenant_id=TENANT,
            dependency_mentions=["the DB upgrade", "a task that doesn't exist yet"],
        )
    now = now + 1
    publisher._clock = lambda: now
    with _resolved([SearchResult(task_id=target, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    assert len(publisher._published) == 2

    now = now + 1
    publisher._clock = lambda: now
    with _resolved([SearchResult(task_id=target, similarity=0.9)]):
        await publisher.publish_dependencies(
            task_id=TASK_C, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert TASK_A not in publisher._published  # newer, but fully resolved: evicted first
    assert TASK_B in publisher._published  # older, but still pending: spared
    assert TASK_C in publisher._published
    assert len(publisher._published) == 2


async def test_a_still_pending_entry_is_evicted_once_no_fully_resolved_entries_remain(
    sink, monkeypatch
):
    """Only once every candidate is still-pending does eviction fall back to
    the globally-oldest entry -- there's no lower-cost, fully-resolved record
    left to sacrifice instead."""
    monkeypatch.setattr("mind.dependencies.publisher._MAX_PUBLISHED", 2)
    now = 0.0
    publisher = DependencyPublisher(store=object(), sink=sink, clock=lambda: now)  # type: ignore[arg-type]
    target = str(uuid4())

    with _resolved([SearchResult(task_id=target, similarity=0.9)], []):
        await publisher.publish_dependencies(
            task_id=TASK_A,
            tenant_id=TENANT,
            dependency_mentions=["the DB upgrade", "a task that doesn't exist yet"],
        )
    now = now + 1
    publisher._clock = lambda: now
    with _resolved([SearchResult(task_id=target, similarity=0.9)], []):
        await publisher.publish_dependencies(
            task_id=TASK_B,
            tenant_id=TENANT,
            dependency_mentions=["the DB upgrade", "a task that doesn't exist yet"],
        )
    assert len(publisher._published) == 2

    now = now + 1
    publisher._clock = lambda: now
    with _resolved([SearchResult(task_id=target, similarity=0.9)], []):
        await publisher.publish_dependencies(
            task_id=TASK_C,
            tenant_id=TENANT,
            dependency_mentions=["the DB upgrade", "a task that doesn't exist yet"],
        )

    assert TASK_A not in publisher._published  # oldest, and no resolved entry to spare instead
    assert TASK_B in publisher._published
    assert TASK_C in publisher._published
    assert len(publisher._published) == 2


async def test_the_expiry_sweep_is_throttled_and_runs_again_after_the_interval(sink):
    """The scan over `_published` is O(n); this bounds it to roughly once per
    `_EXPIRE_SWEEP_INTERVAL` instead of running on every call."""
    now = 0.0
    publisher = DependencyPublisher(store=object(), sink=sink, clock=lambda: now)  # type: ignore[arg-type]
    publisher._expire_stale_published()  # first call always runs, sets the baseline
    assert publisher._last_expired_at == now

    # a stale entry inserted directly, old enough to be expired once the sweep runs
    stale_at = now - _MAX_PUBLISHED_AGE.total_seconds() - 1
    publisher._published[TASK_A] = ({}, stale_at, True)

    now = now + _EXPIRE_SWEEP_INTERVAL.total_seconds() - 1
    publisher._clock = lambda: now
    publisher._expire_stale_published()
    assert TASK_A in publisher._published  # sweep skipped: still within the interval

    now = now + 1
    publisher._clock = lambda: now
    publisher._expire_stale_published()
    assert TASK_A not in publisher._published  # interval elapsed: sweep ran
