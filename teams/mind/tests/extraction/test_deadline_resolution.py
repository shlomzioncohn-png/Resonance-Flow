"""Turning what the text said about a deadline into a date (#960).

The model reports what it read and does no arithmetic; these functions do it,
against the local date the text was written on.
"""

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from mind.extraction.deadline import OffsetUnit, complete_year, resolve_offset, resolve_weekday

WRITTEN_ON = date(2026, 10, 22)  # a Thursday

ISRAEL = ZoneInfo("Asia/Jerusalem")
END_OF_DAY = time(17, 0)
# Python numbers weekdays from Monday = 0.
SUNDAY_TO_THURSDAY = frozenset({6, 0, 1, 2, 3})
MONDAY_TO_FRIDAY = frozenset({0, 1, 2, 3, 4})

# Thursday morning in Israel, three days before the clocks go back (October 25).
WRITTEN_AT = datetime(2026, 10, 22, 10, 0, tzinfo=ISRAEL)


def test_a_date_a_few_days_back_is_read_as_already_passed() -> None:
    """Written on the 22nd, "by October 20" was missed; it is not a year away."""

    assert complete_year(10, 20, WRITTEN_ON) == date(2026, 10, 20)


def test_a_date_months_back_is_read_as_next_year() -> None:
    """Written in October, "by June 1" plans ahead; it is not five months overdue."""

    assert complete_year(6, 1, WRITTEN_ON) == date(2027, 6, 1)


def test_a_date_later_this_year_stays_this_year() -> None:
    assert complete_year(12, 1, WRITTEN_ON) == date(2026, 12, 1)


def test_the_day_it_was_written_is_that_day() -> None:
    assert complete_year(10, 22, WRITTEN_ON) == WRITTEN_ON


@pytest.mark.parametrize(
    ("month", "day", "written_on", "expected"),
    [
        # Forward over New Year: January written in late December.
        (1, 5, date(2026, 12, 20), date(2027, 1, 5)),
        # Back over New Year: a December date missed, written in January.
        (12, 1, date(2027, 1, 10), date(2026, 12, 1)),
    ],
)
def test_the_year_boundary_is_crossed_both_ways(
    month: int, day: int, written_on: date, expected: date
) -> None:
    assert complete_year(month, day, written_on) == expected


def test_the_past_window_ends_exactly_sixty_days_back() -> None:
    """Sixty days back is still passed; one day more and it is next year."""

    assert complete_year(8, 23, WRITTEN_ON) == date(2026, 8, 23)  # 60 days back
    assert complete_year(8, 22, WRITTEN_ON) == date(2027, 8, 22)  # 61 days back


def test_february_29_goes_to_the_leap_year_inside_the_window() -> None:
    """Written in December 2027: 2027 has no 29th, 2028's falls in the window."""

    assert complete_year(2, 29, date(2027, 12, 20)) == date(2028, 2, 29)


def test_february_29_just_past_the_window_end_is_unusable() -> None:
    """Written in March 2027, the window ends in January 2028, before the 29th."""

    assert complete_year(2, 29, date(2027, 3, 10)) is None


def test_february_29_with_no_leap_year_in_the_window_is_unusable() -> None:
    """From June 2026 the window ends in June 2027; neither year has a 29th."""

    assert complete_year(2, 29, date(2026, 6, 1)) is None


def test_a_window_that_starts_on_february_29_works_like_any_other() -> None:
    """Written on 2028-04-29, sixty days back is February 29 itself."""

    written_on = date(2028, 4, 29)

    assert complete_year(2, 29, written_on) == date(2028, 2, 29)
    assert complete_year(2, 28, written_on) == date(2029, 2, 28)
    assert complete_year(3, 1, written_on) == date(2028, 3, 1)


@pytest.mark.parametrize(("month", "day"), [(4, 31), (13, 1), (0, 5), (10, 0), (10, 32)])
def test_a_day_no_calendar_has_is_unusable(month: int, day: int) -> None:
    assert complete_year(month, day, WRITTEN_ON) is None


def _offset(
    amount: int, unit: OffsetUnit, working_days: frozenset[int] = SUNDAY_TO_THURSDAY
) -> datetime | None:
    return resolve_offset(
        amount, unit, WRITTEN_AT, end_of_day=END_OF_DAY, working_days=working_days
    )


@pytest.mark.parametrize(
    ("amount", "unit", "expected"),
    [
        (30, OffsetUnit.MINUTES, datetime(2026, 10, 22, 10, 30, tzinfo=ISRAEL)),
        (3, OffsetUnit.HOURS, datetime(2026, 10, 22, 13, 0, tzinfo=ISRAEL)),
    ],
)
def test_minutes_and_hours_are_counted_on_the_clock(
    amount: int, unit: OffsetUnit, expected: datetime
) -> None:
    assert _offset(amount, unit) == expected


def test_hours_are_real_hours_across_a_clock_change() -> None:
    """From Thursday 10:00, "within 72 hours" crosses the night the clocks go back.

    Real elapsed time lands at 09:00 on Sunday; adding 72 hours to the wall
    clock would say 10:00, an hour too late.
    """

    deadline = _offset(72, OffsetUnit.HOURS)

    assert deadline == datetime(2026, 10, 25, 9, 0, tzinfo=ISRAEL)
    assert deadline is not None
    assert deadline.astimezone(UTC) - WRITTEN_AT.astimezone(UTC) == timedelta(hours=72)


@pytest.mark.parametrize(
    ("amount", "unit", "expected_day"),
    [
        # Calendar days: Thursday + 3 is Sunday, the weekend counts.
        (3, OffsetUnit.DAYS, date(2026, 10, 25)),
        (2, OffsetUnit.WEEKS, date(2026, 11, 5)),
        # Working days only when the text says so: Sunday, Monday, Tuesday.
        (3, OffsetUnit.WORKING_DAYS, date(2026, 10, 27)),
    ],
)
def test_days_and_weeks_land_at_the_end_of_the_working_day(
    amount: int, unit: OffsetUnit, expected_day: date
) -> None:
    assert _offset(amount, unit) == datetime.combine(expected_day, END_OF_DAY, tzinfo=ISRAEL)


def test_working_days_follow_the_configured_week() -> None:
    """One working day from a Thursday: Sunday in Israel, Friday elsewhere."""

    in_israel = _offset(1, OffsetUnit.WORKING_DAYS, SUNDAY_TO_THURSDAY)
    abroad = _offset(1, OffsetUnit.WORKING_DAYS, MONDAY_TO_FRIDAY)

    assert in_israel == datetime(2026, 10, 25, 17, 0, tzinfo=ISRAEL)
    assert abroad == datetime(2026, 10, 23, 17, 0, tzinfo=ISRAEL)


def test_a_week_with_no_working_days_is_refused_not_counted_forever() -> None:
    """A settings mistake, not a model one: raise instead of looping without end."""

    with pytest.raises(ValueError, match="working day"):
        _offset(3, OffsetUnit.WORKING_DAYS, frozenset())


@pytest.mark.parametrize("amount", [0, -2])
def test_an_offset_that_is_not_ahead_is_unusable(amount: int) -> None:
    assert _offset(amount, OffsetUnit.DAYS) is None
    assert _offset(amount, OffsetUnit.HOURS) is None


MONDAY, WEDNESDAY, THURSDAY, FRIDAY = 0, 2, 3, 4


def _weekday(
    weekday: int, *, next_week: bool = False, written_at: datetime = WRITTEN_AT
) -> datetime:
    return resolve_weekday(weekday, next_week, written_at, end_of_day=END_OF_DAY)


def test_a_weekday_later_this_week_is_this_week() -> None:
    assert _weekday(FRIDAY) == datetime(2026, 10, 23, 17, 0, tzinfo=ISRAEL)


def test_a_weekday_already_gone_this_week_is_next_week() -> None:
    assert _weekday(WEDNESDAY) == datetime(2026, 10, 28, 17, 0, tzinfo=ISRAEL)


def test_the_weekday_it_was_written_on_is_today() -> None:
    """Written on a Thursday, "by Thursday" is today: a missed same-day deadline
    is the costlier mistake than a task that tops the list a week early."""

    assert _weekday(THURSDAY) == datetime(2026, 10, 22, 17, 0, tzinfo=ISRAEL)


def test_today_after_the_end_of_the_working_day_is_the_last_minute_of_today() -> None:
    """Written at 18:00, 17:00 has passed: the task must not be born overdue."""

    evening = datetime(2026, 10, 22, 18, 0, tzinfo=ISRAEL)

    assert _weekday(THURSDAY, written_at=evening) == datetime(2026, 10, 22, 23, 59, tzinfo=ISRAEL)


@pytest.mark.parametrize(
    ("weekday", "expected_day"),
    [(THURSDAY, date(2026, 10, 29)), (FRIDAY, date(2026, 10, 30)), (WEDNESDAY, date(2026, 11, 4))],
)
def test_next_adds_a_week_to_the_nearest_one(weekday: int, expected_day: date) -> None:
    assert _weekday(weekday, next_week=True) == datetime.combine(
        expected_day, END_OF_DAY, tzinfo=ISRAEL
    )


def test_next_matches_the_persona_gold() -> None:
    """Written on Monday 2026-09-21, "next Wednesday" is gold Wednesday the 30th."""

    monday = datetime(2026, 9, 21, 9, 0, tzinfo=ISRAEL)

    assert _weekday(WEDNESDAY, next_week=True, written_at=monday).date() == date(2026, 9, 30)


def test_the_last_minute_rule_is_for_today_only() -> None:
    """Written Thursday at 18:00, "next Thursday" is a week ahead: still 17:00."""

    evening = datetime(2026, 10, 22, 18, 0, tzinfo=ISRAEL)

    assert _weekday(THURSDAY, next_week=True, written_at=evening) == datetime(
        2026, 10, 29, 17, 0, tzinfo=ISRAEL
    )
