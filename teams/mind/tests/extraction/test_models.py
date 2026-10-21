"""Tests for the internal extraction models."""

import pytest
from pydantic import ValidationError

from mind.extraction.models import (
    MAX_TASKS_PER_TEXT,
    MAX_TITLE_LENGTH,
    ExtractedFields,
    ExtractedFieldsWithClassification,
    ExtractedTasks,
)


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
    # In a list (#690), "no task here" is said by leaving the entry out.
    assert "leave it out of the list" in message
    assert "N/A" in message


# --- the answer is a list of tasks (#690) ---------------------------------

TASK = {"title": "Send the Q3 report", "has_dependencies": False}


def test_each_task_is_read_into_the_shape_that_was_asked_for() -> None:
    answer = {"tasks": [TASK, {**TASK, "tier": "OPERATIONAL"}]}

    tasks = ExtractedTasks[ExtractedFieldsWithClassification].model_validate(answer).tasks

    assert [type(task) for task in tasks] == [ExtractedFieldsWithClassification] * 2
    assert tasks[0].tier is None


def test_an_empty_list_is_a_valid_answer() -> None:
    assert ExtractedTasks[ExtractedFields].model_validate({"tasks": []}).tasks == []


def test_more_tasks_than_the_cap_are_rejected() -> None:
    ExtractedTasks[ExtractedFields].model_validate({"tasks": [TASK] * MAX_TASKS_PER_TEXT})

    with pytest.raises(ValidationError):
        ExtractedTasks[ExtractedFields].model_validate(
            {"tasks": [TASK] * (MAX_TASKS_PER_TEXT + 1)}
        )


def test_one_bad_task_rejects_the_answer_and_says_which_one() -> None:
    with pytest.raises(ValidationError) as raised:
        ExtractedTasks[ExtractedFields].model_validate({"tasks": [TASK, {**TASK, "title": "N/A"}]})

    assert raised.value.errors()[0]["loc"][:2] == ("tasks", 1)


@pytest.mark.parametrize("answer", [TASK, {"tasks": [TASK], "summary": "one task"}])
def test_a_single_task_or_an_extra_key_is_not_the_list(answer: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        ExtractedTasks[ExtractedFields].model_validate(answer)
