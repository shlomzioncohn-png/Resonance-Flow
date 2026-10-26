"""From what the model reported to the contract's `deadline` (#960).

The stated deadline is resolved against when the text was written, in the
configured zone, and goes out in UTC like every other time on the wire. A
year or a time the content wrote is used as written; a date no calendar has
is null and logged.
"""

import logging
from datetime import UTC, datetime
from uuid import UUID

import pytest
from tests.extraction.fakes import DEADLINE_SETTINGS, WRITTEN_AT, ScriptedProvider, answer_of

from mind.contracts.base_enums import SourceType
from mind.extraction.assembly import resolve_deadline, to_task_creation_requested
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess
from mind.extraction.models import StatedDeadline

# WRITTEN_AT is Thursday 2026-10-22, 10:00 in Israel (UTC+3 until the 25th).


def _utc(*parts: int) -> datetime:
    return datetime(*parts, tzinfo=UTC)


def _resolved(stated: dict[str, object], written_at: datetime = WRITTEN_AT) -> datetime | None:
    return resolve_deadline(StatedDeadline.model_validate(stated), written_at, DEADLINE_SETTINGS)


@pytest.mark.parametrize(
    ("stated", "expected"),
    [
        # Friday 17:00 in Israel.
        ({"phrase": "by Friday", "kind": "WEEKDAY", "weekday": "FRIDAY"}, _utc(2026, 10, 23, 14)),
        # A time the content names replaces the end of the working day.
        (
            {
                "phrase": "Friday 10:00",
                "kind": "WEEKDAY",
                "weekday": "FRIDAY",
                "time_of_day": "10:00",
            },
            _utc(2026, 10, 23, 7),
        ),
        # No year: two days back is passed (decision 2).
        ({"phrase": "October 20", "kind": "DATE", "month": 10, "day": 20}, _utc(2026, 10, 20, 14)),
        # A year the content writes is used as written.
        (
            {"phrase": "October 20, 2027", "kind": "DATE", "month": 10, "day": 20, "year": 2027},
            _utc(2027, 10, 20, 14),
        ),
        # November is winter time: 14:00 in Israel is 12:00 UTC.
        (
            {
                "phrase": "November 3 at 14:00",
                "kind": "DATE",
                "month": 11,
                "day": 3,
                "time_of_day": "14:00",
            },
            _utc(2026, 11, 3, 12),
        ),
        # Real hours across the clock change on the 25th.
        (
            {"phrase": "within 72 hours", "kind": "OFFSET", "amount": 72, "unit": "HOURS"},
            _utc(2026, 10, 25, 7),
        ),
        # October 31 is a Saturday: Thursday the 29th, after the clocks went
        # back, so 17:00 in Israel is 15:00 UTC.
        (
            {"phrase": "end of the month", "kind": "PERIOD_END", "period": "MONTH"},
            _utc(2026, 10, 29, 15),
        ),
        # A time with no day is today at that time: the DEV run's commonest miss.
        (
            {"phrase": "by 12:30", "kind": "PERIOD_END", "period": "DAY", "time_of_day": "12:30"},
            _utc(2026, 10, 22, 9, 30),
        ),
        # A named time on a day offset replaces the end of the working day.
        (
            {
                "phrase": "by 09:00 tomorrow",
                "kind": "OFFSET",
                "amount": 1,
                "unit": "DAYS",
                "time_of_day": "09:00",
            },
            _utc(2026, 10, 23, 6),
        ),
        # On a clock offset a time means nothing more: 3 hours is 3 hours.
        (
            {
                "phrase": "within 3 hours",
                "kind": "OFFSET",
                "amount": 3,
                "unit": "HOURS",
                "time_of_day": "17:00",
            },
            _utc(2026, 10, 22, 10),
        ),
    ],
)
def test_each_kind_reaches_a_moment_in_utc(stated: dict[str, object], expected: datetime) -> None:
    resolved = _resolved(stated)

    assert resolved == expected
    assert resolved is not None and resolved.utcoffset() == UTC.utcoffset(None)


THURSDAY_AFTERNOON = datetime(2026, 10, 22, 12, 0, tzinfo=UTC)  # 15:00 in Israel


def test_a_weekday_time_already_gone_today_is_next_weeks() -> None:
    """ "by Thursday 10:00" written on Thursday at 15:00 cannot mean five hours ago."""

    stated = {
        "phrase": "by Thursday 10:00",
        "kind": "WEEKDAY",
        "weekday": "THURSDAY",
        "time_of_day": "10:00",
    }

    # The 29th is after the clocks go back: 10:00 in Israel is 08:00 UTC.
    assert _resolved(stated, THURSDAY_AFTERNOON) == _utc(2026, 10, 29, 8)


@pytest.mark.parametrize(
    ("stated", "expected"),
    [
        # The week comes round again: next week's Thursday, November 5, at 12:00.
        (
            {
                "phrase": "by the end of the week, 12:00",
                "kind": "PERIOD_END",
                "period": "WEEK",
                "time_of_day": "12:00",
            },
            _utc(2026, 11, 5, 10),
        ),
        # The month is not over yet: its last day, Saturday the 31st, at 12:00.
        (
            {
                "phrase": "by the end of the month, 12:00",
                "kind": "PERIOD_END",
                "period": "MONTH",
                "time_of_day": "12:00",
            },
            _utc(2026, 10, 31, 10),
        ),
    ],
)
def test_a_period_end_time_already_gone_today_points_ahead(
    stated: dict[str, object], expected: datetime
) -> None:
    """Written on the Thursday that ends both the week and October's working days."""

    written = datetime(2026, 10, 29, 13, 0, tzinfo=UTC)  # 15:00 in Israel, winter time

    assert _resolved(stated, written) == expected


@pytest.mark.parametrize(
    ("stated", "expected"),
    [
        # Tonight, not twelve hours ago.
        (
            {
                "phrase": "by midnight",
                "kind": "PERIOD_END",
                "period": "DAY",
                "time_of_day": "00:00",
            },
            _utc(2026, 10, 22, 20, 59),
        ),
        # The end of tomorrow, not its first minute.
        (
            {
                "phrase": "by midnight tomorrow",
                "kind": "OFFSET",
                "amount": 1,
                "unit": "DAYS",
                "time_of_day": "00:00",
            },
            _utc(2026, 10, 23, 20, 59),
        ),
    ],
)
def test_midnight_written_as_00_00_is_the_end_of_that_day(
    stated: dict[str, object], expected: datetime
) -> None:
    assert _resolved(stated) == expected


def test_a_time_today_already_gone_stays_missed() -> None:
    """ "by 12:30" written at 15:00 was missed; only a weekday has another week."""

    stated = {"phrase": "by 12:30", "kind": "PERIOD_END", "period": "DAY", "time_of_day": "12:30"}

    assert _resolved(stated, THURSDAY_AFTERNOON) == _utc(2026, 10, 22, 9, 30)


def test_today_is_the_writers_today_not_utcs() -> None:
    """01:30 on the 22nd in Israel is still the 21st in UTC (decision 3)."""

    after_midnight = datetime(2026, 10, 21, 22, 30, tzinfo=UTC)

    resolved = _resolved({"phrase": "today", "kind": "PERIOD_END", "period": "DAY"}, after_midnight)

    assert resolved == _utc(2026, 10, 22, 14)


@pytest.mark.parametrize(
    "stated",
    [
        {"phrase": "February 30, 2027", "kind": "DATE", "month": 2, "day": 30, "year": 2027},
        {"phrase": "April 31", "kind": "DATE", "month": 4, "day": 31},
        # No February 29 between August 2026 and August 2027.
        {"phrase": "February 29", "kind": "DATE", "month": 2, "day": 29},
    ],
)
def test_a_date_no_calendar_has_is_null_and_logged(
    stated: dict[str, object], caplog: pytest.LogCaptureFixture
) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _resolved(stated) is None

    assert "deadline" in caplog.text


TEXT = "Please send the Q3 revenue report to finance by Friday."


def _extracted(deadline: object, *, classify: bool = True) -> ExtractionSuccess:
    task: dict[str, object] = {
        "title": "Send the Q3 revenue report to finance",
        "description": None,
        "source_ref": None,
        "has_dependencies": False,
    }
    if classify:
        task["deadline"] = deadline
    outcome = BasicExtractor(
        ScriptedProvider(answer_of(task)),
        ExtractionConfig(classify=classify),
        sleep=lambda _: None,
    ).extract(TEXT, SourceType.EMAIL)
    assert isinstance(outcome, ExtractionSuccess)
    return outcome


def _deadline_sent(outcome: ExtractionSuccess) -> datetime | None:
    [proposal] = to_task_creation_requested(
        outcome,
        tenant_id=UUID("11111111-1111-1111-1111-111111111111"),
        bridge_source_ref=None,
        bridge_correlation_id=UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83"),
        written_at=WRITTEN_AT,
        deadline_settings=DEADLINE_SETTINGS,
    )
    return proposal.deadline


def test_the_resolved_deadline_reaches_the_contract() -> None:
    stated = {"phrase": "by Friday", "kind": "WEEKDAY", "weekday": "FRIDAY"}

    assert _deadline_sent(_extracted(stated)) == _utc(2026, 10, 23, 14)


def test_no_deadline_reaches_the_contract_as_none() -> None:
    assert _deadline_sent(_extracted(None)) is None


def test_with_the_switch_off_there_is_no_deadline_to_send() -> None:
    assert _deadline_sent(_extracted(None, classify=False)) is None


def test_an_invented_phrase_never_reaches_the_contract() -> None:
    """Grounding runs in extraction, before assembly sees the task (piece 4)."""

    invented = {"phrase": "by Monday", "kind": "WEEKDAY", "weekday": "MONDAY"}

    assert _deadline_sent(_extracted(invented)) is None
