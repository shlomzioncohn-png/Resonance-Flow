"""The locale a deadline is resolved in (#960): time zone, end of the working
day, working week. Defaults are Israel's; a tenant abroad changes settings,
not code. A setting that would break resolution fails when it is loaded, not
halfway through an extraction.
"""

from datetime import time
from pathlib import Path

import pytest
from pydantic import ValidationError

from mind.config import DeadlineSettings

_VARIABLES = ("MIND_DEADLINE__TIMEZONE", "MIND_DEADLINE__END_OF_DAY", "MIND_DEADLINE__WORKING_DAYS")


@pytest.fixture
def load(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    """Build the settings from the given variables only: no ambient env, no .env."""

    for name in _VARIABLES:
        monkeypatch.delenv(name, raising=False)

    def build(**variables: str) -> DeadlineSettings:
        for name, value in variables.items():
            monkeypatch.setenv(f"MIND_DEADLINE__{name.upper()}", value)
        return DeadlineSettings(_env_file=tmp_path / "absent.env")

    return build


def test_the_defaults_are_israel(load) -> None:
    settings = load()

    assert settings.zone.key == "Asia/Jerusalem"
    assert settings.end_of_day == time(17, 0)
    # Python's numbers, Monday = 0: Sunday to Thursday.
    assert settings.weekday_numbers == frozenset({6, 0, 1, 2, 3})


def test_a_tenant_abroad_is_a_change_of_settings(load) -> None:
    settings = load(
        timezone="Europe/London",
        end_of_day="18:30",
        working_days='["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"]',
    )

    assert settings.zone.key == "Europe/London"
    assert settings.end_of_day == time(18, 30)
    assert settings.weekday_numbers == frozenset({0, 1, 2, 3, 4})


def test_a_week_that_wraps_past_sunday_is_one_run(load) -> None:
    """Friday to Monday runs from Python's Sunday (6) into Monday (0): still one run."""

    settings = load(working_days='["FRIDAY", "SATURDAY", "SUNDAY", "MONDAY"]')

    assert settings.weekday_numbers == frozenset({4, 5, 6, 0})


def test_day_names_are_read_in_any_case(load) -> None:
    settings = load(working_days='["sunday", "Monday", "TUESDAY", "wednesday", "thursday"]')

    assert settings.weekday_numbers == frozenset({6, 0, 1, 2, 3})


@pytest.mark.parametrize(
    ("variables", "named"),
    [
        # A typo in the zone would otherwise surface on the first deadline.
        ({"timezone": "Asia/Jerusalm"}, "timezone"),
        ({"working_days": '["MONDAY", "FUNDAY"]'}, "working_days"),
        # No working day: nothing to count. Every day a working day: no week
        # ever ends. Either way resolution has nothing to stand on.
        ({"working_days": "[]"}, "working_days"),
        # Gaps: the week would "end" on Monday, the first day before a day off.
        ({"working_days": '["MONDAY", "WEDNESDAY", "FRIDAY"]'}, "working_days"),
        (
            {
                "working_days": '["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", '
                '"FRIDAY", "SATURDAY", "SUNDAY"]'
            },
            "working_days",
        ),
    ],
)
def test_a_setting_that_would_break_resolution_fails_on_load(
    load, variables: dict[str, str], named: str
) -> None:
    with pytest.raises(ValidationError, match=named):
        load(**variables)
