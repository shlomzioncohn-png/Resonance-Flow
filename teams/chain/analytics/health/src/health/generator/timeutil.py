from __future__ import annotations

import random
from datetime import datetime, timedelta

WORK_START, WORK_END = 8, 19  # UTC hours; keeps the synthetic week looking like a real one


def snap_to_work_time(t: datetime, rng: random.Random) -> datetime:
    """Move t to the next working moment (Mon-Fri, 08:00-19:00); in-hours times are untouched."""
    moved = False
    while True:
        if t.weekday() >= 5:
            t = (t + timedelta(days=7 - t.weekday())).replace(hour=WORK_START, minute=0, second=0, microsecond=0)
        elif t.hour >= WORK_END:
            t = (t + timedelta(days=1)).replace(hour=WORK_START, minute=0, second=0, microsecond=0)
        elif t.hour < WORK_START:
            t = t.replace(hour=WORK_START, minute=0, second=0, microsecond=0)
        else:
            break
        moved = True
    # jitter so everything that got pushed doesn't pile up at exactly 08:00
    return t + timedelta(seconds=rng.uniform(0, 3600)) if moved else t


def lognormal_hours(rng: random.Random, median_hours: float, sigma: float) -> float:
    return median_hours * rng.lognormvariate(0.0, sigma)
