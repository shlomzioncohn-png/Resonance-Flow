"""Tests for Assignee and AssigneeCandidate contracts."""

from uuid import UUID

import pytest
from pydantic import ValidationError

from mind.contracts.assignee import (
    Assignee,
    AssigneeCandidate,
)
from mind.contracts.task_enums import AssigneeType


def test_accepts_user_assignee() -> None:
    payload = {
        "type": "user",
        "id": "11111111-1111-1111-1111-111111111111",
    }

    assignee = Assignee.model_validate(payload)

    assert assignee.type == AssigneeType.USER
    assert assignee.id == UUID(
        "11111111-1111-1111-1111-111111111111"
    )


def test_accepts_team_assignee() -> None:
    payload = {
        "type": "team",
        "id": "22222222-2222-2222-2222-222222222222",
    }

    assignee = Assignee.model_validate(payload)

    assert assignee.type == AssigneeType.TEAM
    assert assignee.id == UUID(
        "22222222-2222-2222-2222-222222222222"
    )


def test_rejects_invalid_assignee_type() -> None:
    payload = {
        "type": "department",
        "id": "11111111-1111-1111-1111-111111111111",
    }

    with pytest.raises(ValidationError):
        Assignee.model_validate(payload)


def test_rejects_invalid_assignee_id() -> None:
    payload = {
        "type": "user",
        "id": "not-a-uuid",
    }

    with pytest.raises(ValidationError):
        Assignee.model_validate(payload)


def test_accepts_user_assignee_candidate() -> None:
    payload = {
        "type": "user",
        "id": "33333333-3333-3333-3333-333333333333",
        "confidence": 0.91,
    }

    candidate = AssigneeCandidate.model_validate(payload)

    assert candidate.type == AssigneeType.USER
    assert candidate.id == UUID(
        "33333333-3333-3333-3333-333333333333"
    )
    assert candidate.confidence == 0.91


def test_accepts_team_assignee_candidate() -> None:
    payload = {
        "type": "team",
        "id": "44444444-4444-4444-4444-444444444444",
        "confidence": 0.75,
    }

    candidate = AssigneeCandidate.model_validate(payload)

    assert candidate.type == AssigneeType.TEAM
    assert candidate.confidence == 0.75


@pytest.mark.parametrize(
    "confidence",
    [-0.01, 1.01],
)
def test_rejects_invalid_candidate_confidence(
    confidence: float,
) -> None:
    payload = {
        "type": "user",
        "id": "33333333-3333-3333-3333-333333333333",
        "confidence": confidence,
    }

    with pytest.raises(ValidationError):
        AssigneeCandidate.model_validate(payload)


def test_rejects_unknown_assignee_field() -> None:
    payload = {
        "type": "user",
        "id": "11111111-1111-1111-1111-111111111111",
        "unknown_field": "unexpected",
    }

    with pytest.raises(ValidationError):
        Assignee.model_validate(payload)