"""A chain of same-tier tasks where every link is several times slower than its peers."""
from __future__ import annotations

from .base import Disease, Planter, by_severity


def _plant(p: Planter, severity: str):
    length = by_severity((4, 7, 12), severity)
    factor = by_severity((3.0, 5.0, 8.0), severity)
    chain = []
    for i in range(length):
        link = p.task(p.t0 if i == 0 else p.advance(p.rng.uniform(0.5, 3)), work_mult=factor)
        if i == 0:
            p.attach_upstream(link)
        else:
            p.depend(link, [chain[-1]])
        chain.append(link)
    return chain[0], {"length": length, "work_factor": factor}


DISEASE = Disease("slow_chain", _plant, tiers=("operational", "tactical"))
