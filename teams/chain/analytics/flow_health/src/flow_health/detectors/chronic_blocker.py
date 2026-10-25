"""Detector 1: a task that is worked on far longer than its tier's norm while other tasks wait behind it."""
from __future__ import annotations

import pandas as pd

from .common import Flag, make_flag_id, percentile_scores, tier_phrase
from .config import ChronicBlockerConfig

PATTERN = "CHRONIC_BLOCKER"


def _severity(ratio: float, cfg: ChronicBlockerConfig) -> str:
    if ratio >= cfg.high_from_ratio:
        return "high"
    return "medium" if ratio >= cfg.medium_from_ratio else "low"


def detect(tasks: pd.DataFrame, cfg: ChronicBlockerConfig, clusters: pd.DataFrame | None = None) -> list[Flag]:
    """`tasks` is features_task. A task is flagged when it was ACTIVE at least `min_active_ratio` tier medians
    (`cfg.ratio_column`, so unfinished tasks count up to `as_of`) and had at least `min_dependents` dependents at
    once. A long task nobody waits on is not flagged. The time a task itself spent blocked plays no part: a chronic
    blocker holds others up while it is being worked on, it is not stuck itself."""
    if tasks.empty:
        return []
    t = tasks
    if clusters is not None:
        t = t.merge(clusters[["task_id", "cluster"]], on="task_id", how="left")
    hit = t[(t[cfg.ratio_column] >= cfg.min_active_ratio) & (t["n_dependents_max"] >= cfg.min_dependents)]
    hit = hit.sort_values("task_id")

    scope = " באותו פרויקט" if cfg.ratio_column.endswith("_project") else ""
    rows = []
    for r in hit.itertuples():
        ratio = float(getattr(r, cfg.ratio_column))
        tier_median = r.days_active / ratio
        evidence = {
            "days_active": round(float(r.days_active), 2), "tier": r.tier,
            "tier_median_active_days": round(float(tier_median), 2), "ratio_to_tier_median": round(ratio, 2),
            "n_dependents_max": int(r.n_dependents_max), "is_open": bool(r.is_open),
            "days_blocked": round(float(r.days_blocked_total), 2),
        }
        if clusters is not None:
            evidence["cluster"] = None if pd.isna(r.cluster) else int(r.cluster)
        explanation = (f"בעבודה {round(r.days_active)} יום, פי {ratio:.1f} מהחציון של {tier_phrase(r.tier)}{scope} "
                       f"({round(tier_median)} יום), חוסמת {int(r.n_dependents_max)} משימות")
        if r.is_open:
            explanation += "; המשימה עדיין לא הסתיימה"
        rows.append((r.task_id, ratio, _severity(ratio, cfg), explanation, evidence))

    ids = [make_flag_id(PATTERN, [task_id]) for task_id, *_ in rows]
    scores = percentile_scores([ratio for _, ratio, *_ in rows], ids)
    return [Flag(flag_id=fid, pattern=PATTERN, entity_type="task", entity_ids=[task_id], score=score,
                 severity=sev, explanation=text, evidence=ev)
            for fid, score, (task_id, _, sev, text, ev) in zip(ids, scores, rows)]
