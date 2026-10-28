"""#355: task.dependencies.proposed, published once per resolved mention,
the moment the real task_id is known. No retry here -- a mention that
doesn't resolve is logged, not tracked. event_id is deterministic
(task_id:target), so a redelivered task.created republishes the exact same
event for an already-sent edge, which Chain dedups on event_id; this file
only has to prove that determinism, not re-implement Chain's dedup. No
broker: the sink is an in-memory list, and resolve_dependency_targets
(#245) is mocked -- its own behavior is tests/test_dependency_resolution.py's
job, not this file's."""

from unittest.mock import AsyncMock, patch
from uuid import UUID

import pytest

from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.dependencies.publisher import DependencyPublisher
from mind.embeddings.confidence import ScoredResult

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


class _FakeIndex:
    """for_tenant() returns itself -- the scoped store is never touched
    directly in these tests, resolve_dependency_targets (the only thing
    that would use it) is mocked below."""

    def for_tenant(self, tenant_id: UUID) -> "_FakeIndex":
        return self


@pytest.fixture
def publisher(sink: ListSink) -> DependencyPublisher:
    return DependencyPublisher(index=_FakeIndex(), sink=sink)  # type: ignore[arg-type]


def _resolved(*per_mention: list[ScoredResult]):
    return patch(
        "mind.dependencies.publisher.resolve_dependency_targets",
        AsyncMock(return_value=list(per_mention)),
    )


async def test_a_resolved_mention_is_published_as_one_edge(publisher, sink) -> None:
    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    [event] = sink.sent
    assert str(event.task_id) == TASK_A
    assert event.tenant_id == TENANT
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)
    # #476 review item 1: Chain's PriorityScoreService is calibrated against
    # raw similarity -- published value is .similarity, not .confidence.
    assert event.dependencies[0].confidence == pytest.approx(0.9)


async def test_a_published_edge_is_logged_with_its_ids_and_not_its_mention(
    publisher, sink, caplog: pytest.LogCaptureFixture
) -> None:
    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]), caplog.at_level("INFO"):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert (
        f"dependencies: task {TASK_A} depends on task {TASK_B} (confidence 0.900), published"
        in caplog.text
    )
    assert "the DB upgrade" not in caplog.text


async def test_nothing_is_logged_as_published_when_the_send_fails(
    publisher, sink, caplog: pytest.LogCaptureFixture
) -> None:
    def fail(payload: DependencyProposed) -> None:
        raise RuntimeError("Kafka down")

    sink.send = fail
    with (
        _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]),
        caplog.at_level("INFO"),
        pytest.raises(RuntimeError),
    ):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert "published" not in caplog.text


async def test_an_unresolved_mention_is_logged_not_raised(
    publisher, sink, caplog: pytest.LogCaptureFixture
) -> None:
    with _resolved([]), caplog.at_level("WARNING"):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A),
            tenant_id=TENANT,
            dependency_mentions=["a task that doesn't exist yet"],
        )

    assert sink.sent == []
    assert "did not resolve" in caplog.text


async def test_one_unresolved_mention_does_not_block_another_resolved_one(publisher, sink) -> None:
    with _resolved([], [ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A),
            tenant_id=TENANT,
            dependency_mentions=["doesn't exist yet", "the DB upgrade"],
        )

    [event] = sink.sent
    assert event.dependencies[0].depends_on_task_id == UUID(TASK_B)


async def test_each_resolved_mention_becomes_its_own_event(publisher, sink) -> None:
    with _resolved(
        [ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)],
        [ScoredResult(task_id=TASK_C, similarity=0.8, confidence=0.3)],
    ):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A),
            tenant_id=TENANT,
            dependency_mentions=["mention one", "mention two"],
        )

    assert len(sink.sent) == 2
    targets = {e.dependencies[0].depends_on_task_id for e in sink.sent}
    assert targets == {UUID(TASK_B), UUID(TASK_C)}


async def test_two_mentions_resolving_to_the_same_target_publish_one_edge_at_the_higher_similarity(
    publisher, sink
) -> None:
    """Both would compute the same event_id -- this dedups by target first
    and keeps the higher similarity (#476 review item 1: the published
    value is .similarity, for Chain's calibration, not .confidence).
    Confidence is set to the opposite order on purpose, so a publisher
    that dedups on `.confidence` instead would be caught here."""

    with _resolved(
        [ScoredResult(task_id=TASK_B, similarity=0.95, confidence=0.3)],
        [ScoredResult(task_id=TASK_B, similarity=0.70, confidence=0.6)],
    ):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A),
            tenant_id=TENANT,
            dependency_mentions=["mention one", "mention two"],
        )

    [event] = sink.sent
    assert event.dependencies[0].confidence == pytest.approx(0.95)


async def test_the_same_target_in_different_case_is_deduped_across_mentions(
    publisher, sink
) -> None:
    with _resolved(
        [ScoredResult(task_id=TASK_B.upper(), similarity=0.95, confidence=0.3)],
        [ScoredResult(task_id=TASK_B, similarity=0.70, confidence=0.6)],
    ):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A),
            tenant_id=TENANT,
            dependency_mentions=["mention one", "mention two"],
        )

    assert len(sink.sent) == 1
    assert sink.sent[0].dependencies[0].confidence == pytest.approx(0.95)


async def test_all_blank_mentions_publish_nothing_and_skip_resolution(
    publisher, sink, caplog: pytest.LogCaptureFixture
) -> None:
    with _resolved() as mock_resolve, caplog.at_level("WARNING"):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["  ", "\t"]
        )

    assert sink.sent == []
    assert "blank mention" in caplog.text
    mock_resolve.assert_not_awaited()


async def test_the_event_id_is_deterministic_so_a_redelivery_resends_the_same_edge(
    publisher, sink
) -> None:
    """Chain dedups on event_id -- a redelivered task.created must recompute
    the exact same id for the same edge, not a fresh one."""

    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )
    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert len(sink.sent) == 2
    assert sink.sent[0].event_id == sink.sent[1].event_id


async def test_a_different_target_gets_a_different_event_id(publisher, sink) -> None:
    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["mention one"]
        )
    with _resolved([ScoredResult(task_id=TASK_C, similarity=0.9, confidence=0.2)]):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["mention two"]
        )

    assert sink.sent[0].event_id != sink.sent[1].event_id


async def test_a_blank_mention_is_dropped_with_a_warning(
    publisher, sink, caplog: pytest.LogCaptureFixture
) -> None:
    with _resolved([ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)]), caplog.at_level("WARNING"):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["  ", "the DB upgrade"]
        )

    assert len(sink.sent) == 1
    assert "blank mention" in caplog.text


async def test_no_mentions_is_a_no_op(publisher, sink) -> None:
    await publisher.publish_dependencies(
        task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=[]
    )

    assert sink.sent == []


async def test_a_resolved_target_that_is_not_a_uuid_is_skipped_with_a_warning(
    publisher, sink, caplog: pytest.LogCaptureFixture
) -> None:
    with (
        _resolved([ScoredResult(task_id="not-a-uuid", similarity=0.9, confidence=0.2)]),
        caplog.at_level("WARNING"),
    ):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert sink.sent == []
    assert "not-a-uuid" in caplog.text


async def test_a_resolved_target_matching_the_task_itself_is_skipped_even_in_different_case(
    publisher, sink, caplog: pytest.LogCaptureFixture
) -> None:
    """exclude_task_id is a case-sensitive SQL compare against the lowercase
    normalized id -- a self-match stored in different case would slip past
    it and get published as "A depends on A", which Chain rejects as a cycle."""
    with (
        _resolved([ScoredResult(task_id=TASK_A.upper(), similarity=0.9, confidence=0.2)]),
        caplog.at_level("WARNING"),
    ):
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A), tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
        )

    assert sink.sent == []
    assert "resolved itself" in caplog.text


async def test_resolve_is_called_once_for_every_mention_together(publisher, sink) -> None:
    with _resolved(
        [ScoredResult(task_id=TASK_B, similarity=0.9, confidence=0.2)],
        [ScoredResult(task_id=TASK_C, similarity=0.8, confidence=0.3)],
    ) as mock_resolve:
        await publisher.publish_dependencies(
            task_id=UUID(TASK_A),
            tenant_id=TENANT,
            dependency_mentions=["mention one", "mention two"],
        )

    mock_resolve.assert_awaited_once_with(
        publisher._index, ["mention one", "mention two"], k=1, exclude_task_id=TASK_A
    )
