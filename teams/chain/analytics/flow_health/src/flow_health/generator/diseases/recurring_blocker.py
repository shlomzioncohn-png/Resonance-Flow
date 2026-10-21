"""A dependency edge blocks, is released and blocks again and again; the dependent flaps BLOCKED / UNBLOCKED."""
from __future__ import annotations

from datetime import timedelta

from ..timeutil import snap_to_work_time
from .base import Disease, Planter, by_severity

GAP_DAYS = (0.5, 3.0)  # between two toggles of the edge


def _plant(p: Planter, severity: str):
    cycles = by_severity((3, 6, 10), severity)  # times the dependent gets blocked
    n = by_severity((1, 1, 2), severity)
    blocker = p.task(p.t0, hold_finish_until=p.t0 + timedelta(days=365))  # stays open for the whole flapping
    p.attach_upstream(blocker)
    dependents, last = [], p.t0
    for _ in range(n):
        dependent = p.task(p.advance(p.rng.uniform(1, 6)), tier=p.dependent_tier(),
                           hold_start_until=p.t0 + timedelta(days=cycles * 2 * GAP_DAYS[1] + 10))  # stays PENDING, so it keeps flapping
        p.depend(dependent, [blocker])  # block no. 1
        at = dependent.created_at + timedelta(minutes=1)
        for _ in range(cycles - 1):
            for action in ("REMOVED", "ADDED"):
                at = snap_to_work_time(at + timedelta(days=p.rng.uniform(*GAP_DAYS)), p.rng)
                p.toggle(dependent, blocker, action, at)
        dependents.append(dependent)
        last = max(last, at)
    blocker.hold_finish_until = last + timedelta(days=2)  # the last block is real before the blocker finishes
    return blocker, {"cycles": cycles, "dependents": n}


DISEASE = Disease("recurring_blocker", _plant, tiers=("operational", "tactical"))
