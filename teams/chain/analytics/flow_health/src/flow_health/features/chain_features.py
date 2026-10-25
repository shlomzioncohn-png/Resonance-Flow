"""features_chain: one row per chain, a chain being the longest dependency path behind a finished sink task."""
from __future__ import annotations

from collections import defaultdict

import numpy as np
import pandas as pd

from ..loaders import Dataset
from .timeline import Timeline

MIN_CHAIN_LENGTH = 3
_DAY = 86400.0
CHAIN_COLUMNS = [
    "sink_task_id", "root_task_id", "task_ids", "chain_length", "end_to_end_days", "hop_days", "mean_hop", "max_hop",
    "std_hop", "max_hop_share", "n_edge_changes", "length_bucket",
]


def length_bucket(length: int) -> str:
    return "8+" if length >= 8 else str(length)


def chain_features(ds: Dataset, tl: Timeline) -> pd.DataFrame:
    seg = tl.status_segments
    done_at = seg.loc[seg["status"] == "DONE"].set_index("task_id")["start"].to_dict()
    first_active = seg.loc[seg["status"] == "ACTIVE"].groupby("task_id")["start"].min().to_dict()

    waits_on = defaultdict(list)  # dependent -> [(target, start, end, is_open)]
    has_dependents = set()  # targets with an edge still active at as_of
    for e in tl.edge_intervals.itertuples(index=False):
        waits_on[e.task_id].append((e.depends_on_task_id, e.start, e.end, e.is_open))
        if e.is_open:
            has_dependents.add(e.depends_on_task_id)

    rows = []
    for sink in sorted(done_at):
        if sink in has_dependents:
            continue
        path = _longest_path(sink, done_at[sink], waits_on, done_at, first_active)
        if len(path) >= MIN_CHAIN_LENGTH:
            rows.append(_row(path[::-1], done_at, first_active, waits_on))  # root first
    return pd.DataFrame(rows, columns=CHAIN_COLUMNS)


def _start(task, done_at, first_active):
    return first_active.get(task, done_at[task])  # a task that never was ACTIVE did no work: it starts when it ends


def _longest_path(sink, finished, waits_on, done_at, first_active) -> list:
    """Sink -> ... -> root over the edges active when the sink finished. The longest path wins; ties go to the one
    that started earliest (the slower chain), then to the smaller ids. An edge is usable only if its target finished
    before its dependent did."""
    best: dict = {}

    def path_from(task, guard):
        if task in best:
            return best[task]
        options = []
        for target, start, end, is_open in sorted(waits_on.get(task, [])):
            active = start <= finished and (is_open or end > finished)
            if active and target in done_at and done_at[target] <= done_at[task] and target not in guard:
                options.append(path_from(target, guard | {task}))
        tail = min(options, key=_rank) if options else ()
        best[task] = (task, *tail)
        return best[task]

    def _rank(path):
        return (-len(path), min(_start(t, done_at, first_active) for t in path), path)

    return list(path_from(sink, frozenset()))


def _row(chain, done_at, first_active, waits_on):
    """chain: root first."""
    sink = chain[-1]
    begin = min(_start(t, done_at, first_active) for t in chain)
    # hop 0 is the root's own work (from the chain's first ACTIVE to its DONE); the others run DONE to DONE
    marks = [begin] + [done_at[t] for t in chain]
    hops = np.diff(np.array([m.value for m in marks], dtype="int64")) / 1e9 / _DAY
    total = (done_at[sink] - begin).total_seconds() / _DAY
    changes = 0
    for dependent, target in zip(chain[1:], chain[:-1]):
        for t, start, end, is_open in waits_on[dependent]:
            if t == target:
                changes += (start <= done_at[sink]) + (not is_open and end <= done_at[sink])
    return (sink, chain[0], ";".join(chain), len(chain), total, ";".join(repr(float(h)) for h in hops), hops.mean(),
            hops.max(), hops.std(), hops.max() / total if total > 0 else 0.0, int(changes), length_bucket(len(chain)))
