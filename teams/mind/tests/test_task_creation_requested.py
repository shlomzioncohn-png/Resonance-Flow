"""Tests for the TaskCreationRequested contract."""

from uuid import UUID

import pytest
from pydantic import ValidationError

from mind.contracts.task_creation_requested import (
    TaskCreationRequested,
)

from mind.contracts.base_enums import SourceType

from mind.contracts.task_enums import (
    AssigneeType,
    RoleContext,
    TaskTier,
)
import json


def valid_payload() -> dict:
    """Return a minimal valid task creation request payload."""

    return {
        "tenant_id": "11111111-1111-1111-1111-111111111111",
        "title": "Prepare customer report",
        "source_type": "EMAIL",
        "has_dependencies": False,
        "confidence": 0.92,
    }


def test_accepts_minimal_valid_creation_request() -> None:
    payload = valid_payload()

    request = TaskCreationRequested.model_validate(payload)

    assert request.tenant_id == UUID(
        "11111111-1111-1111-1111-111111111111"
    )
    assert request.title == "Prepare customer report"
    assert request.source_type == SourceType.EMAIL
    assert request.has_dependencies is False
    assert request.confidence == 0.92


def test_accepts_optional_fields() -> None:
    payload = valid_payload()

    payload.update(
        {
            "description": "Prepare the report before Monday.",
            "source_ref": "EMAIL/message-123",
            "tier": "OPERATIONAL",
            "role_context": "DEVELOPER",
            "urgency": 4,
            "effort_estimate_minutes": 30,
            "effort_estimate_confidence": 0.80,
            "deadline": "2026-09-01T10:00:00Z",
            "suggested_owner_user_id": (
                "22222222-2222-2222-2222-222222222222"
            ),
            "suggested_owner_confidence": 0.88,
        }
    )

    request = TaskCreationRequested.model_validate(payload)

    assert request.description == "Prepare the report before Monday."
    assert request.source_ref == "EMAIL/message-123"
    assert request.tier == TaskTier.OPERATIONAL
    assert request.role_context == RoleContext.DEVELOPER
    assert request.urgency == 4
    assert request.effort_estimate_minutes == 30
    assert request.effort_estimate_confidence == 0.80
    assert request.suggested_owner_user_id == UUID(
        "22222222-2222-2222-2222-222222222222"
    )
    assert request.suggested_owner_confidence == 0.88


def test_accepts_user_and_team_candidates() -> None:
    payload = valid_payload()

    payload["candidate_assignees"] = [
        {
            "assignee_type": "USER",
            "id": "33333333-3333-3333-3333-333333333333",
            "confidence": 0.91,
        },
        {
            "assignee_type": "TEAM",
            "id": "44444444-4444-4444-4444-444444444444",
            "confidence": 0.76,
        },
    ]

    request = TaskCreationRequested.model_validate(payload)

    assert len(request.candidate_assignees) == 2

    assert request.candidate_assignees[0].assignee_type == (
        AssigneeType.USER
    )
    assert request.candidate_assignees[0].confidence == 0.91

    assert request.candidate_assignees[1].assignee_type == (
        AssigneeType.TEAM
    )
    assert request.candidate_assignees[1].confidence == 0.76


@pytest.mark.parametrize(
    "urgency",
    [1, 2, 3, 4, 5],
)
def test_accepts_valid_urgency(urgency: int) -> None:
    payload = valid_payload()
    payload["urgency"] = urgency

    request = TaskCreationRequested.model_validate(payload)

    assert request.urgency == urgency


@pytest.mark.parametrize(
    "urgency",
    [0, 6],
)
def test_rejects_invalid_urgency(urgency: int) -> None:
    payload = valid_payload()
    payload["urgency"] = urgency

    with pytest.raises(ValidationError):
        TaskCreationRequested.model_validate(payload)


@pytest.mark.parametrize(
    "confidence",
    [-0.01, 1.01],
)
def test_rejects_invalid_general_confidence(
    confidence: float,
) -> None:
    payload = valid_payload()
    payload["confidence"] = confidence

    with pytest.raises(ValidationError):
        TaskCreationRequested.model_validate(payload)


def test_rejects_non_positive_effort_estimate() -> None:
    payload = valid_payload()
    payload["effort_estimate_minutes"] = 0

    with pytest.raises(ValidationError):
        TaskCreationRequested.model_validate(payload)


def test_rejects_empty_title() -> None:
    payload = valid_payload()
    payload["title"] = ""

    with pytest.raises(ValidationError):
        TaskCreationRequested.model_validate(payload)


def test_rejects_unknown_field() -> None:
    payload = valid_payload()
    payload["unknown_field"] = "unexpected"

    with pytest.raises(ValidationError):
        TaskCreationRequested.model_validate(payload)


def test_serializes_enums_as_upper_snake_case() -> None:
    payload = valid_payload()

    request = TaskCreationRequested.model_validate(payload)

    serialized = json.loads(request.model_dump_json())

    assert serialized["source_type"] == "EMAIL"
