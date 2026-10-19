"""A multi-level subtree stalled behind one held root."""
from __future__ import annotations

from .base import Disease, Planter, by_severity

FLOORS, FACTORS, CAP = (14, 28, 50), (6, 12, 24), 80  # days; subtle/moderate/severe


def _nominal_days(p: Planter, severity: str) -> float:
    return p.relative_days(FLOORS, FACTORS, CAP)


def _plant(p: Planter, severity: str):
    size = by_severity((4, 8, 15), severity)
    hold_days = p.nominal * p.rng.uniform(0.9, 1.15)
    root = p.task(p.t0, hold_finish_until=p.hold_end(hold_days))
    p.attach_upstream(root)
    nodes = [root]
    for i in range(size):
        # the first two nodes force depth >= 2; the rest attach anywhere, so the cluster is a real subtree
        parent = nodes[-1] if i < 2 else p.rng.choice(nodes)
        node = p.task(p.advance(p.rng.uniform(0.5, 4)), tier=p.dependent_tier())
        p.depend(node, [parent])
        nodes.append(node)
    return root, {"size": size, "hold_days": round(hold_days, 1)}


DISEASE = Disease("stuck_subgraph", _plant, _nominal_days)
