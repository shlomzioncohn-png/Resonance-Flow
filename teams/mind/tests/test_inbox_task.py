"""#357: an Inbox task event becomes a Task the segmented model can score,
with the team's defaults for whatever a real task is still missing."""

from datetime import UTC, datetime

from google.protobuf.timestamp_pb2 import Timestamp

from mind.evaluation.interfaces import ScoreContext
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring import features
from mind.scoring.inbox_task import (
    DEFAULT_EFFORT_MINUTES,
    DEFAULT_TIER,
    PLACEHOLDER_TASK_ID,
    to_scoring_task,
)
from mind.scoring.segmented_model import SegmentedRankingModel

TASK_UUID = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
DEADLINE = datetime(2026, 10, 20, 9, 0, tzinfo=UTC)


def _timestamp(dt: datetime) -> Timestamp:
    ts = Timestamp()
    ts.FromDatetime(dt)
    return ts


def _full_created_event() -> TaskCreatedEvent:
    return TaskCreatedEvent(
        id=TASK_UUID,
        title="Fix the checkout crash",
        description="Customers cannot pay since this morning's deploy.",
        tier=TaskTier.TASK_TIER_OPERATIONAL,
        effort_estimate_minutes=30,
        deadline=_timestamp(DEADLINE),
    )


def test_a_fully_classified_task_keeps_its_own_values():
    task = to_scoring_task(_full_created_event())

    assert task.title == "Fix the checkout crash"
    assert task.description == "Customers cannot pay since this morning's deploy."
    assert task.tier == "operational"
    assert task.effort_estimate == 30
    assert task.deadline == DEADLINE


def test_a_real_uuid_does_not_reach_the_eval_task():
    # The eval Task's id pattern rejects UUIDs; the model never reads the id anyway.
    task = to_scoring_task(_full_created_event())

    assert task.task_id == PLACEHOLDER_TASK_ID


def test_blocked_and_parent_are_never_set():
    task = to_scoring_task(_full_created_event())

    assert task.blocked is False
    assert task.parent_task_id is None


def test_task_updated_is_converted_the_same_way():
    event = TaskUpdatedEvent(
        task_id=TASK_UUID,
        title="Plan the sprint",
        description="Decide who picks up the migration.",
        tier=TaskTier.TASK_TIER_STRATEGIC,
        effort_estimate_minutes=60,
    )

    task = to_scoring_task(event)

    assert task.tier == "strategic"
    assert task.effort_estimate == 60


def test_a_task_with_no_tier_is_scored_as_tactical():
    event = _full_created_event()
    event.ClearField("tier")

    assert to_scoring_task(event).tier == DEFAULT_TIER == "tactical"


def test_a_task_with_no_effort_gets_no_quick_win_bonus():
    event = _full_created_event()
    event.ClearField("effort_estimate_minutes")

    task = to_scoring_task(event)

    assert task.effort_estimate == DEFAULT_EFFORT_MINUTES
    assert features.quick_win(task) == 0.0


def test_a_task_with_no_deadline_has_none():
    event = _full_created_event()
    event.ClearField("deadline")

    assert to_scoring_task(event).deadline is None


def test_an_empty_description_falls_back_to_the_title():
    event = _full_created_event()
    event.description = ""

    assert to_scoring_task(event).description == "Fix the checkout crash"


def test_a_bare_task_can_still_be_scored():
    # Only a title: no tier, effort, deadline or description.
    event = TaskCreatedEvent(id=TASK_UUID, title="Look into the flaky test")
    context = ScoreContext(current_datetime=datetime(2026, 10, 13, 9, 0, tzinfo=UTC))

    result = SegmentedRankingModel().score(to_scoring_task(event), context)

    assert result.factors is not None
    assert result.factors.base_score is not None
    assert 0.0 <= result.factors.base_score <= 1.0
