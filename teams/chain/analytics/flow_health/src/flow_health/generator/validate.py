"""Checks a generated history against Chain's table constraints and replays it for consistency.

Works on the row lists alone (no simulator state), so the same checks can later run on rows read
back from the real tables.
"""
from __future__ import annotations

from collections import defaultdict
from typing import Iterable

from .simulation import DependencyRow, StateRow

_STATUSES = {"PENDING", "ACTIVE", "DONE"}
_SOURCES = {"MIND", "USER", "SYSTEM"}


def validate(dep_rows: list[DependencyRow], state_rows: list[StateRow]) -> list[str]:
    errors = _check_schema(dep_rows, state_rows)
    if errors:  # replaying malformed rows only produces noise
        return errors
    return _replay(dep_rows, state_rows) + _check_acyclic(dep_rows)


def _check_schema(dep_rows: Iterable[DependencyRow], state_rows: Iterable[StateRow]) -> list[str]:
    errors: list[str] = []
    for table, rows in (("dependency_history", list(dep_rows)), ("task_state_history", list(state_rows))):
        ids = {r.id for r in rows}
        if len(ids) != len(rows):
            errors.append(f"{table}: duplicate ids")
        for prev, cur in zip(rows, rows[1:]):
            if cur.sequence <= prev.sequence or cur.created_at < prev.created_at:
                errors.append(f"{table}: rows not ordered at sequence {cur.sequence}")
                break

    seen_refs = set()
    for r in dep_rows:
        if r.action not in {"ADDED", "REMOVED", "REJECTED"} or r.source not in _SOURCES:
            errors.append(f"dependency_history seq {r.sequence}: bad action/source")
        if r.source_ref is not None:
            if (r.source, r.source_ref) in seen_refs:
                errors.append(f"dependency_history seq {r.sequence}: duplicate (source, source_ref)")
            seen_refs.add((r.source, r.source_ref))

    for r in state_rows:
        where = f"task_state_history seq {r.sequence}"
        for s in (r.from_status, r.to_status):
            if s is not None and s not in _STATUSES:
                errors.append(f"{where}: bad status {s}")
        b, a = r.blocking_count_before, r.blocking_count_after
        if r.event_type == "STATUS_CHANGED":
            ok = r.from_status and r.to_status and b is None and a is None
        elif r.event_type == "BLOCKED":
            ok = r.from_status is None and r.to_status is None and b == 0 and a is not None and a > 0
        elif r.event_type == "UNBLOCKED":
            ok = r.from_status is None and r.to_status is None and b is not None and b > 0 and a == 0
        else:
            ok = False
        if not ok:
            errors.append(f"{where}: {r.event_type} violates the table's shape check")
    return errors


def _replay(dep_rows: list[DependencyRow], state_rows: list[StateRow]) -> list[str]:
    """Re-derives every blocking count from dependency_history + status changes and compares."""
    errors: list[str] = []
    groups: dict = defaultdict(lambda: ([], []))
    for r in dep_rows:
        groups[r.created_at][0].append(r)
    for r in state_rows:
        groups[r.created_at][1].append(r)

    status: dict = defaultdict(lambda: "PENDING")
    deps: dict = defaultdict(set)
    dependents: dict = defaultdict(set)
    blocking: dict = defaultdict(int)

    for ts in sorted(groups):
        deps_in, states_in = groups[ts]
        start: dict = {}  # task -> blocking count at the beginning of this timestamp

        def bump(task, delta):
            start.setdefault(task, blocking[task])
            blocking[task] += delta

        for r in deps_in:
            edge_known = r.depends_on_task_id in deps[r.task_id]
            if r.action == "REJECTED":
                if edge_known:
                    errors.append(f"dependency_history seq {r.sequence}: REJECTED an edge that already exists")
                continue
            if r.action == "ADDED":
                if edge_known:
                    errors.append(f"dependency_history seq {r.sequence}: ADDED twice")
                    continue
                deps[r.task_id].add(r.depends_on_task_id)
                dependents[r.depends_on_task_id].add(r.task_id)
                if status[r.depends_on_task_id] != "DONE":
                    bump(r.task_id, +1)
            else:
                if not edge_known:
                    errors.append(f"dependency_history seq {r.sequence}: REMOVED an edge that is not there")
                    continue
                deps[r.task_id].discard(r.depends_on_task_id)
                dependents[r.depends_on_task_id].discard(r.task_id)
                if status[r.depends_on_task_id] != "DONE":
                    bump(r.task_id, -1)

        for r in states_in:
            if r.event_type != "STATUS_CHANGED":
                continue
            if r.from_status != status[r.task_id]:
                errors.append(f"task_state_history seq {r.sequence}: from_status {r.from_status} "
                              f"but the task is {status[r.task_id]}")
            status[r.task_id] = r.to_status
            if r.to_status == "DONE" and r.from_status != "DONE":
                for d in dependents[r.task_id]:
                    bump(d, -1)
            elif r.from_status == "DONE" and r.to_status != "DONE":
                for d in dependents[r.task_id]:
                    bump(d, +1)

        expected = {}
        for task, before in start.items():
            after = blocking[task]
            if after < 0:
                errors.append(f"{ts}: negative blocking count for {task}")
            if before == 0 and after > 0:
                expected[task] = ("BLOCKED", 0, after)
            elif before > 0 and after == 0:
                expected[task] = ("UNBLOCKED", before, 0)
        actual = {r.task_id: (r.event_type, r.blocking_count_before, r.blocking_count_after)
                  for r in states_in if r.event_type != "STATUS_CHANGED"}
        if expected != actual:
            errors.append(f"{ts}: blocked/unblocked events {actual} do not match the replayed {expected}")
        if len(errors) > 20:
            errors.append("... stopping after 20 errors")
            break
    return errors


def _check_acyclic(dep_rows: list[DependencyRow]) -> list[str]:
    graph: dict = defaultdict(set)
    indegree: dict = defaultdict(int)
    for r in dep_rows:
        if r.action == "ADDED" and r.depends_on_task_id not in graph[r.task_id]:
            graph[r.task_id].add(r.depends_on_task_id)
            indegree[r.depends_on_task_id] += 1
            indegree.setdefault(r.task_id, 0)
    ready = [n for n, d in indegree.items() if d == 0]
    visited = 0
    while ready:
        node = ready.pop()
        visited += 1
        for dep in graph.get(node, ()):
            indegree[dep] -= 1
            if indegree[dep] == 0:
                ready.append(dep)
    return [] if visited == len(indegree) else ["dependency graph contains a cycle"]
