"""A live-path task and its clock, shared by the scoring tests.

One definition, so the tests that score a `LiveTask` and the tests that
explain one cannot drift onto different clocks.
"""

from dataclasses import dataclass
from datetime import datetime, timedelta

from mind.evaluation.interfaces import ScoreContext, Tier

NOW = datetime(2026, 9, 7, 9, 0)  # noqa: DTZ001
CONTEXT = ScoreContext(current_datetime=NOW)


@dataclass(frozen=True)
class LiveTask:
    """What the live path builds from an Inbox task: some fields may be empty."""

    task_id: str = "0f8c2a51-6d2e-4b1a-9c3e-7a5d1e2b4c60"  # an Inbox UUID, as a string
    title: str = "Do the thing"
    description: str | None = "A task with no particular signals in its wording."
    # A day and a bit out: near enough that each tier's sense of time reads it
    # differently, which is what makes the tiers score it apart.
    deadline: datetime | None = NOW + timedelta(hours=30)
    effort_estimate: int | None = 30
    tier: Tier | None = "tactical"
    blocked: bool = False
    parent_task_id: str | None = None
