"""Tests for the TaskCompleted contract."""

from uuid import UUID

import pytest
from pydantic import ValidationError

from mind.contracts.task_completed import TaskCompleted


def test_accepts_completed_task() -> None:
    payload = {
        "task_id": "11111111-1111-1111-1111-111111111111",
        "is_completed": True,
    }

    event = TaskCompleted.model_validate(payload)

    assert event.task_id == UUID(
        "11111111-1111-1111-1111-111111111111"
    )
    assert event.is_completed is True


def test_accepts_not_completed_task() -> None:
    payload = {
        "task_id": "11111111-1111-1111-1111-111111111111",
        "is_completed": False,
    }

    event = TaskCompleted.model_validate(payload)

    assert event.is_completed is False


def test_rejects_invalid_task_id() -> None:
    payload = {
        "task_id": "not-a-uuid",
        "is_completed": True,
    }

    with pytest.raises(ValidationError):
        TaskCompleted.model_validate(payload)


def test_rejects_missing_task_id() -> None:
    payload = {
        "is_completed": True,
    }

    with pytest.raises(ValidationError):
        TaskCompleted.model_validate(payload)


def test_rejects_missing_is_completed() -> None:
    payload = {
        "task_id": "11111111-1111-1111-1111-111111111111",
    }

    with pytest.raises(ValidationError):
        TaskCompleted.model_validate(payload)


def test_rejects_unknown_field() -> None:
    payload = {
        "task_id": "11111111-1111-1111-1111-111111111111",
        "is_completed": True,
        "unknown_field": "unexpected",
    }

    with pytest.raises(ValidationError):
        TaskCompleted.model_validate(payload)