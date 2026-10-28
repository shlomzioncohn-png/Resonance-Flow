"""Does a flag hit a planted disease? One rule per pattern."""
from __future__ import annotations

from ..detectors.common import Flag
from .truth import Disease

JACCARD_MIN = 0.5

DISEASE_TYPE = {"CHRONIC_BLOCKER": "chronic_blocking", "RECURRING_CYCLE": "recurring_blocker", "SLOW_CHAIN": "slow_chain"}


def jaccard(a: set[str], b: set[str] | frozenset[str]) -> float:
    union = len(a | b)
    return len(a & b) / union if union else 0.0


def matches(flag: Flag, disease: Disease) -> bool:
    """chronic blocker: same task; recurring cycle: same edge (either edge of a near-cycle pair);
    slow chain: Jaccard of the task sets at least 0.5."""
    if flag.pattern == "CHRONIC_BLOCKER":
        return flag.entity_ids[0] == disease.root_task_id
    if flag.pattern == "RECURRING_CYCLE":
        return any(e in disease.edges for e in flag.entity_ids)
    if flag.pattern == "SLOW_CHAIN":
        return jaccard(set(flag.entity_ids), disease.task_ids) >= JACCARD_MIN
    raise ValueError(f"unknown pattern {flag.pattern}")


def flag_tasks(flag: Flag) -> set[str]:
    """Every task a flag points at (both ends of an edge)."""
    if flag.pattern == "RECURRING_CYCLE":
        return {t for e in flag.entity_ids for t in e.split(">")}
    return set(flag.entity_ids)
