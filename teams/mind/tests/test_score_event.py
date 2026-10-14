"""#357: the task.score.proposed payload built for Chain from a model result."""

from datetime import UTC, datetime
from uuid import UUID

import pytest

from mind.contracts.task_enums import TaskTier
from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import ScoreFactors, ScoreResult
from mind.scoring.confidence import ASSUMED_TIER_SHIFT
from mind.scoring.inbox_task import InboxTask
from mind.scoring.score_event import build_score_event

TASK_UUID = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
EVENT_ID = UUID("11111111-2222-4333-8444-555555555555")
SCORED_AT = datetime(2026, 10, 13, 9, 0, tzinfo=UTC)

# Nothing missing, so full confidence.
FULL_TASK = InboxTask(
    task_id=TASK_UUID,
    title="Fix the checkout crash",
    description="Customers cannot pay.",
    deadline=datetime(2026, 10, 20, 9, 0, tzinfo=UTC),
    effort_estimate=30,
    tier="operational",
)

# A blocked result, so `score` and `base_score` differ and a mix-up shows.
BLOCKED_RESULT = ScoreResult(
    score=0.15,
    factors=ScoreFactors(
        contributions={"time_pressure": 0.4, "impact": 0.2},
        blocked=True,
        base_score=0.6,
    ),
)


def _build(task=FULL_TASK, result=BLOCKED_RESULT) -> TaskScoreProposed:
    return build_score_event(task, result, scored_at=SCORED_AT, event_id=EVENT_ID)


def test_the_payload_has_exactly_the_agreed_fields():
    assert set(TaskScoreProposed.model_fields) == {
        "event_id",
        "task_id",
        "mind_score",
        "scored_at",
        "factors",
        "confidence",
        "tier",
        "effort_estimate_minutes",
        "deadline",
    }


def test_inbox_fields_pass_through():
    payload = _build()

    assert payload.tier == TaskTier.OPERATIONAL
    assert payload.effort_estimate_minutes == 30
    assert payload.deadline == FULL_TASK.deadline


def test_unset_inbox_fields_stay_none():
    # The model scores a task with no tier by the tactical rule, but Chain must
    # see only a tier someone actually assigned.
    task = InboxTask(
        task_id=TASK_UUID,
        title="Look into the flaky test",
        description=None,
        deadline=None,
        effort_estimate=None,
        tier=None,
    )

    payload = _build(task)

    assert payload.tier is None
    assert payload.effort_estimate_minutes is None
    assert payload.deadline is None


def test_mind_score_is_the_base_score_not_the_blocked_score():
    assert _build().mind_score == 0.6


def test_factors_are_the_model_contributions():
    assert _build().factors == {"time_pressure": 0.4, "impact": 0.2}


def test_ids_and_time_are_carried_as_given():
    payload = _build()

    assert payload.task_id == UUID(TASK_UUID)
    assert payload.event_id == EVENT_ID
    assert payload.scored_at == SCORED_AT


def test_each_publish_gets_a_new_event_id_by_default():
    first = build_score_event(FULL_TASK, BLOCKED_RESULT, scored_at=SCORED_AT)
    second = build_score_event(FULL_TASK, BLOCKED_RESULT, scored_at=SCORED_AT)

    assert first.event_id != second.event_id


def test_a_task_with_nothing_missing_has_full_confidence():
    assert _build().confidence == 1.0


def test_a_missing_tier_lowers_confidence():
    task = InboxTask(**{**FULL_TASK.__dict__, "tier": None})

    assert _build(task).confidence == pytest.approx(1.0 - ASSUMED_TIER_SHIFT)


def test_a_result_without_base_score_is_refused():
    with pytest.raises(ValueError, match="base_score"):
        _build(result=ScoreResult(score=0.7))
