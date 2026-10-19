"""An unfinished task that keeps gaining dependents over weeks."""
from __future__ import annotations

from datetime import timedelta

from .base import Disease, Planter, by_severity

WINDOW_DAYS = (20, 30, 45)  # subtle/moderate/severe


def _nominal_days(p: Planter, severity: str) -> float:
    return by_severity(WINDOW_DAYS, severity)


def _plant(p: Planter, severity: str):
    n = by_severity((6, 12, 24), severity)
    window_days = p.nominal
    bottleneck = p.task(p.t0, hold_finish_until=p.hold_end(window_days + 15))
    p.attach_upstream(bottleneck)
    step = window_days * 24 / n
    for _ in range(n):  # dependents trickle in over the window instead of arriving together
        created = p.advance(step * p.rng.uniform(0.7, 1.3))
        if created > p.cfg.end - timedelta(days=1):  # an ongoing one is cut off by the end of the history
            break
        p.depend(p.task(created, tier=p.dependent_tier()), [bottleneck])
    return bottleneck, {"dependents": len(p.members) - 1, "planned_dependents": n, "window_days": window_days}


DISEASE = Disease("growing_bottleneck", _plant, _nominal_days)
