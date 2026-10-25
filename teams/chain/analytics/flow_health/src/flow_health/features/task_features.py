"""features_task: one row per task, computed from the rebuilt timeline."""
from __future__ import annotations

import numpy as np
import pandas as pd

from ..loaders import Dataset
from .timeline import Timeline

UNKNOWN_TIER = "UNKNOWN"
_DAY = 86400.0

TASK_COLUMNS = [
    "task_id", "project_id", "tier", "is_open", "age_days", "days_pending", "days_active", "days_blocked_total",
    "n_block_events", "blocked_p50", "blocked_p90", "blocked_max", "n_dependents_now", "n_dependents_max",
    "dependents_per_30d", "age_ratio_to_tier", "blocked_ratio_to_tier", "active_ratio_to_tier",
]
MIN_AGE_DAYS_FOR_RATE = 1.0  # a task younger than a day is treated as one day old when computing a rate


def _days(start: pd.Series, end: pd.Series) -> pd.Series:
    return (end - start).dt.total_seconds() / _DAY


def _sum_by_task(segments: pd.DataFrame) -> pd.Series:
    return _days(segments["start"], segments["end"]).groupby(segments["task_id"]).sum()


def _max_concurrent(edges: pd.DataFrame) -> pd.Series:
    """Per depended-on task: the largest number of dependents it ever had at the same time."""
    peak = {}
    for target, g in edges.groupby("depends_on_task_id", sort=False):
        moves = [(t, 1) for t in g["start"]] + [(t, -1) for t, open_ in zip(g["end"], g["is_open"]) if not open_]
        level = best = 0
        for _, step in sorted(moves, key=lambda m: (m[0], m[1])):  # a removal at the same instant goes first
            level += step
            best = max(best, level)
        peak[target] = best
    return pd.Series(peak, dtype="int64")


def _ratio_to_tier(values: pd.Series, tier: pd.Series, fallback: float, only_positive: bool = False) -> pd.Series:
    """value / median of its tier. A tier whose median is 0 falls back to `fallback`; a value of 0 stays 0.

    only_positive: the median is taken over the tier's non-zero values (for block time, where most tasks have 0).
    """
    basis = values.where(values > 0) if only_positive else values
    median = basis.groupby(tier).transform("median")
    return (values / median.where(median > 0, fallback)).where(values > 0, 0.0)


def task_features(ds: Dataset, tl: Timeline) -> pd.DataFrame:
    tasks = ds.tasks[["task_id", "project_id"]].merge(ds.task_meta, on="task_id", how="left")
    tasks["tier"] = tasks["tier"].fillna(UNKNOWN_TIER)
    tasks = tasks.set_index("task_id")

    seg = tl.status_segments
    done_at = seg.loc[seg["status"] == "DONE"].set_index("task_id")["start"]
    created = ds.tasks.set_index("task_id")["created_at"]
    end = done_at.reindex(created.index).fillna(ds.as_of)
    tasks["is_open"] = done_at.reindex(tasks.index).isna()
    tasks["age_days"] = (end - created).dt.total_seconds().reindex(tasks.index) / _DAY

    for status, column in (("PENDING", "days_pending"), ("ACTIVE", "days_active")):
        tasks[column] = _sum_by_task(seg[seg["status"] == status]).reindex(tasks.index).fillna(0.0)

    blocks = tl.block_segments.assign(days=lambda d: _days(d["start"], d["end"]))
    by_task = blocks.groupby("task_id")["days"]
    tasks["days_blocked_total"] = by_task.sum().reindex(tasks.index).fillna(0.0)
    tasks["n_block_events"] = by_task.size().reindex(tasks.index).fillna(0).astype("int64")
    # undefined without a block: left empty here, and turned into 0 ("did not happen") only where a model needs a number
    tasks["blocked_p50"] = by_task.quantile(0.5).reindex(tasks.index)
    tasks["blocked_p90"] = by_task.quantile(0.9).reindex(tasks.index)
    tasks["blocked_max"] = by_task.max().reindex(tasks.index)

    edges = tl.edge_intervals
    now = edges[edges["is_open"]].groupby("depends_on_task_id").size()
    tasks["n_dependents_now"] = now.reindex(tasks.index).fillna(0).astype("int64")
    tasks["n_dependents_max"] = _max_concurrent(edges).reindex(tasks.index).fillna(0).astype("int64")
    tasks["dependents_per_30d"] = tasks["n_dependents_max"] / tasks["age_days"].clip(lower=MIN_AGE_DAYS_FOR_RATE) * 30

    tasks["age_ratio_to_tier"] = _ratio_to_tier(tasks["age_days"], tasks["tier"], fallback=tasks["age_days"].median())
    positive = tasks.loc[tasks["days_blocked_total"] > 0, "days_blocked_total"]
    tasks["blocked_ratio_to_tier"] = _ratio_to_tier(
        tasks["days_blocked_total"], tasks["tier"], fallback=positive.median() if len(positive) else np.nan,
        only_positive=True)
    active = tasks.loc[tasks["days_active"] > 0, "days_active"]
    tasks["active_ratio_to_tier"] = _ratio_to_tier(
        tasks["days_active"], tasks["tier"], fallback=active.median() if len(active) else np.nan, only_positive=True)
    return tasks.reset_index()[TASK_COLUMNS].sort_values("task_id", kind="stable").reset_index(drop=True)
