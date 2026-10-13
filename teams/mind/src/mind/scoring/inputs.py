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
    """The seven fields the ranking model reads, and nothing else.

    A Protocol, so anything with these fields fits without inheriting from
    anything: R1's scenario Task as it is, and whatever the live path builds
    from Inbox's Task. Read-only properties rather than plain attributes, and
    that is what lets "always filled" fit "may be empty" — mypy only accepts a
    narrower type in place of a wider one for a field nobody can write to.
    """

    @property
    def title(self) -> str: ...

    # Empty on a real task more often than not; required only in the scenarios.
    @property
    def description(self) -> str | None: ...

    @property
    def deadline(self) -> datetime | None: ...

    # Empty when the content gave no basis for one (#352).
    @property
    def effort_estimate(self) -> int | None: ...

    # Empty when the content gave no sense of timescale (#351).
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
