"""A deadline travels only if its phrase is in the content (#960).

The same check as dependency mentions, plus the numbers: in a deadline the
number is the answer, and the word check drops it ("72", "20", "14:00" are
too short to count as words). It catches an invented deadline, not one taken
from another event the content really mentions.
"""

import logging

import pytest

from mind.extraction.grounding import ground_deadline
from mind.extraction.models import ExtractedFields, ExtractedFieldsWithClassification

TEXT = (
    "Hi, the vendor agreement lapses on October 20, so legal needs the signed "
    "copy back within 72 hours. Ideally before the 14:00 call on Friday."
)


def _task(phrase: str) -> ExtractedFieldsWithClassification:
    return ExtractedFieldsWithClassification(
        title="Sign the vendor agreement",
        has_dependencies=False,
        deadline={"phrase": phrase, "kind": "PERIOD_END", "period": "DAY"},
    )


@pytest.mark.parametrize(
    "phrase",
    [
        "within 72 hours",
        "October 20",
        "on October 20",
        "before the 14:00 call on Friday",
        # Case and the words around a number do not matter.
        "by friday",
    ],
)
def test_a_phrase_the_content_says_keeps_its_deadline(phrase: str) -> None:
    task = _task(phrase)

    assert ground_deadline(task, TEXT) is task


@pytest.mark.parametrize(
    "phrase",
    [
        # A word the content never says.
        "by Monday",
        # The right words, the wrong number: the number is the answer.
        "within 48 hours",
        "October 25",
        # "72" is in "720"? No: numbers match whole.
        "within 7 hours",
        # Nothing to check: names no word and no number.
        "by then",
    ],
)
def test_a_phrase_the_content_does_not_say_loses_its_deadline(
    phrase: str, caplog: pytest.LogCaptureFixture
) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        grounded = ground_deadline(_task(phrase), TEXT)

    assert grounded.deadline is None
    assert grounded.title == "Sign the vendor agreement"
    assert "deadline" in caplog.text
    # The phrase is the content's own words; the log does not carry them.
    assert phrase not in caplog.text


def test_a_leading_zero_is_the_same_number() -> None:
    """ "by 9:00" in a text that writes 09:00."""

    task = _task("by 9:00")

    assert ground_deadline(task, "Please have it ready by 09:00 tomorrow.") is task


def test_a_task_without_a_deadline_or_without_the_field_is_left_alone() -> None:
    no_deadline = ExtractedFieldsWithClassification(title="Sign it", has_dependencies=False)
    switch_off = ExtractedFields(title="Sign it", has_dependencies=False)

    assert ground_deadline(no_deadline, TEXT) is no_deadline
    assert ground_deadline(switch_off, TEXT) is switch_off
