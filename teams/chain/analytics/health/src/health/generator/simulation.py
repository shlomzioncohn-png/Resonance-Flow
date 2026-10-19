"""Plays a planned World forward in time and records what Chain would have written.

Mirrors Chain's counter rules: blockingCount = number of dependencies whose status is not DONE;
BLOCKED/UNBLOCKED rows are written only when that count crosses zero (see V9 migration).
"""
from __future__ import annotations

import heapq
import random
from dataclasses import dataclass
from datetime import datetime, timedelta
from uuid import UUID

from .config import GeneratorConfig
from .model import TIER_PROFILES, EdgeBatch, TaskSpec, World, row_uuid
from .timeutil import lognormal_hours, snap_to_work_time

_TICK = timedelta(microseconds=1)


@dataclass(frozen=True)
class DependencyRow:
    id: UUID
    task_id: UUID
    depends_on_task_id: UUID
    action: str
    source: str
    source_ref: str | None
    created_at: datetime
    sequence: int


@dataclass(frozen=True)
class StateRow:
    id: UUID
    task_id: UUID
    event_type: str
    from_status: str | None
    to_status: str | None
    blocking_count_before: int | None
    blocking_count_after: int | None
    created_at: datetime
    sequence: int


@dataclass
class History:
    dependency_rows: list[DependencyRow]
    state_rows: list[StateRow]
    final_status: dict[UUID, str]


class _State:
    def __init__(self, spec: TaskSpec, rng: random.Random):
        self.spec = spec
        self.rng = rng  # per-task, so a task's own randomness is independent of every other task
        self.status = "PENDING"
        self.deps: dict[UUID, None] = {}  # dicts as ordered sets: deterministic iteration
        self.dependents: dict[UUID, None] = {}
        self.blocking = 0
        self.reopens_left = spec.reopen_budget


def simulate(cfg: GeneratorConfig, world: World) -> History:
    return _Simulator(cfg, world).run()


class _Simulator:
    def __init__(self, cfg: GeneratorConfig, world: World):
        self.cfg, self.end = cfg, cfg.end
        self.projects = {p.id: p for p in world.projects}
        self.states = {t.id: _State(t, random.Random(f"{cfg.seed}:task:{t.id}")) for t in world.tasks}
        self.heap: list = []
        self.counter = 0
        self.now = cfg.start - _TICK
        self.dep_rows: list[DependencyRow] = []
        self.state_rows: list[StateRow] = []
        self.ref_n = 0
        for t in world.tasks:
            self._push(t.created_at, "create", t.id)
        for e in world.edges:
            self._push(e.at, "edges", e)

    def run(self) -> History:
        while self.heap:
            at, _, kind, payload = heapq.heappop(self.heap)
            if at > self.end:
                break
            # strictly increasing: rows of two different causes never share a timestamp, so the
            # history replays unambiguously (rows caused by one event do share it)
            self.now = max(at, self.now + _TICK)
            getattr(self, f"_on_{kind}")(payload)
        self._assert_counters_consistent()
        return History(self.dep_rows, self.state_rows, {i: s.status for i, s in self.states.items()})

    # ---- scheduling ----
    def _push(self, at: datetime, kind: str, payload, snap_rng: random.Random | None = None) -> None:
        if snap_rng is not None:
            at = snap_to_work_time(at, snap_rng)
        self.counter += 1
        heapq.heappush(self.heap, (at, self.counter, kind, payload))

    def _hours(self, st: _State, median: float) -> timedelta:
        return timedelta(hours=lognormal_hours(st.rng, median, self.projects[st.spec.project].sigma))

    def _start_delay(self, st: _State) -> timedelta:
        return self._hours(st, self.projects[st.spec.project].start_hours * TIER_PROFILES[st.spec.tier].start_mult)

    def _work(self, st: _State) -> timedelta:
        tier_mult = TIER_PROFILES[st.spec.tier].work_mult
        return self._hours(st, self.projects[st.spec.project].work_hours * tier_mult * st.spec.work_mult)

    # ---- row emission ----
    def _emit_dependency(self, task_id: UUID, dep_id: UUID, action: str, source: str) -> None:
        seq = len(self.dep_rows) + 1
        ref = None
        if source != "USER":  # USER changes carry no ref (matches the table's partial unique index)
            self.ref_n += 1
            ref = f"{'mind-evt' if source == 'MIND' else 'sys'}-{self.ref_n}"
        self.dep_rows.append(DependencyRow(row_uuid(self.cfg.seed, "dependency_history", seq), task_id, dep_id,
                                           action, source, ref, self.now, seq))

    def _emit_state(self, task_id: UUID, event_type: str, *, from_status=None, to_status=None,
                    before=None, after=None) -> None:
        seq = len(self.state_rows) + 1
        self.state_rows.append(StateRow(row_uuid(self.cfg.seed, "task_state_history", seq), task_id, event_type,
                                        from_status, to_status, before, after, self.now, seq))

    def _set_status(self, st: _State, to: str) -> None:
        self._emit_state(st.spec.id, "STATUS_CHANGED", from_status=st.status, to_status=to)
        st.status = to

    def _unblocked(self, st: _State, before: int) -> None:
        self._emit_state(st.spec.id, "UNBLOCKED", before=before, after=0)
        if st.status == "PENDING":
            self._push(self.now + self._start_delay(st), "start", st.spec.id, st.rng)
        elif st.status == "ACTIVE":
            self._push(self.now + self._work(st) * st.rng.uniform(0.1, 0.6), "finish", st.spec.id, st.rng)

    # ---- handlers ----
    def _on_create(self, task_id: UUID) -> None:
        st = self.states[task_id]
        self._push(self.now + self._start_delay(st), "start", task_id, st.rng)

    def _on_start(self, task_id: UUID) -> None:
        st = self.states[task_id]
        if st.status != "PENDING" or st.blocking > 0:
            return  # a blocked task is rescheduled when it gets unblocked
        hold = st.spec.hold_start_until
        if hold and self.now < hold:
            self._push(hold + timedelta(seconds=st.rng.uniform(0, 3600)), "start", task_id, st.rng)
            return
        self._set_status(st, "ACTIVE")
        self._push(self.now + self._work(st), "finish", task_id, st.rng)

    def _on_finish(self, task_id: UUID) -> None:
        st = self.states[task_id]
        if st.status != "ACTIVE" or st.blocking > 0:
            return
        hold = st.spec.hold_finish_until
        if hold and self.now < hold:
            self._push(hold + timedelta(seconds=st.rng.uniform(0, 3600)), "finish", task_id, st.rng)
            return
        self._set_status(st, "DONE")
        for dep_id in st.dependents:
            d = self.states[dep_id]
            d.blocking -= 1
            if d.blocking == 0:
                self._unblocked(d, before=1)
        if st.reopens_left > 0:
            st.reopens_left -= 1
            lo, hi = st.spec.reopen_gap_hours
            self._push(self.now + timedelta(hours=st.rng.uniform(lo, hi)), "reopen", task_id, st.rng)

    def _on_reopen(self, task_id: UUID) -> None:
        st = self.states[task_id]
        if st.status != "DONE":
            return
        self._set_status(st, "ACTIVE")
        for dep_id in st.dependents:
            d = self.states[dep_id]
            d.blocking += 1
            if d.blocking == 1:
                self._emit_state(dep_id, "BLOCKED", before=0, after=1)
        self._push(self.now + self._work(st), "finish", task_id, st.rng)

    def _on_edges(self, batch: EdgeBatch) -> None:
        a = self.states[batch.task_id]
        before = a.blocking
        if batch.action == "REJECTED":  # audit entry only: no edge, no counter change
            for b_id in batch.depends_on:
                self._emit_dependency(a.spec.id, b_id, "REJECTED", batch.source)
            return
        if batch.action == "ADDED":
            for b_id in batch.depends_on:
                if b_id in a.deps:
                    continue
                b = self.states[b_id]
                a.deps[b_id] = None
                b.dependents[a.spec.id] = None
                self._emit_dependency(a.spec.id, b_id, "ADDED", batch.source)
                if b.status != "DONE":
                    a.blocking += 1
            if before == 0 and a.blocking > 0:
                self._emit_state(a.spec.id, "BLOCKED", before=0, after=a.blocking)
        else:
            for b_id in batch.depends_on:
                if b_id not in a.deps:
                    continue
                b = self.states[b_id]
                del a.deps[b_id]
                del b.dependents[a.spec.id]
                self._emit_dependency(a.spec.id, b_id, "REMOVED", batch.source)
                if b.status != "DONE":
                    a.blocking -= 1
            if before > 0 and a.blocking == 0:
                self._unblocked(a, before=before)

    def _assert_counters_consistent(self) -> None:
        for st in self.states.values():
            expected = sum(1 for b in st.deps if self.states[b].status != "DONE")
            assert st.blocking == expected, f"counter drift on {st.spec.id}: {st.blocking} != {expected}"
