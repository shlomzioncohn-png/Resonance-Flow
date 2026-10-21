"""features_edge: one row per (task_id, depends_on_task_id) pair that appears in the dependency history."""
from __future__ import annotations

from collections import defaultdict
from datetime import timedelta

import numpy as np
import pandas as pd

from ..loaders import Dataset
from .timeline import Timeline

EDGE_COLUMNS = ["task_id", "depends_on_task_id", "n_toggles", "median_gap_days", "active_fraction",
                "n_dependent_flips"]
_DAY = 86400.0
# A flip is blamed on an edge when the edge event and the BLOCKED / UNBLOCKED row are written together.
ATTRIBUTION_WINDOW = timedelta(seconds=1)


def edge_features(ds: Dataset, tl: Timeline) -> pd.DataFrame:
    intervals = tl.edge_intervals
    seg = tl.status_segments
    done_at = seg.loc[seg["status"] == "DONE"].set_index("task_id")["start"].to_dict()
    flips = _flips_per_edge(tl, done_at)

    rows = []
    for (task, target), g in intervals.groupby(["task_id", "depends_on_task_id"], sort=True):
        transitions = sorted(list(g["start"]) + [e for e, o in zip(g["end"], g["is_open"]) if not o])
        gaps = pd.Series(transitions).diff().dropna().dt.total_seconds() / _DAY
        first = g["start"].min()
        last = min(ds.as_of, done_at.get(task, ds.as_of))  # an edge matters until its dependent is done
        window = (last - first).total_seconds() / _DAY
        if window > 0:
            active = sum(max(0.0, (min(e, last) - s).total_seconds()) for s, e in zip(g["start"], g["end"])) / _DAY
            fraction = active / window
        else:  # added and judged at the same instant
            fraction = 1.0 if bool(g["is_open"].iloc[-1]) else 0.0
        rows.append((task, target, len(transitions), float(gaps.median()) if len(gaps) else np.nan,
                     fraction, flips.get((task, target), 0)))
    return pd.DataFrame(rows, columns=EDGE_COLUMNS)


def _flips_per_edge(tl: Timeline, done_at: dict) -> dict:
    """How many times each edge's dependent flipped BLOCKED / UNBLOCKED because of it.

    A BLOCKED row belongs to the edges the dependent was given at that instant; an UNBLOCKED row belongs to the
    edges removed then, or whose target finished then.
    """
    added, removed, by_task = defaultdict(list), defaultdict(list), defaultdict(list)
    for r in tl.edge_intervals.itertuples(index=False):
        added[r.task_id].append((r.start, r.depends_on_task_id))
        if not r.is_open:
            removed[r.task_id].append((r.end, r.depends_on_task_id))
        by_task[r.task_id].append(r)
    flips: dict = defaultdict(int)
    for b in tl.block_segments.itertuples(index=False):
        for at, target in added[b.task_id]:
            if abs(at - b.start) <= ATTRIBUTION_WINDOW:
                flips[(b.task_id, target)] += 1
        if b.is_open:
            continue
        hit = {target for at, target in removed[b.task_id] if abs(at - b.end) <= ATTRIBUTION_WINDOW}
        hit |= {e.depends_on_task_id for e in by_task[b.task_id]
                if e.start <= b.end and e.depends_on_task_id in done_at
                and abs(done_at[e.depends_on_task_id] - b.end) <= ATTRIBUTION_WINDOW}
        for target in hit:
            flips[(b.task_id, target)] += 1
    return flips
