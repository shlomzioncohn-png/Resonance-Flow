"""What `dependency_mentions` accepts, and what it refuses (#690, was #842).

Optional, like the classification fields: an item that cannot be used is
dropped and logged, and never costs a repair round. Whatever survives reaches
#834's resolver, where one empty mention used to stall the whole listener.
"""

import logging

import pytest

from mind.extraction.models import ExtractedFields, ExtractedFieldsWithClassification


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


def test_a_mention_makes_has_dependencies_true_and_says_so(
    caplog: pytest.LogCaptureFixture,
) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        task = _task(["the DB upgrade"], has_dependencies=False)

    assert task.has_dependencies is True
    assert "dependency_mentions" in caplog.text


def test_mentions_that_were_all_placeholders_do_not_flip_it() -> None:
    task = _task(["", "N/A"], has_dependencies=False)

    assert (task.dependency_mentions, task.has_dependencies) == ([], False)


def test_true_with_no_mention_is_allowed() -> None:
    assert _task([], has_dependencies=True).has_dependencies is True
