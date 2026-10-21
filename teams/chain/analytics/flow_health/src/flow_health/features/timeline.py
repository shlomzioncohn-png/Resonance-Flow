"""Rebuilds, per task, its status segments, block segments and active dependency edges from the two event tables.

A segment still running at `as_of` is cut there (censored) and flagged `is_open`. Open ones are never dropped:
they are exactly what the detectors look for. An event that contradicts the sequence is logged as a warning,
kept in `Timeline.warnings`, and skipped.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

import pandas as pd

from ..loaders import Dataset

log = logging.getLogger(__name__)

STATUS_COLUMNS = ["task_id", "status", "start", "end", "is_open"]
BLOCK_COLUMNS = ["task_id", "start", "end", "is_open"]
EDGE_COLUMNS = ["task_id", "depends_on_task_id", "start", "end", "is_open"]


@dataclass(frozen=True)
class Timeline:
    status_segments: pd.DataFrame  # PENDING / ACTIVE segments, plus a zero-length DONE marker at the finish time
    block_segments: pd.DataFrame  # one row per BLOCKED -> UNBLOCKED pair
    edge_intervals: pd.DataFrame  # one row per ADDED -> REMOVED stretch of an edge (an edge can have several)
    warnings: list[str] = field(default_factory=list)


def build_timeline(ds: Dataset) -> Timeline:
    warnings: list[str] = []

    def warn(message: str) -> None:
        warnings.append(message)
        log.warning(message)

    status_rows: list[tuple] = []
    block_rows: list[tuple] = []
    events = {tid: g for tid, g in ds.task_state_history.groupby("task_id", sort=False)}
    for task in ds.tasks.itertuples(index=False):
        group = events.get(task.task_id)
        rows = [] if group is None else list(group.itertuples(index=False))
        status_rows += _status_segments(task, rows, ds.as_of, warn)
        block_rows += _block_segments(task.task_id, rows, ds.as_of, warn)
    known = set(ds.tasks["task_id"])
    for tid in set(events) - known:
        warn(f"task_state_history has events for unknown task {tid}")

    return Timeline(
        status_segments=pd.DataFrame(status_rows, columns=STATUS_COLUMNS),
        block_segments=pd.DataFrame(block_rows, columns=BLOCK_COLUMNS),
        edge_intervals=_edge_intervals(ds, warn),
        warnings=warnings,
    )


def _status_segments(task, rows, as_of, warn) -> list[tuple]:
    out, current, since = [], "PENDING", task.created_at
    for r in rows:
        if r.event_type != "STATUS_CHANGED":
            continue
        if current == "DONE":
            warn(f"task {task.task_id}: {r.from_status} -> {r.to_status} after DONE at {r.created_at}; ignored")
            continue
        if r.from_status != current:
            warn(f"task {task.task_id}: status change from {r.from_status} but the task was {current}")
        out.append((task.task_id, current, since, r.created_at, False))
        current, since = r.to_status, r.created_at
    if current == "DONE":
        out.append((task.task_id, "DONE", since, since, False))
    else:
        out.append((task.task_id, current, since, as_of, True))  # still running: censored at as_of
    return out


def _block_segments(task_id, rows, as_of, warn) -> list[tuple]:
    out, started = [], None
    for r in rows:
        if r.event_type == "BLOCKED":
            if started is not None:
                warn(f"task {task_id}: BLOCKED at {r.created_at} while already blocked since {started}; ignored")
            else:
                started = r.created_at
        elif r.event_type == "UNBLOCKED":
            if started is None:
                warn(f"task {task_id}: UNBLOCKED at {r.created_at} without a BLOCKED before it; ignored")
            else:
                out.append((task_id, started, r.created_at, False))
                started = None
        elif r.event_type != "STATUS_CHANGED":
            warn(f"task {task_id}: unknown event type {r.event_type}")
    if started is not None:
        out.append((task_id, started, as_of, True))
    return out


def _edge_intervals(ds: Dataset, warn) -> pd.DataFrame:
    out, active = [], {}
    for r in ds.dependency_history.itertuples(index=False):
        key = (r.task_id, r.depends_on_task_id)
        if r.action == "ADDED":
            if key in active:
                warn(f"edge {key[0]} -> {key[1]}: ADDED at {r.created_at} while already active; ignored")
            else:
                active[key] = r.created_at
        elif r.action == "REMOVED":
            if key not in active:
                warn(f"edge {key[0]} -> {key[1]}: REMOVED at {r.created_at} without being active; ignored")
            else:
                out.append((*key, active.pop(key), r.created_at, False))
    out += [(*key, start, ds.as_of, True) for key, start in active.items()]
    df = pd.DataFrame(out, columns=EDGE_COLUMNS)
    return df.sort_values(["start", "task_id", "depends_on_task_id"], kind="stable").reset_index(drop=True)
