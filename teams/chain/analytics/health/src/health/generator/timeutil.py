from __future__ import annotations

import random
from datetime import datetime, timedelta

WORK_START, WORK_END = 8, 19  # UTC hours; keeps the synthetic week looking like a real one
# Automation (Mind, system updates) and late work do not respect office hours. Without this share every
# duration lands near a whole number of days, with empty gaps in between: an obvious synthetic fingerprint.
OFF_HOURS_SHARE = 0.15


def snap_to_work_time(t: datetime, rng: random.Random) -> datetime:
    """Move t to the next working moment (Mon-Fri, 08:00-19:00); in-hours times are untouched.

    A small share of events is left exactly where it is, at any hour or day of the week.
    """
    if rng.random() < OFF_HOURS_SHARE:
        return t
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
    # spread over the whole working day: a short jitter piles every pushed event up around 08:00,
    # which turns durations into near-whole days (visible as bands in the plots)
    return t + timedelta(seconds=rng.uniform(0, (WORK_END - WORK_START) * 3600 - 1)) if moved else t


def lognormal_hours(rng: random.Random, median_hours: float, sigma: float) -> float:
    return median_hours * rng.lognormvariate(0.0, sigma)
