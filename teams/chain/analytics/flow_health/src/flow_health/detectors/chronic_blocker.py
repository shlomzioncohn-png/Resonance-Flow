"""Detector 1: a task that stays blocked far longer than its tier's norm while many tasks wait behind it."""
from __future__ import annotations

import pandas as pd

from .common import Flag, make_flag_id, percentile_scores, tier_phrase
from .config import ChronicBlockerConfig

PATTERN = "CHRONIC_BLOCKER"
_UNKNOWN = "UNKNOWN"


def _severity(ratio: float, cfg: ChronicBlockerConfig) -> str:
    if ratio >= cfg.high_from_ratio:
        return "high"
    return "medium" if ratio >= cfg.medium_from_ratio else "low"


def _ratio_to_median(tasks: pd.DataFrame) -> tuple[pd.Series, pd.Series]:
    """Blocked days / median blocked days of the tier (blocked tasks only). Unknown tier: the all-tasks median."""
    blocked = tasks["days_blocked_total"].where(tasks["days_blocked_total"] > 0)
    median = blocked.groupby(tasks["tier"]).transform("median")
    overall = blocked.median()
    median = median.where(tasks["tier"] != _UNKNOWN, overall)
    ratio = (tasks["days_blocked_total"] / median).where(median > 0)
    return ratio, median


def detect(tasks: pd.DataFrame, cfg: ChronicBlockerConfig, clusters: pd.DataFrame | None = None) -> list[Flag]:
    """`tasks` is features_task. A candidate is unfinished, or finished at least `late_age_ratio_to_tier` tier
    medians late. It is flagged when it was blocked >= `min_ratio_to_tier` tier medians and carried >= `min_dependents`."""
    if tasks.empty:
        return []
    t = tasks.copy()
    t["ratio"], t["tier_median"] = _ratio_to_median(t)
    if clusters is not None:
        t = t.merge(clusters[["task_id", "cluster"]], on="task_id", how="left")

    candidate = t["is_open"] | (t["age_ratio_to_tier"] >= cfg.late_age_ratio_to_tier)
    hit = t[candidate & (t["n_dependents_max"] >= cfg.min_dependents) & (t["ratio"] >= cfg.min_ratio_to_tier)]
    hit = hit.sort_values("task_id")

    rows = []
    for r in hit.itertuples():
        evidence = {
            "days_blocked": round(float(r.days_blocked_total), 2), "tier": r.tier,
            "tier_median_blocked_days": round(float(r.tier_median), 2), "ratio_to_tier_median": round(float(r.ratio), 2),
            "n_dependents_max": int(r.n_dependents_max), "age_ratio_to_tier": round(float(r.age_ratio_to_tier), 2),
            "is_open": bool(r.is_open),
        }
        if clusters is not None:
            evidence["cluster"] = None if pd.isna(r.cluster) else int(r.cluster)
        explanation = (f"חסומה {round(r.days_blocked_total)} יום, פי {r.ratio:.1f} מהחציון של {tier_phrase(r.tier)} "
                       f"({round(r.tier_median)} יום), חוסמת {int(r.n_dependents_max)} משימות")
        if r.is_open:
            explanation += "; המשימה עדיין לא הסתיימה"
        rows.append((r.task_id, float(r.ratio), _severity(r.ratio, cfg), explanation, evidence))

    ids = [make_flag_id(PATTERN, [task_id]) for task_id, *_ in rows]
    scores = percentile_scores([ratio for _, ratio, *_ in rows], ids)
    return [Flag(flag_id=fid, pattern=PATTERN, entity_type="task", entity_ids=[task_id], score=score,
                 severity=sev, explanation=text, evidence=ev)
            for fid, score, (task_id, _, sev, text, ev) in zip(ids, scores, rows)]
