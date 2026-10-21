"""A blocker is finished and reopened again and again; its dependents flap BLOCKED / UNBLOCKED."""
from __future__ import annotations

from datetime import timedelta

from .base import Disease, Planter, by_severity


def _plant(p: Planter, severity: str):
    cycles = by_severity((3, 6, 10), severity)
    blocker = p.task(p.t0, reopen_budget=cycles - 1, reopen_gap_hours=(24.0, 96.0),
                     hold_finish_until=p.t0 + timedelta(days=2))  # first DONE only after dependents are attached
    p.attach_upstream(blocker)
    n = by_severity((1, 1, 2), severity)
    for _ in range(n):
        # held back so they stay PENDING and visibly flap instead of finishing in the first window
        dependent = p.task(p.advance(p.rng.uniform(1, 6)), tier=p.dependent_tier(),
                           hold_start_until=p.t0 + timedelta(days=cycles * 6 + 5))
        p.depend(dependent, [blocker])
    return blocker, {"cycles": cycles, "dependents": n}


DISEASE = Disease("recurring_blocker", _plant, tiers=("operational", "tactical"))
