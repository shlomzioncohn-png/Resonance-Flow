"""Counting TP / FP / FN and precision / recall, per detector and overall."""
from __future__ import annotations

from dataclasses import dataclass

from ..detectors.common import PATTERNS, Flag
from .matching import DISEASE_TYPE, flag_tasks, matches
from .truth import Truth

LOW_SEVERITY = "subtle"


def ratio(num: int, den: int) -> float | None:
    """None when undefined (nothing flagged / nothing planted); never a silent 0 or 1."""
    return num / den if den else None


@dataclass
class Counts:
    tp: int = 0
    fp: int = 0
    fn: int = 0

    @property
    def precision(self) -> float | None:
        return ratio(self.tp, self.tp + self.fp)

    @property
    def recall(self) -> float | None:
        return ratio(self.tp, self.tp + self.fn)

    def as_dict(self) -> dict:
        return {"tp": self.tp, "fp": self.fp, "fn": self.fn, "precision": self.precision, "recall": self.recall}


def evaluate(flags: list[Flag], truth: Truth) -> dict:
    """Stage-A diseases are what must be found. A flag is
    - TP if it matches a stage-A disease of its own type (any number of flags on one disease count once),
    - out of scope if it matches only a disease outside stage A (counted neither way, listed separately),
    - FP otherwise; if it also touches a decoy task it is listed under `fell_for_decoy` too."""
    stage_a = [d for d in truth.diseases if d.in_stage_a]
    outside = [d for d in truth.diseases if not d.in_stage_a]
    outcome: dict[str, str] = {}
    hit: set[str] = set()
    fp_flags: dict[str, int] = {p: 0 for p in PATTERNS}
    decoy_hits, out_of_scope = [], []

    for f in flags:
        found = [d for d in stage_a if d.type == DISEASE_TYPE[f.pattern] and matches(f, d)]
        if found:
            hit.update(d.id for d in found)
            outcome[f.flag_id] = "TP"
        elif any(matches(f, d) for d in outside):
            outcome[f.flag_id] = "OUT_OF_SCOPE"
            out_of_scope.append(f.flag_id)
        else:
            outcome[f.flag_id] = "FP"
            fp_flags[f.pattern] += 1
            if flag_tasks(f) & truth.decoy_tasks:
                decoy_hits.append(f.flag_id)

    def counts_for(pattern: str | None) -> Counts:
        c = Counts()
        for d in stage_a:
            if pattern is None or DISEASE_TYPE[pattern] == d.type:
                c.tp += d.id in hit
                c.fn += d.id not in hit
        c.fp = sum(fp_flags.values()) if pattern is None else fp_flags[pattern]
        return c

    def recall_where(pred) -> dict:
        planted = [d for d in stage_a if pred(d)]
        found = sum(d.id in hit for d in planted)
        return {"planted": len(planted), "found": found, "recall": ratio(found, len(planted))}

    return {
        "n_flags": len(flags),
        "per_detector": {p: counts_for(p).as_dict() for p in PATTERNS},
        "overall": counts_for(None).as_dict(),
        "recall_low_severity": {
            "overall": recall_where(lambda d: d.severity == LOW_SEVERITY),
            **{p: recall_where(lambda d, p=p: d.severity == LOW_SEVERITY and d.type == DISEASE_TYPE[p]) for p in PATTERNS}},
        "recall_by_severity": {s: recall_where(lambda d, s=s: d.severity == s) for s in ("subtle", "moderate", "severe")},
        "missed_diseases": sorted(d.id for d in stage_a if d.id not in hit),
        "fell_for_decoy": sorted(decoy_hits),
        "out_of_scope_flags": sorted(out_of_scope),
        "flag_outcomes": outcome,
    }
