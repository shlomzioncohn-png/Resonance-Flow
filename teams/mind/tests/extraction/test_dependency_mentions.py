"""What `dependency_mentions` accepts, and what it refuses (#690, was #842).

Optional, like the classification fields: an item that cannot be used is
dropped and logged, and never costs a repair round. Whatever survives reaches
#834's resolver, where one empty mention used to stall the whole listener.
"""

import logging

import pytest

from mind.extraction.models import (
    MAX_MENTIONS_PER_TASK,
    MAX_TITLE_LENGTH,
    ExtractedFields,
    ExtractedFieldsWithClassification,
)


def _task(mentions: object, has_dependencies: bool = True) -> ExtractedFields:
    return ExtractedFields.model_validate(
        {
            "title": "Migrate the billing tables",
            "has_dependencies": has_dependencies,
            "dependency_mentions": mentions,
        }
    )


def test_no_mention_is_the_default_in_both_shapes() -> None:
    answer = {"title": "Migrate the billing tables", "has_dependencies": False}

    for shape in (ExtractedFields, ExtractedFieldsWithClassification):
        assert shape.model_validate(answer).dependency_mentions == []


def test_blanks_placeholders_and_repeats_are_dropped() -> None:
    written = ["the DB upgrade", "", "N/A", "  The DB upgrade ", "Dana's pricing review"]

    assert _task(written).dependency_mentions == ["the DB upgrade", "Dana's pricing review"]


@pytest.mark.parametrize(
    ("written", "kept"),
    [
        (["the DB upgrade", 3, None, ["x"]], ["the DB upgrade"]),
        # One mention written without its list: the right answer, written wrong.
        ("the DB upgrade", ["the DB upgrade"]),
        ({"a": 1}, []),
        (None, []),
    ],
)
def test_a_shape_that_is_not_a_list_of_words_never_raises(written: object, kept: list[str]) -> None:
    assert _task(written).dependency_mentions == kept


def test_the_model_alone_does_not_flip_has_dependencies() -> None:
    """The flip needs the content, to check the mention is not invented, so it
    happens at grounding (grounding.ground_dependency_mentions), not here."""

    assert _task(["the DB upgrade"], has_dependencies=False).has_dependencies is False


def test_true_with_no_mention_is_allowed() -> None:
    assert _task([], has_dependencies=True).has_dependencies is True


def test_a_runaway_mention_is_dropped_and_logged(caplog: pytest.LogCaptureFixture) -> None:
    """A phrase as long as a paragraph is an echo of the content, not a name."""

    runaway = "the DB upgrade " * (MAX_TITLE_LENGTH // 10)

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        task = _task(["Dana's pricing review", runaway])

    assert task.dependency_mentions == ["Dana's pricing review"]
    assert "longer than" in caplog.text


def test_past_the_cap_the_first_mentions_are_kept_and_the_rest_logged(
    caplog: pytest.LogCaptureFixture,
) -> None:
    written = [f"the migration step {n}" for n in range(MAX_MENTIONS_PER_TASK + 3)]

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        task = _task(written)

    assert task.dependency_mentions == written[:MAX_MENTIONS_PER_TASK]
    assert "3 more" in caplog.text
