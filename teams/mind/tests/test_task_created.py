"""Tests for the TaskCreated contract."""

from uuid import UUID

import pytest
from pydantic import ValidationError

from mind.contracts.task_created import TaskCreated
from mind.contracts.task_enums import TaskStatus


def valid_task_payload() -> dict:
    """Return a minimal valid canonical Task payload."""

    return {
        "id": "11111111-1111-1111-1111-111111111111",
        "organization_id": (
            "22222222-2222-2222-2222-222222222222"
        ),
        "title": "Prepare customer report",
        "source_type": "email",
        "status": "new",
        "is_available": True,
        "extraction_provenance": "ml_inferred",
        "confidence": 0.92,
        "created_at": "2026-08-30T10:00:00Z",
        "updated_at": "2026-08-30T10:00:00Z",
    }


def test_accepts_valid_task_created() -> None:
    payload = {
        "task": valid_task_payload(),
    }

    event = TaskCreated.model_validate(payload)

    assert event.task.id == UUID(
        "11111111-1111-1111-1111-111111111111"
    )
    assert event.task.title == "Prepare customer report"
    assert event.task.status == TaskStatus.NEW


def test_rejects_task_created_without_task() -> None:
    with pytest.raises(ValidationError):
        TaskCreated.model_validate({})


def test_rejects_task_created_with_invalid_task() -> None:
    task_payload = valid_task_payload()
    task_payload["id"] = "not-a-uuid"

    payload = {
        "task": task_payload,
    }

    with pytest.raises(ValidationError):
        TaskCreated.model_validate(payload)


def test_rejects_unknown_task_created_field() -> None:
    payload = {
        "task": valid_task_payload(),
        "unknown_field": "unexpected",
    }

    with pytest.raises(ValidationError):
        TaskCreated.model_validate(payload)