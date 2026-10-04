"""Tests for the internal extraction models."""

import pytest
from pydantic import ValidationError

from mind.extraction.models import MAX_TITLE_LENGTH, ExtractedFields


def test_accepts_minimal_valid_fields() -> None:
    fields = ExtractedFields.model_validate(
        {"title": "Send the Q3 report", "has_dependencies": False}
    )

    assert fields.title == "Send the Q3 report"
    assert fields.description is None
    assert fields.source_ref is None


def test_strips_surrounding_whitespace() -> None:
    fields = ExtractedFields.model_validate(
        {"title": "  Send the Q3 report  ", "has_dependencies": False}
    )

    assert fields.title == "Send the Q3 report"


@pytest.mark.parametrize("placeholder", ["", "  ", "N/A", "none", "null", "Unknown", "-"])
def test_placeholder_strings_become_none(placeholder: str) -> None:
    fields = ExtractedFields.model_validate(
        {
            "title": "Send the Q3 report",
            "description": placeholder,
            "source_ref": placeholder,
            "has_dependencies": False,
        }
    )

    assert fields.description is None
    assert fields.source_ref is None


def test_rejects_hallucinated_extra_field() -> None:
    with pytest.raises(ValidationError):
        ExtractedFields.model_validate(
            {
                "title": "Send the Q3 report",
                "has_dependencies": False,
                "urgency": 4,
            }
        )


def test_rejects_empty_title() -> None:
    with pytest.raises(ValidationError):
        ExtractedFields.model_validate({"title": "   ", "has_dependencies": False})


def test_rejects_runaway_title() -> None:
    with pytest.raises(ValidationError):
        ExtractedFields.model_validate(
            {"title": "x" * (MAX_TITLE_LENGTH + 1), "has_dependencies": False}
        )


def test_requires_has_dependencies() -> None:
    with pytest.raises(ValidationError):
        ExtractedFields.model_validate({"title": "Send the Q3 report"})
