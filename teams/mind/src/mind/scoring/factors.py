"""The fixed vocabulary of factor names this package may return.

`ScoreFactors.contributions` is deliberately a free mapping in the shared
contract, so that one model's vocabulary is not frozen into a file every model
depends on. The vocabulary therefore has to be pinned *here*, on the producing
side: without it, #358's "the explanation's factors always match the score's
factors" test would be checking two free-form strings against each other.

Adding a member is a deliberate act with a downstream consequence — anything
generating explanations has to know how to phrase it.
"""

from __future__ import annotations

from enum import StrEnum


class Factor(StrEnum):
    """A named contribution to a score produced in `mind.scoring`."""

    TIME_PRESSURE = "time_pressure"
    """How soon the task has to happen — from its deadline or from its wording."""

    IMPACT = "impact"
    """Who is affected and how badly, read from the title and description."""

    QUICK_WIN = "quick_win"
    """How quickly the task can be cleared. Never decisive on its own."""

    HIERARCHY = "hierarchy"
    """The net effect of averaging in the task's ancestors (#474) -- 0 for a
    root or an orphan, and can be negative (an unimportant ancestor can pull
    a task below its own baseline, unlike an additive-only boost)."""


#: Every name this package is allowed to put in `contributions`.
FACTOR_NAMES: frozenset[str] = frozenset(factor.value for factor in Factor)
