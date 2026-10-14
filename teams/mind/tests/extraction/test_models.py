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


# --- a placeholder title is not a title -----------------------------------


@pytest.mark.parametrize("placeholder", ["N/A", "n/a", "unknown", "-", "TBD", "none", "   "])
def test_a_placeholder_title_fails_validation(placeholder: str) -> None:
    """Unlike description and source_ref, title has nowhere to fall back to.

    It is required by the contract, so a model that wrote "N/A" has not
    answered. Failing here is what sends the answer back for repair; letting
    it through puts a task literally called "N/A" in front of a user.
    """

    with pytest.raises(ValidationError):
        ExtractedFields(title=placeholder, has_dependencies=False)


def test_a_real_title_still_survives_the_same_check() -> None:
    fields = ExtractedFields(title="  Send the Q3 report  ", has_dependencies=False)

    assert fields.title == "Send the Q3 report"


def test_a_title_that_merely_contains_a_placeholder_word_is_fine() -> None:
    """Only a title that is *nothing but* a placeholder is rejected."""

    fields = ExtractedFields(title="Replace the N/A values in the export", has_dependencies=False)

    assert fields.title.startswith("Replace")


def test_the_rejection_tells_the_model_what_was_wrong() -> None:
    """The repair round only pays for itself if the message is actionable.

    Pydantic's own complaint about a null title is "Input should be a valid
    string", which is useless to a model that wrote "N/A" — that *is* a valid
    string. The validator raises its own message instead.
    """

    with pytest.raises(ValidationError) as raised:
        ExtractedFields(title="N/A", has_dependencies=False)

    message = str(raised.value)
    assert "placeholder" in message
    assert "N/A" in message
