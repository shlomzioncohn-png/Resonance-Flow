"""Tests for the canonical Task contract."""

from uuid import UUID

import pytest
from pydantic import ValidationError

from mind.contracts.base_enums import SourceType
from mind.contracts.task import Task
from mind.contracts.task_enums import (
    AssigneeType,
    ExtractionProvenance,
    TaskStatus,
)


def valid_payload() -> dict:
    """Return a minimal valid canonical Task payload."""

    return {
        "id": "11111111-1111-1111-1111-111111111111",
        "tenant_id": (
            "22222222-2222-2222-2222-222222222222"
        ),
        "title": "Prepare customer report",
        "source_type": "EMAIL",
        "status": "NEW",
        "is_available": True,
        "extraction_provenance": "ML_INFERRED",
        "confidence": 0.92,
        "created_at": "2026-08-30T10:00:00Z",
        "updated_at": "2026-08-30T10:00:00Z",
    }


def test_accepts_valid_task() -> None:
    payload = valid_payload()

    task = Task.model_validate(payload)

    assert task.id == UUID(
        "11111111-1111-1111-1111-111111111111"
    )
    assert task.tenant_id == UUID(
        "22222222-2222-2222-2222-222222222222"
    )
    assert task.title == "Prepare customer report"
    assert task.source_type == SourceType.EMAIL
    assert task.status == TaskStatus.NEW
    assert task.is_available is True
    assert task.extraction_provenance == (
        ExtractionProvenance.ML_INFERRED
    )
    assert task.confidence == 0.92


def test_accepts_owner_id() -> None:
    payload = valid_payload()

    payload["owner_id"] = (
        "33333333-3333-3333-3333-333333333333"
    )

    task = Task.model_validate(payload)

    assert task.owner_id == UUID(
        "33333333-3333-3333-3333-333333333333"
    )


def test_accepts_user_and_team_assignees() -> None:
    payload = valid_payload()

    payload["assignees"] = [
        {
            "assignee_type": "USER",
            "id": "44444444-4444-4444-4444-444444444444",
        },
        {
            "assignee_type": "TEAM",
            "id": "55555555-5555-5555-5555-555555555555",
        },
    ]

    task = Task.model_validate(payload)

    assert len(task.assignees) == 2

    assert task.assignees[0].assignee_type == AssigneeType.USER
    assert task.assignees[1].assignee_type == AssigneeType.TEAM


def test_requires_confidence_for_ml_inferred_task() -> None:
    payload = valid_payload()
    payload.pop("confidence")

    with pytest.raises(
        ValidationError,
        match="confidence is required",
    ):
        Task.model_validate(payload)


def test_allows_missing_confidence_for_manual_task() -> None:
    payload = valid_payload()

    payload["extraction_provenance"] = "MANUAL"
    payload.pop("confidence")

    task = Task.model_validate(payload)

    assert task.extraction_provenance == (
        ExtractionProvenance.MANUAL
    )
    assert task.confidence is None


def test_requires_snoozed_until_when_status_is_snoozed() -> None:
    payload = valid_payload()
    payload["status"] = "SNOOZED"

    with pytest.raises(
        ValidationError,
        match="snoozed_until is required",
    ):
        Task.model_validate(payload)


def test_accepts_snoozed_task_with_snoozed_until() -> None:
    payload = valid_payload()

    payload["status"] = "SNOOZED"
    payload["snoozed_until"] = "2026-09-01T10:00:00Z"

    task = Task.model_validate(payload)

    assert task.status == TaskStatus.SNOOZED
    assert task.snoozed_until is not None


@pytest.mark.parametrize(
    "urgency",
    [0, 6],
)
def test_rejects_invalid_urgency(urgency: int) -> None:
    payload = valid_payload()
    payload["urgency"] = urgency

    with pytest.raises(ValidationError):
        Task.model_validate(payload)


def test_rejects_non_positive_effort_estimate() -> None:
    payload = valid_payload()
    payload["effort_estimate_minutes"] = 0

    with pytest.raises(ValidationError):
        Task.model_validate(payload)


@pytest.mark.parametrize(
    "confidence",
    [-0.01, 1.01],
)
def test_rejects_invalid_confidence(
    confidence: float,
) -> None:
    payload = valid_payload()
    payload["confidence"] = confidence

    with pytest.raises(ValidationError):
        Task.model_validate(payload)


def test_rejects_empty_title() -> None:
    payload = valid_payload()
    payload["title"] = ""

    with pytest.raises(ValidationError):
        Task.model_validate(payload)


def test_rejects_unknown_field() -> None:
    payload = valid_payload()
    payload["unknown_field"] = "unexpected"

    with pytest.raises(ValidationError):
        Task.model_validate(payload)