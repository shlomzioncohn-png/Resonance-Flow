"""Turning what the text said about a deadline into a date (#960).

The model does no date arithmetic: it reports what the text says ("October
20", "Friday", "within 72 hours") and the functions here resolve it against
the local date the text was written on. Counting days is where models slip,
and here it is plain code that a test can pin.
"""

from datetime import UTC, date, datetime, time, timedelta
from enum import StrEnum

# A date written without a year is read as already passed only this far back;
# anything earlier is next year's. A message that creates a task looks forward:
# a date missed recently is a real reminder ("the invoice was due September
# 15"), a date months back is history, not live work. About two months, counted
# in days so the window needs no month arithmetic. Not calibrated: the persona
# set's deadlines all fall between 3 days before and 23 days after "now".
MAX_DAYS_PASSED = 60


def complete_year(month: int, day: int, written_on: date) -> date | None:
    """The year a "month and day" deadline falls in, or None if no year fits.

    The answer is the first such day on or after `MAX_DAYS_PASSED` before
    `written_on`: in that year if the day has not yet come by then, otherwise
    in the next. February 29 in a year that has none, or a day no calendar has
    (April 31), is None: the caller treats it as an unusable answer.
    """

    window_start = written_on - timedelta(days=MAX_DAYS_PASSED)
    not_yet_come = (month, day) >= (window_start.month, window_start.day)
    year = window_start.year if not_yet_come else window_start.year + 1
    try:
        return date(year, month, day)
    except ValueError:
        return None


class OffsetUnit(StrEnum):
    """The unit of "in N ..." / "within N ...". Internal: it never reaches Inbox.

    Minutes and weeks are units of their own so the model never multiplies.
    """

    # Counted on the clock, in real elapsed time.
    MINUTES = "MINUTES"
    HOURS = "HOURS"
    # Counted on the calendar, landing at the end of the working day.
    DAYS = "DAYS"
    WEEKS = "WEEKS"
    # Only when the text says "working days" / "business days": they only ever
    # land later than calendar days, the costlier way to be wrong.
    WORKING_DAYS = "WORKING_DAYS"


_ON_THE_CLOCK = {OffsetUnit.MINUTES: timedelta(minutes=1), OffsetUnit.HOURS: timedelta(hours=1)}


def resolve_offset(
    amount: int,
    unit: OffsetUnit,
    written_at: datetime,
    *,
    end_of_day: time,
    working_days: frozenset[int],
) -> datetime | None:
    """The moment "in `amount` `unit`" points to, or None if it is not ahead.

    `written_at` is aware and in the writer's zone; the result is in the same
    zone. `working_days` uses Python's weekday numbers (Monday is 0).
    """

    if amount <= 0:
        return None
    if unit in _ON_THE_CLOCK:
        # Through UTC: adding to a zoned datetime moves the wall clock, which
        # is an hour off across a clock change.
        elapsed = amount * _ON_THE_CLOCK[unit]
        return (written_at.astimezone(UTC) + elapsed).astimezone(written_at.tzinfo)
    if unit is OffsetUnit.WORKING_DAYS:
        day = _working_days_after(written_at.date(), amount, working_days)
    else:
        days = amount if unit is OffsetUnit.DAYS else 7 * amount
        day = written_at.date() + timedelta(days=days)
    return datetime.combine(day, end_of_day, tzinfo=written_at.tzinfo)


def resolve_weekday(
    weekday: int, next_week: bool, written_at: datetime, *, end_of_day: time
) -> datetime:
    """The moment "by Friday" (or "by next Friday") points to.

    The nearest such day, today included: a missed same-day deadline costs more
    than a task that tops the list a week early. `next_week` ("next Friday")
    adds a week to it. `weekday` uses Python's numbers (Monday is 0).
    """

    days_ahead = (weekday - written_at.weekday()) % 7
    if next_week:
        days_ahead += 7
    return _at_end_of_day(written_at.date() + timedelta(days=days_ahead), written_at, end_of_day)


# When the end of the working day has already passed on the day the text was
# written, a deadline "today" goes to the last minute of it instead, so the task
# is not born overdue.
_LAST_MINUTE = time(23, 59)


def _at_end_of_day(day: date, written_at: datetime, end_of_day: time) -> datetime:
    # Only a deadline on the very day of writing can find its working day over.
    is_today = day == written_at.date()
    working_day_over = is_today and written_at.time() >= end_of_day
    at = _LAST_MINUTE if working_day_over else end_of_day
    return datetime.combine(day, at, tzinfo=written_at.tzinfo)


def _working_days_after(start: date, count: int, working_days: frozenset[int]) -> date:
    # With no working day in the week the count never goes down and the loop
    # never ends. That is a settings mistake, so it raises rather than turning
    # into a null deadline nobody would notice.
    if not working_days:
        raise ValueError("the working week has no working day; check the deadline settings")
    day = start
    while count:
        day += timedelta(days=1)
        if day.weekday() in working_days:
            count -= 1
    return day
