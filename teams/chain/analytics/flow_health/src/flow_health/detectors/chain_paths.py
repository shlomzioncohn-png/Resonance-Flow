"""Extracts the dependency paths the slow chain detector compares: every maximal path of finished tasks in the DAG."""
from __future__ import annotations

from collections import defaultdict

import numpy as np
import pandas as pd

from ..features import Timeline
from .config import SlowChainConfig

PATH_COLUMNS = ["sink_task_id", "task_ids", "chain_length", "end_to_end_days", "max_hop_share"]
_DAY = 86400.0


def extract_paths(tl: Timeline, cfg: SlowChainConfig) -> pd.DataFrame:
    """Maximal paths of `min_length`..`max_length` tasks, root first.

    The graph holds finished tasks only. An edge dependent -> target is used when it was active at the moment the
    dependent finished and the target finished no later. A path is maximal: it starts at a task with no such
    dependency and ends at a task nothing such depends on, so a stretch of a longer path is not a path of its own;
    paths longer than `max_length` are left out whole.

    Time: from the first ACTIVE of the first task (its DONE when it never was ACTIVE) to the DONE of the last. The
    first hop is the first task's own work, each next hop runs from one DONE to the next, so the hops add up to the total.
    """
    seg = tl.status_segments
    done_at = seg.loc[seg["status"] == "DONE"].set_index("task_id")["start"].to_dict()
    first_active = seg.loc[seg["status"] == "ACTIVE"].groupby("task_id")["start"].min().to_dict()

    targets_of, dependents_of = defaultdict(set), defaultdict(set)
    for e in tl.edge_intervals.itertuples(index=False):
        d, t = e.task_id, e.depends_on_task_id
        if d in done_at and t in done_at and done_at[t] <= done_at[d] and e.start <= done_at[d] \
                and (e.is_open or e.end > done_at[d]):
            targets_of[d].add(t)
            dependents_of[t].add(d)

    rows = []

    def walk(task, tail):  # tail: the path after `task` towards the sink; walking from the sink back builds it root first
        path = [task, *tail]
        if len(path) > cfg.max_length:
            return
        if not targets_of[task]:  # a root: the path is complete
            if len(path) >= cfg.min_length:
                rows.append(_row(path, done_at, first_active))
            return
        for target in sorted(targets_of[task]):
            walk(target, path)

    for sink in sorted(done_at):
        if not dependents_of[sink]:
            walk(sink, [])
    return pd.DataFrame(rows, columns=PATH_COLUMNS)


def _row(path, done_at, first_active):
    """path: root first."""
    chain = path
    begin = first_active.get(chain[0], done_at[chain[0]])
    marks = [begin] + [done_at[t] for t in chain]
    hops = np.diff(np.array([m.value for m in marks], dtype="int64")) / 1e9 / _DAY
    total = float(hops.sum())
    return (chain[-1], ";".join(chain), len(chain), total, float(hops.max() / total) if total > 0 else 0.0)
