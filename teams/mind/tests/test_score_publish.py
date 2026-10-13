"""#357: task.score.proposed is published at creation, re-published when a
field the score depends on changes, and not re-published otherwise.
No broker: the sink is an in-memory list."""

from datetime import UTC, datetime
from uuid import UUID

import pytest
from google.protobuf.timestamp_pb2 import Timestamp

from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import ScoreContext
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.inbox_task import to_scoring_task
from mind.scoring.score_publisher import ScorePublisher
from mind.scoring.segmented_model import SegmentedRankingModel

TASK_A = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
TASK_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
NOW = datetime(2026, 10, 13, 9, 0, tzinfo=UTC)
DEADLINE = datetime(2026, 10, 20, 9, 0, tzinfo=UTC)


class ListSink:
    def __init__(self) -> None:
        self.sent: list[TaskScoreProposed] = []

    def send(self, payload: TaskScoreProposed) -> None:
        self.sent.append(payload)


class FailsOnceSink(ListSink):
    def __init__(self) -> None:
        super().__init__()
        self._failed = False

    def send(self, payload: TaskScoreProposed) -> None:
        if not self._failed:
            self._failed = True
            raise RuntimeError("broker down")
        super().send(payload)


def _timestamp(dt: datetime) -> Timestamp:
    ts = Timestamp()
    ts.FromDatetime(dt)
    return ts


def _created(task_id: str = TASK_A) -> TaskCreatedEvent:
    return TaskCreatedEvent(
        id=task_id,
        title="Fix the checkout crash",
        description="Customers cannot pay since this morning's deploy.",
        tier=TaskTier.TASK_TIER_OPERATIONAL,
        effort_estimate_minutes=30,
        deadline=_timestamp(DEADLINE),
    )


def _updated(task_id: str = TASK_A, **changes) -> TaskUpdatedEvent:
    """The same task as `_created`, as task.updated, with `changes` applied."""
    fields = {
        "task_id": task_id,
        "title": "Fix the checkout crash",
        "description": "Customers cannot pay since this morning's deploy.",
        "tier": TaskTier.TASK_TIER_OPERATIONAL,
        "effort_estimate_minutes": 30,
        "deadline": _timestamp(DEADLINE),
    }
    fields.update(changes)
    return TaskUpdatedEvent(**fields)


@pytest.fixture
def sink() -> ListSink:
    return ListSink()


@pytest.fixture
def publisher(sink: ListSink) -> ScorePublisher:
    return ScorePublisher(sink, clock=lambda: NOW)


# --- publish at creation ----------------------------------------------------


def test_a_created_task_is_published_once(publisher, sink):
    publisher.handle_task_created(_created())

    assert len(sink.sent) == 1
    assert sink.sent[0].task_id == UUID(TASK_A)
    assert sink.sent[0].scored_at == NOW


def test_the_published_score_is_the_models_base_score(publisher, sink):
    event = _created()
    expected = SegmentedRankingModel().score(
        to_scoring_task(event), ScoreContext(current_datetime=NOW)
    )

    publisher.handle_task_created(event)

    assert expected.factors is not None
    assert sink.sent[0].mind_score == expected.factors.base_score


# --- re-publish when the score can change -----------------------------------


@pytest.mark.parametrize(
    "change",
    [
        {"title": "Fix the checkout crash before the sale"},
        {"description": "Only some customers are affected."},
        {"tier": TaskTier.TASK_TIER_TACTICAL},
        {"deadline": _timestamp(datetime(2026, 10, 14, 9, 0, tzinfo=UTC))},
        {"effort_estimate_minutes": 90},
    ],
    ids=["title", "description", "tier", "deadline", "effort"],
)
def test_a_change_the_model_reads_is_re_published(publisher, sink, change):
    publisher.handle_task_created(_created())

    publisher.handle_task_updated(_updated(**change))

    assert len(sink.sent) == 2


def test_a_task_that_gets_its_tier_later_is_re_published(publisher, sink):
    created = _created()
    created.ClearField("tier")
    publisher.handle_task_created(created)

    publisher.handle_task_updated(_updated())

    assert len(sink.sent) == 2


# --- no re-publish otherwise (the Chain feedback loop) ----------------------


def test_chains_scheduling_update_is_not_re_published(publisher, sink):
    # Chain's update reaches us as task.updated with only is_available changed.
    publisher.handle_task_created(_created())

    publisher.handle_task_updated(_updated(is_available=True))

    assert len(sink.sent) == 1


def test_an_urgency_change_is_not_re_published(publisher, sink):
    # The model never reads the urgency field, so the score cannot change.
    publisher.handle_task_created(_created())

    publisher.handle_task_updated(_updated(urgency=5))

    assert len(sink.sent) == 1


def test_tasks_are_tracked_separately(publisher, sink):
    publisher.handle_task_created(_created(TASK_A))
    publisher.handle_task_created(_created(TASK_B))

    publisher.handle_task_updated(_updated(TASK_B, effort_estimate_minutes=90))

    assert [p.task_id for p in sink.sent] == [UUID(TASK_A), UUID(TASK_B), UUID(TASK_B)]


# --- state ------------------------------------------------------------------


def test_an_update_for_an_unseen_task_is_published(publisher, sink):
    # e.g. after a restart, when the in-memory state is empty.
    publisher.handle_task_updated(_updated())

    assert len(sink.sent) == 1


def test_a_failed_send_is_not_recorded_so_it_is_retried():
    sink = FailsOnceSink()
    publisher = ScorePublisher(sink, clock=lambda: NOW)
    with pytest.raises(RuntimeError):
        publisher.handle_task_created(_created())

    # Kafka redelivers the same event, since the offset was not committed.
    publisher.handle_task_created(_created())

    assert len(sink.sent) == 1
