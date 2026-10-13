"""What the ranking model reads from a task, and which of it may be missing.

The model used to take the persona scenario `Task` from `mind.evaluation`,
where every field it reads is required. A real task is not like that: Inbox's
canonical Task allows an empty tier, effort estimate and description, and
extraction leaves them empty on purpose when the content gives no basis.
"""

from __future__ import annotations

from datetime import datetime
from typing import Protocol

from mind.evaluation.interfaces import Task, Tier


class ScorableTask(Protocol):
    """The fields the ranking model reads, and the id that names the task.

    A Protocol, so anything with these fields fits without inheriting from
    anything: R1's scenario Task as it is, and whatever the live path builds
    from Inbox's Task. Read-only properties rather than plain attributes, and
    that is what lets "always filled" fit "may be empty" — mypy only accepts a
    narrower type in place of a wider one for a field nobody can write to.
    """

    # Not read by the score. Here so the score can be traced to its task, and
    # because `parent_task_id` points at an id: walking from a parent to its
    # children (R3, week 7) needs the task's own. A string: R1's scenario ids
    # and Inbox's UUIDs (converted) both fit.
    @property
    def task_id(self) -> str: ...

    @property
    def title(self) -> str: ...

    # Empty on a real task more often than not; required only in the scenarios.
    @property
    def description(self) -> str | None: ...

    # Naive or timezone-aware, but the same kind as the context's
    # `current_datetime`: Inbox's deadlines are aware, the scenarios' naive.
    # Whoever builds the task builds `now` too, and converts; a mismatch fails.
    @property
    def deadline(self) -> datetime | None: ...

    # Minutes. Empty when the content gave no basis for one (#352). Inbox
    # calls the same value `effort_estimate_minutes`.
    @property
    def effort_estimate(self) -> int | None: ...

    # Empty when the content gave no sense of timescale (#351). Lowercase,
    # as `TIER_RULES` keys it: Inbox writes "OPERATIONAL", and a tier passed
    # through unconverted is a KeyError on every task that has one.
    @property
    def tier(self) -> Tier | None: ...

    @property
    def blocked(self) -> bool: ...

    @property
    def parent_task_id(self) -> str | None: ...


def _a_scenario_task_is_scorable(task: Task) -> ScorableTask:
    """Checked by mypy, never called: the harness must keep working unchanged.

    A scenario task fills every field, and "always filled" has to fit "may
    be empty". If R1's Task ever stops fitting this shape, `mypy src` fails
    here rather than the harness failing at run time.
    """

    return task
