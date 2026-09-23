"""Per-task features, each normalised to 0-1 on its own.

Every function here is pure, deterministic and **tier-agnostic**: a tier's rule
is expressed entirely by which features it uses and what it weighs them at,
never by branching inside the feature itself. That separation is what makes the
three rules genuinely different rather than three names for one formula.
"""

from __future__ import annotations

from datetime import datetime

from mind.evaluation.interfaces import Task
from mind.scoring.keywords import IMPACT_BASE, IMPACT_PHRASES, URGENCY_PHRASES

#: An effort estimate at or above this many minutes earns no quick-win credit.
#: 120 minutes is roughly the largest `effort_estimate` the persona set uses, so
#: the feature spreads across the real range instead of saturating.
QUICK_WIN_CAP_MINUTES = 120


def task_text(task: Task) -> str:
    """Title and description as one lowercased blob — what both lexicons read."""
    return f"{task.title} {task.description}".lower()


def deadline_proximity(
    deadline: datetime | None,
    now: datetime,
    scale_hours: float,
    missing_value: float,
) -> float:
    """How close `deadline` is, on a 0-1 scale, under a tier's own sense of time.

    `scale_hours` is the point at which a deadline is judged half-pressing, so
    the same curve serves all three tiers with one parameter: hours for
    operational work, days for tactical, weeks for strategic.

    An **already-passed** deadline returns the maximum. Overdue is not by itself
    a reason to deprioritise — whether an overdue task still matters is decided
    by `impact`, since the persona set deliberately contains overdue tasks that
    pull in both directions.

    A **missing** deadline returns `missing_value`, never 0.0: at strategic tier
    most real work carries no date, so absence of a date is not evidence of
    unimportance. Each tier states its own reading of the absence.
    """
    if deadline is None:
        return missing_value
    hours_remaining = (deadline - now).total_seconds() / 3600
    if hours_remaining <= 0:
        return 1.0
    return 1.0 / (1.0 + hours_remaining / scale_hours)


def stated_urgency(task: Task) -> float:
    """Time pressure asserted in words rather than in the `deadline` field.

    Taken as the **max** over matched phrases: several ways of saying a system
    is on fire still describe one fire, and summing them would let wording
    volume outrank an actual timestamp.
    """
    text = task_text(task)
    matched = [weight for phrase, weight in URGENCY_PHRASES.items() if phrase in text]
    return max(matched, default=0.0)


def time_pressure(
    task: Task,
    now: datetime,
    scale_hours: float,
    missing_value: float,
) -> float:
    """The stronger of the dated and the stated urgency.

    A live incident with `deadline = None` ("their production has been down for
    30 minutes") is more time-critical than a dated chore, and the dated
    reading alone would rank it below one. Taking the max lets either source
    establish urgency without letting a task collect credit twice.
    """
    dated = deadline_proximity(task.deadline, now, scale_hours, missing_value)
    return max(dated, stated_urgency(task))


def impact(task: Task) -> float:
    """How much this task matters, from the words of the task itself.

    Summed onto `IMPACT_BASE` and clamped: impact genuinely accumulates (a
    customer-facing outage with an SLA attached is worse than either signal
    alone), while the neutral base keeps a task nobody wrote keywords about from
    reading as worthless.
    """
    text = task_text(task)
    total = IMPACT_BASE + sum(w for phrase, w in IMPACT_PHRASES.items() if phrase in text)
    return min(1.0, max(0.0, total))


def quick_win(task: Task) -> float:
    """Shorter tasks score higher, on a 0-1 scale.

    Deliberately the weakest feature in every rule. `effort_estimate` belongs to
    Chain's constraint-satisfaction step, and `eval/personas/README.md` forbids
    a scenario's correct answer from hinging on it, so it must never be able to
    decide an ordering on its own — only to break a near-tie.
    """
    capped = min(task.effort_estimate, QUICK_WIN_CAP_MINUTES)
    return 1.0 - capped / QUICK_WIN_CAP_MINUTES


def belongs_to_initiative(task: Task) -> float:
    """1.0 when the task hangs under a parent task, 0.0 when it stands alone.

    `parent_task_id` points *outside* the scenario, so the parent cannot be
    looked up: the only readable signal is whether a parent exists at all —
    weak evidence that this sits inside a larger initiative. Walking the chain
    is R3's week-7 stretch and is deliberately not done here.

    In the current persona set this field is `null` everywhere, so this feature
    never fires. It exists so the behaviour is defined rather than accidental.
    """
    return 0.0 if task.parent_task_id is None else 1.0
