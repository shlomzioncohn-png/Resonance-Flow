"""One blocker stays open far longer than peers of its tier, and its dependents wait."""
from __future__ import annotations

from .base import Disease, Planter, by_severity

FLOORS, FACTORS, CAP = (10, 20, 40), (5, 10, 20), 75  # days; subtle/moderate/severe


def _nominal_days(p: Planter, severity: str) -> float:
    return p.relative_days(FLOORS, FACTORS, CAP)


def _plant(p: Planter, severity: str):
    hold_days = p.nominal * p.rng.uniform(0.9, 1.15)
    blocker = p.task(p.t0, hold_finish_until=p.hold_end(hold_days))
    p.attach_upstream(blocker)
    n = by_severity((1, 2, 3), severity)
    for _ in range(n):
        p.depend(p.task(p.advance(p.rng.uniform(1, 6)), tier=p.dependent_tier()), [blocker])
    return blocker, {"hold_days": round(hold_days, 1), "dependents": n}


DISEASE = Disease("chronic_blocking", _plant, _nominal_days)
