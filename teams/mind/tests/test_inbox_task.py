"""#357: an Inbox task event becomes a ScorableTask, with empty fields left
empty for the model to decide on."""

from datetime import UTC, datetime

import pytest
from google.protobuf.timestamp_pb2 import Timestamp

from mind.evaluation.interfaces import ScoreContext
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.inbox_task import InvalidInboxEvent, to_scoring_task
from mind.scoring.inputs import ScorableTask
from mind.scoring.segmented_model import SegmentedRankingModel

TASK_UUID = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
TENANT_UUID = "7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a"
DEADLINE = datetime(2026, 10, 20, 9, 0, tzinfo=UTC)


def _timestamp(dt: datetime) -> Timestamp:
    ts = Timestamp()
    ts.FromDatetime(dt)
    return ts


def _full_created_event() -> TaskCreatedEvent:
    return TaskCreatedEvent(
        id=TASK_UUID,
        tenant_id=TENANT_UUID,
        title="Fix the checkout crash",
        description="Customers cannot pay since this morning's deploy.",
        tier=TaskTier.TASK_TIER_OPERATIONAL,
        effort_estimate_minutes=30,
        deadline=_timestamp(DEADLINE),
    )


def test_a_fully_classified_task_keeps_its_own_values():
    task = to_scoring_task(_full_created_event())

    assert task.task_id == TASK_UUID
    assert task.tenant_id == TENANT_UUID
    assert task.title == "Fix the checkout crash"
    assert task.description == "Customers cannot pay since this morning's deploy."
    assert task.effort_estimate == 30
    assert task.deadline == DEADLINE


def test_the_tier_is_converted_to_lower_case():
    # Inbox writes OPERATIONAL; the model's rules are keyed "operational".
    assert to_scoring_task(_full_created_event()).tier == "operational"


def test_blocked_and_parent_are_never_set():
    task = to_scoring_task(_full_created_event())

    assert task.blocked is False
    assert task.parent_task_id is None


def test_task_updated_reads_its_id_from_task_id():
    event = TaskUpdatedEvent(task_id=TASK_UUID, tenant_id=TENANT_UUID, title="Plan the sprint")

    assert to_scoring_task(event).task_id == TASK_UUID


def test_empty_fields_stay_empty():
    # Only a title: no tier, effort, deadline or description. No defaults here,
    # the model decides how to score what is missing.
    task = to_scoring_task(
        TaskCreatedEvent(id=TASK_UUID, tenant_id=TENANT_UUID, title="Look into the flaky test")
    )

    assert task.tier is None
    assert task.effort_estimate is None
    assert task.deadline is None
    assert task.description is None


def test_the_result_fits_the_models_input():
    # Checked by mypy as much as by pytest: the annotation is the claim.
    task: ScorableTask = to_scoring_task(_full_created_event())

    assert task.task_id == TASK_UUID


def test_a_bare_task_can_be_scored():
    event = TaskCreatedEvent(id=TASK_UUID, tenant_id=TENANT_UUID, title="Look into the flaky test")
    context = ScoreContext(current_datetime=datetime(2026, 10, 13, 9, 0, tzinfo=UTC))

    result = SegmentedRankingModel().score(to_scoring_task(event), context)

    assert result.factors is not None
    assert result.factors.base_score is not None
    assert 0.0 <= result.factors.base_score <= 1.0


def test_a_deadline_can_be_scored_against_an_aware_now():
    # The model refuses a naive deadline against an aware now; the conversion
    # must build an aware one.
    context = ScoreContext(current_datetime=datetime(2026, 10, 13, 9, 0, tzinfo=UTC))

    SegmentedRankingModel().score(to_scoring_task(_full_created_event()), context)


# --- events that can never be scored ----------------------------------------


@pytest.mark.parametrize("bad_id", ["", "not-a-uuid"])
def test_a_task_id_that_is_not_a_uuid_is_rejected(bad_id):
    event = _full_created_event()
    event.id = bad_id

    with pytest.raises(InvalidInboxEvent, match="task_id"):
        to_scoring_task(event)


@pytest.mark.parametrize("bad_id", ["", "not-a-uuid"])
def test_a_tenant_id_that_is_not_a_uuid_is_rejected(bad_id):
    event = _full_created_event()
    event.tenant_id = bad_id

    with pytest.raises(InvalidInboxEvent, match="tenant_id"):
        to_scoring_task(event)


@pytest.mark.parametrize("effort", [0, -30])
def test_a_non_positive_effort_is_read_as_missing(effort):
    event = _full_created_event()
    event.effort_estimate_minutes = effort

    assert to_scoring_task(event).effort_estimate is None


def test_ids_are_canonicalized():
    # One task must not be tracked twice because a topic spelled its id differently.
    event = _full_created_event()
    event.id = TASK_UUID.upper()

    assert to_scoring_task(event).task_id == TASK_UUID
