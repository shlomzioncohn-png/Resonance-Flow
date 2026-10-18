"""Per-task features, each normalised to 0-1 on its own.

Every function here is pure, deterministic and **tier-agnostic**: a tier's rule
is expressed entirely by which features it uses and what it weighs them at,
never by branching inside the feature itself. That separation is what makes the
three rules genuinely different rather than three names for one formula.
"""

from __future__ import annotations

from datetime import datetime

from mind.scoring.inputs import ScorableTask
from mind.scoring.keywords import IMPACT_BASE, IMPACT_MATCHERS, URGENCY_MATCHERS

#: An effort estimate at or above this many minutes earns no quick-win credit.
#: 120 minutes is roughly the largest `effort_estimate` the persona set uses, so
#: the feature spreads across the real range instead of saturating.
QUICK_WIN_CAP_MINUTES = 120


def task_text(task: ScorableTask) -> str:
    """Title and description as one lowercased blob — what both lexicons read.

    A missing description adds nothing: f"{None}" is the word "none", which
    the lexicons would read as if the task had said it.
    """
    return f"{task.title} {task.description or ''}".lower()


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
    # Inbox's deadlines carry a timezone and the scenarios' do not. Whoever
    # builds the task also builds `now`, and only they know where the times
    # came from, so they convert; this refuses to guess. Not treated as a
    # missing deadline: that would hide the bug and drop the time pressure.
    # Judged by utcoffset(), as Python itself judges it: a tzinfo that gives
    # no offset still makes a naive datetime.
    deadline_naive = deadline.utcoffset() is None
    now_naive = now.utcoffset() is None
    if deadline_naive != now_naive:
        raise ValueError(
            f"deadline is {'naive' if deadline_naive else 'timezone-aware'} "
            f"but now is {'naive' if now_naive else 'timezone-aware'}; "
            f"build both the same way"
        )
    hours_remaining = (deadline - now).total_seconds() / 3600
    if hours_remaining <= 0:
        return 1.0
    return 1.0 / (1.0 + hours_remaining / scale_hours)


def stated_urgency(task: ScorableTask) -> float:
    """Time pressure asserted in words rather than in the `deadline` field.

    Taken as the **max** over matched phrases: several ways of saying a system
    is on fire still describe one fire, and summing them would let wording
    volume outrank an actual timestamp.
    """
    text = task_text(task)
    matched = [weight for pattern, weight in URGENCY_MATCHERS if pattern.search(text)]
    return max(matched, default=0.0)


def time_pressure(
    task: ScorableTask,
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


def impact(task: ScorableTask) -> float:
    """How much this task matters, from the words of the task itself.

    Summed onto `IMPACT_BASE` and clamped: impact genuinely accumulates (a
    customer-facing outage with an SLA attached is worse than either signal
    alone), while the neutral base keeps a task nobody wrote keywords about from
    reading as worthless.
    """
    text = task_text(task)
    total = IMPACT_BASE + sum(w for pattern, w in IMPACT_MATCHERS if pattern.search(text))
    return min(1.0, max(0.0, total))


def quick_win(task: ScorableTask) -> float:
    """Shorter tasks score higher, on a 0-1 scale.

    Deliberately the weakest feature in every rule. `effort_estimate` belongs to
    Chain's constraint-satisfaction step, and `eval/personas/README.md` forbids
    a scenario's correct answer from hinging on it, so it must never be able to
    decide an ordering on its own — only to break a near-tie.

    A missing estimate earns nothing: the bonus rewards a shortness we know
    about, and without an estimate there is no evidence of one. That is the
    same 0.0 a task past the cap gets, so a missing estimate is never a
    penalty, only the absence of a bonus.
    """
    if task.effort_estimate is None:
        return 0.0
    capped = min(task.effort_estimate, QUICK_WIN_CAP_MINUTES)
    return 1.0 - capped / QUICK_WIN_CAP_MINUTES
