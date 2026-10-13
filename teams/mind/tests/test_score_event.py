"""#357: the task.score.proposed payload built for Chain from a model result."""

from datetime import UTC, datetime
from uuid import UUID

import pytest
from google.protobuf.timestamp_pb2 import Timestamp

from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import ScoreFactors, ScoreResult
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_created_event_pb2 import TaskTier as ProtoTaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.score_event import build_score_event

TASK_UUID = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
EVENT_ID = UUID("11111111-2222-4333-8444-555555555555")
SCORED_AT = datetime(2026, 10, 13, 9, 0, tzinfo=UTC)
DEADLINE = datetime(2026, 10, 20, 9, 0, tzinfo=UTC)

# A blocked result, so `score` and `base_score` differ and a mix-up shows.
BLOCKED_RESULT = ScoreResult(
    score=0.15,
    factors=ScoreFactors(
        contributions={"time_pressure": 0.4, "impact": 0.2},
        blocked=True,
        base_score=0.6,
    ),
)


def _timestamp(dt: datetime) -> Timestamp:
    ts = Timestamp()
    ts.FromDatetime(dt)
    return ts


def _bare_created_event() -> TaskCreatedEvent:
    return TaskCreatedEvent(id=TASK_UUID, title="Look into the flaky test")


def _build(event, result=BLOCKED_RESULT) -> TaskScoreProposed:
    return build_score_event(event, result, scored_at=SCORED_AT, event_id=EVENT_ID)


def test_the_payload_has_exactly_the_agreed_fields():
    assert set(TaskScoreProposed.model_fields) == {
        "event_id",
        "task_id",
        "mind_score",
        "scored_at",
        "factors",
        "confidence",
    }


def test_mind_score_is_the_base_score_not_the_blocked_score():
    assert _build(_bare_created_event()).mind_score == 0.6


def test_factors_are_the_model_contributions():
    assert _build(_bare_created_event()).factors == {"time_pressure": 0.4, "impact": 0.2}


def test_ids_and_time_are_carried_as_given():
    payload = _build(_bare_created_event())

    assert payload.task_id == UUID(TASK_UUID)
    assert payload.event_id == EVENT_ID
    assert payload.scored_at == SCORED_AT


def test_task_updated_reads_its_id_from_task_id():
    event = TaskUpdatedEvent(task_id=TASK_UUID, title="Look into the flaky test")

    assert _build(event).task_id == UUID(TASK_UUID)


def test_each_publish_gets_a_new_event_id_by_default():
    event = _bare_created_event()
    first = build_score_event(event, BLOCKED_RESULT, scored_at=SCORED_AT)
    second = build_score_event(event, BLOCKED_RESULT, scored_at=SCORED_AT)

    assert first.event_id != second.event_id


def test_confidence_passes_through_from_inbox():
    event = _bare_created_event()
    event.confidence = 0.5

    assert _build(event).confidence == 0.5


def test_unset_confidence_stays_none():
    # Chain reads None as 1.0.
    assert _build(_bare_created_event()).confidence is None


def test_chain_does_not_get_inbox_fields_it_takes_from_inbox():
    # Agreed with Chain: tier, effort and deadline come from Inbox, not from us.
    event = TaskCreatedEvent(
        id=TASK_UUID,
        title="Fix the checkout crash",
        tier=ProtoTaskTier.TASK_TIER_OPERATIONAL,
        effort_estimate_minutes=30,
        deadline=_timestamp(DEADLINE),
    )

    payload = _build(event).model_dump()

    assert "tier" not in payload
    assert "effort_estimate_minutes" not in payload
    assert "deadline" not in payload


def test_a_result_without_base_score_is_refused():
    result = ScoreResult(score=0.7)

    with pytest.raises(ValueError, match="base_score"):
        _build(_bare_created_event(), result)
