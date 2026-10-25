"""The #359 demo scenario: where its files live and when it happens.

Every time in the scenario is on one Monday, SCENARIO_MONDAY. A run moves the
whole timeline by whole weeks onto another Monday (its anchor), so a deadline
said as a weekday ("by Thursday") stays the same weekday of the same week as
the meeting, whichever week the demo runs in.
"""

import json
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import UUID

DEMO_DIR = Path(__file__).resolve().parent
SCENARIO_DIR = DEMO_DIR / "scenario"
ARTIFACTS_DIR = DEMO_DIR / "artifacts"
RUNS_DIR = DEMO_DIR / "runs"

MEETING_SCRIPT = SCENARIO_DIR / "meeting_script.txt"
# Zoom's Report API meeting report, the `<basename>.meeting.json` sidecar Bridge's
# media lane lands next to a Zoom recording.
MEETING_METADATA = SCENARIO_DIR / "meeting.meeting.json"
SLACK_THREAD = SCENARIO_DIR / "slack_thread.json"
EMAIL = SCENARIO_DIR / "email.json"
FORM_TEXT = SCENARIO_DIR / "supplier_form.txt"
EXPECTED = DEMO_DIR / "expected.json"

MEETING_AUDIO = ARTIFACTS_DIR / "meeting.m4a"
# Same name as the attachment in email.json: the feed looks it up by that name.
FORM_IMAGE = ARTIFACTS_DIR / "supplier_form.jpg"

SCENARIO_MONDAY = date(2026, 10, 5)

# Default tenant for events (overridable via --tenant-id or DEMO_TENANT_ID).
# Uses Inbox's built-in "System" tenant (migration V15) to satisfy Mind's
# UUID requirement and Inbox's foreign key constraints.
DEFAULT_TENANT_ID = UUID("00000000-0000-0000-0000-000000000001")


def _at(hour: int, minute: int) -> datetime:
    return datetime(
        SCENARIO_MONDAY.year, SCENARIO_MONDAY.month, SCENARIO_MONDAY.day, hour, minute, tzinfo=UTC
    )


# When Bridge lands each item. The Slack and email times inside their own files
# (`ts`, `date`) are when they were sent; the meeting has none, as Bridge's
# media lane sends no source_timestamp.
MEETING_INGESTED_AT = _at(9, 35)
SLACK_INGESTED_AT = _at(9, 45)
EMAIL_INGESTED_AT = _at(11, 16)

_WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def most_recent_monday(today: date) -> date:
    return today - timedelta(days=today.weekday())


def week_shift(anchor: date) -> timedelta:
    """How far to move the scenario's timeline so its Monday lands on `anchor`."""
    if anchor.weekday() != 0:
        raise ValueError(f"anchor must be a Monday, got {anchor} ({_WEEKDAYS[anchor.weekday()]})")
    return anchor - SCENARIO_MONDAY


def weekday_in_week(anchor: date, weekday: str) -> date:
    """The date of `weekday` in the week that starts on `anchor` (a Monday)."""
    return anchor + timedelta(days=_WEEKDAYS.index(weekday))


def read_meeting_script() -> list[tuple[str, str]]:
    """(speaker, text) per utterance of meeting_script.txt."""
    lines = []
    for raw in MEETING_SCRIPT.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        speaker, _, text = line.partition(":")
        lines.append((speaker.strip(), text.strip()))
    return lines


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))
