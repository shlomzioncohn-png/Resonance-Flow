"""What the stated deadline accepts, and what it refuses (#960).

The model reports what the content says about when the task is due, in one
of four shapes, and does no arithmetic; resolving it to a moment happens
later. The same line as the other optional fields: an answer that cannot be
used becomes null and is logged, and never costs a repair round or the rest
of the task.
"""

import logging
from datetime import time

import pytest

from mind.extraction.deadline import OffsetUnit, PeriodEnd, Weekday
from mind.extraction.models import (
    ClassificationFields,
    DeadlineKind,
    ExtractedFieldsWithClassification,
)


def _deadline(written: object):
    return ClassificationFields(deadline=written).deadline


def test_no_deadline_is_the_default() -> None:
    assert ClassificationFields().deadline is None


def test_an_explicit_date_with_its_time() -> None:
    deadline = _deadline(
        {
            "phrase": "by October 20 at 14:00",
            "kind": "DATE",
            "month": 10,
            "day": 20,
            "time_of_day": "14:00",
        }
    )

    assert deadline is not None
    assert (deadline.kind, deadline.month, deadline.day, deadline.year) == (
        DeadlineKind.DATE,
        10,
        20,
        None,
    )
    assert deadline.time_of_day == time(14, 0)


def test_an_offset() -> None:
    deadline = _deadline(
        {"phrase": "within 72 hours", "kind": "OFFSET", "amount": 72, "unit": "HOURS"}
    )

    assert deadline is not None
    assert (deadline.amount, deadline.unit) == (72, OffsetUnit.HOURS)


def test_a_weekday_next_week() -> None:
    deadline = _deadline(
        {"phrase": "by next Friday", "kind": "WEEKDAY", "weekday": "FRIDAY", "next_week": True}
    )

    assert deadline is not None
    assert (deadline.weekday, deadline.next_week, deadline.time_of_day) == (
        Weekday.FRIDAY,
        True,
        None,
    )


def test_the_end_of_a_period() -> None:
    deadline = _deadline(
        {"phrase": "by the end of the month", "kind": "PERIOD_END", "period": "MONTH"}
    )

    assert deadline is not None
    assert deadline.period is PeriodEnd.MONTH


def test_the_words_are_read_in_any_case() -> None:
    """`friday` is the right answer written wrong, as with `strategic`."""

    deadline = _deadline({"phrase": "Friday", "kind": "weekday", "weekday": "friday"})

    assert deadline is not None
    assert (deadline.kind, deadline.weekday) == (DeadlineKind.WEEKDAY, Weekday.FRIDAY)


@pytest.mark.parametrize("declined", [None, "null", "N/A", "none", ""])
def test_a_declined_deadline_is_null(declined: object) -> None:
    assert _deadline(declined) is None


@pytest.mark.parametrize(
    "unusable",
    [
        # Not one of the four kinds.
        {"phrase": "soon", "kind": "VAGUE"},
        # A kind without what it needs.
        {"phrase": "within 3", "kind": "OFFSET", "amount": 3},
        {"phrase": "by the 20th", "kind": "DATE", "day": 20},
        {"phrase": "by next week", "kind": "WEEKDAY", "next_week": True},
        {"phrase": "by the end", "kind": "PERIOD_END"},
        # Values no calendar or clock has.
        {"phrase": "by Funday", "kind": "WEEKDAY", "weekday": "FUNDAY"},
        # Beyond what a calendar can count: these raised OverflowError out of
        # assembly and the message came back for ever.
        {"phrase": "within 100000000 days", "kind": "OFFSET", "amount": 100000000, "unit": "DAYS"},
        {"phrase": "in 1001 weeks", "kind": "OFFSET", "amount": 1001, "unit": "WEEKS"},
        {"phrase": "May 1, 10000000000", "kind": "DATE", "month": 5, "day": 1, "year": 10**10},
        {"phrase": "May 1, 1850", "kind": "DATE", "month": 5, "day": 1, "year": 1850},
        {"phrase": "by month 13", "kind": "DATE", "month": 13, "day": 1},
        {"phrase": "in 0 days", "kind": "OFFSET", "amount": 0, "unit": "DAYS"},
        # No phrase: nothing to check the answer against.
        {"kind": "PERIOD_END", "period": "DAY"},
        {"phrase": "", "kind": "PERIOD_END", "period": "DAY"},
        # A key of its own invention.
        {"phrase": "Friday", "kind": "WEEKDAY", "weekday": "FRIDAY", "confidence": 0.8},
        # Not an object at all.
        "Friday",
        ["Friday", "Monday"],
    ],
)
def test_a_deadline_that_cannot_be_used_becomes_null_and_is_logged(
    unusable: object, caplog: pytest.LogCaptureFixture
) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _deadline(unusable) is None

    assert "deadline" in caplog.text


@pytest.mark.parametrize("unreadable", ["5pm", "25:00", "noon", 17])
def test_an_unreadable_time_costs_only_the_time(
    unreadable: object, caplog: pytest.LogCaptureFixture
) -> None:
    """The day the model read is still right: Friday survives, at the end of
    the working day, instead of the whole deadline being lost."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        deadline = _deadline(
            {
                "phrase": "by Friday 5pm",
                "kind": "WEEKDAY",
                "weekday": "FRIDAY",
                "time_of_day": unreadable,
            }
        )

    assert deadline is not None
    assert (deadline.weekday, deadline.time_of_day) == (Weekday.FRIDAY, None)
    assert "time_of_day" in caplog.text


def test_an_unusable_deadline_costs_nothing_else() -> None:
    """The title is required, the deadline optional: losing the second must
    never take the first with it."""

    task = ExtractedFieldsWithClassification(
        title="Sign the term sheet",
        has_dependencies=False,
        tier="TACTICAL",
        deadline={"phrase": "soon", "kind": "VAGUE"},
    )

    assert (task.title, task.tier, task.deadline) == ("Sign the term sheet", "TACTICAL", None)
