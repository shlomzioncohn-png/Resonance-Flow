"""Planted pathologies. Each plant_* builds tasks/edges/holds that the simulation then plays out.

Every disease draws from its own RNG (seeded by its id), so excluding one type, or changing
instances_per_severity, never changes the data of the others.
"""
from __future__ import annotations

import random
from datetime import datetime, timedelta

from .config import DISEASE_TYPES, SEVERITIES, GeneratorConfig
from .model import DEAD_END_KINDS, DiseaseInstance, EdgeBatch, TaskSpec, task_uuid
from .timeutil import snap_to_work_time

T0_RANGE = (0.03, 0.55)  # resolved patterns start early enough to finish before the history ends
ONGOING_SHARE = 0.35  # of the hold-based diseases: still unresolved at the end of the history
# nominal duration in days (subtle, moderate, severe) of the diseases that can be ongoing
NOMINAL_DAYS = {"chronic_blocking": (14, 30, 60), "stuck_subgraph": (20, 40, 70), "growing_bottleneck": (20, 30, 45)}


def _by_sev(values: tuple, severity: str):
    return values[SEVERITIES.index(severity)]


class _Planter:
    def __init__(self, cfg: GeneratorConfig, disease_id: str, normal: list[TaskSpec],
                 tasks: list[TaskSpec], edges: list[EdgeBatch], nominal_days: float | None = None):
        self.cfg, self.disease_id, self.normal = cfg, disease_id, normal
        self._tasks, self._edges = tasks, edges
        self.rng = random.Random(f"{cfg.seed}:{disease_id}")
        self.members: list[TaskSpec] = []
        self.project = self.rng.randrange(cfg.n_projects)
        horizon = cfg.history_days * 86400
        self.ongoing = nominal_days is not None and self.rng.random() < ONGOING_SHARE
        if self.ongoing:  # begun recently enough that it is only 50-100% of its way through at the end
            begin = cfg.end - timedelta(days=nominal_days * self.rng.uniform(0.5, 1.0))
        else:
            begin = cfg.start + timedelta(seconds=self.rng.uniform(*T0_RANGE) * horizon)
        self.t0 = snap_to_work_time(begin, self.rng)
        self.cursor = self.t0

    def hold_end(self, days: float) -> datetime:
        """When a held task may finish: normally t0 + days, but never within the history if ongoing."""
        return self.cfg.end + timedelta(days=30) if self.ongoing else self.t0 + timedelta(days=days)

    def advance(self, hours: float) -> datetime:
        """Next creation time, strictly after the previous one so rank order matches plan order."""
        nxt = snap_to_work_time(self.cursor + timedelta(hours=hours), self.rng)
        self.cursor = max(nxt, self.cursor + timedelta(seconds=60))
        return self.cursor

    def task(self, created_at: datetime, **overrides) -> TaskSpec:
        spec = TaskSpec(task_uuid(self.cfg.seed, f"{self.disease_id}:{len(self.members)}"),
                        self.project, created_at, kind="disease", disease_id=self.disease_id, **overrides)
        self.members.append(spec)
        self._tasks.append(spec)
        return spec

    def depend(self, task: TaskSpec, targets: list[TaskSpec]) -> None:
        at = task.created_at + timedelta(seconds=self.rng.uniform(1, 30))
        self._edges.append(EdgeBatch(at, "ADDED", task.id, tuple(t.id for t in targets), "MIND"))

    def attach_upstream(self, root: TaskSpec) -> None:
        """Most real blockers are themselves downstream of something healthy."""
        if self.rng.random() >= 0.6:
            return
        before = [n for n in self.normal if n.created_at < root.created_at - timedelta(days=1) and n.kind not in DEAD_END_KINDS]
        same_project = [n for n in before if n.project == root.project]
        pool = same_project or before
        if pool:
            self.depend(root, [self.rng.choice(pool)])


def _chronic_blocking(p: _Planter, sev: str) -> tuple[TaskSpec, dict]:
    hold_days = _by_sev(NOMINAL_DAYS["chronic_blocking"], sev) * p.rng.uniform(0.9, 1.15)
    blocker = p.task(p.t0, hold_finish_until=p.hold_end(hold_days))
    p.attach_upstream(blocker)
    n = _by_sev((1, 2, 3), sev)
    for _ in range(n):
        dependent = p.task(p.advance(p.rng.uniform(1, 6)))
        p.depend(dependent, [blocker])
    return blocker, {"hold_days": round(hold_days, 1), "dependents": n}


def _recurring_blocker(p: _Planter, sev: str) -> tuple[TaskSpec, dict]:
    cycles = _by_sev((3, 6, 10), sev)
    blocker = p.task(p.t0, reopen_budget=cycles - 1, reopen_gap_hours=(24.0, 96.0),
                     hold_finish_until=p.t0 + timedelta(days=2))  # first DONE only after dependents are attached
    p.attach_upstream(blocker)
    n = _by_sev((1, 1, 2), sev)
    for _ in range(n):
        # held back so they stay PENDING and visibly flap BLOCKED/UNBLOCKED instead of finishing in the first window
        dependent = p.task(p.advance(p.rng.uniform(1, 6)),
                           hold_start_until=p.t0 + timedelta(days=cycles * 6 + 5))
        p.depend(dependent, [blocker])
    return blocker, {"cycles": cycles, "dependents": n}


def _slow_chain(p: _Planter, sev: str) -> tuple[TaskSpec, dict]:
    length = _by_sev((4, 7, 12), sev)
    factor = _by_sev((3.0, 5.0, 8.0), sev)
    chain: list[TaskSpec] = []
    for i in range(length):
        link = p.task(p.t0 if i == 0 else p.advance(p.rng.uniform(0.5, 3)), work_mult=factor)
        if i == 0:
            p.attach_upstream(link)
        else:
            p.depend(link, [chain[-1]])
        chain.append(link)
    return chain[0], {"length": length, "work_factor": factor}


def _growing_bottleneck(p: _Planter, sev: str) -> tuple[TaskSpec, dict]:
    n = _by_sev((6, 12, 24), sev)
    window_days = _by_sev(NOMINAL_DAYS["growing_bottleneck"], sev)
    bottleneck = p.task(p.t0, hold_finish_until=p.hold_end(window_days + 15))
    p.attach_upstream(bottleneck)
    step = window_days * 24 / n
    for _ in range(n):  # dependents trickle in over the window instead of arriving together
        created = p.advance(step * p.rng.uniform(0.7, 1.3))
        if created > p.cfg.end - timedelta(days=1):  # an ongoing one is cut off by the end of the history
            break
        p.depend(p.task(created), [bottleneck])
    return bottleneck, {"dependents": len(p.members) - 1, "planned_dependents": n, "window_days": window_days}


def _stuck_subgraph(p: _Planter, sev: str) -> tuple[TaskSpec, dict]:
    size = _by_sev((4, 8, 15), sev)
    hold_days = _by_sev(NOMINAL_DAYS["stuck_subgraph"], sev) * p.rng.uniform(0.9, 1.15)
    root = p.task(p.t0, hold_finish_until=p.hold_end(hold_days))
    p.attach_upstream(root)
    nodes = [root]
    for i in range(size):
        # the first two nodes force depth >= 2; the rest attach anywhere, so the cluster is a real subtree
        parent = nodes[-1] if i < 2 else p.rng.choice(nodes)
        node = p.task(p.advance(p.rng.uniform(0.5, 4)))
        p.depend(node, [parent])
        nodes.append(node)
    return root, {"size": size, "hold_days": round(hold_days, 1)}


_PLANTERS = {
    "chronic_blocking": _chronic_blocking,
    "recurring_blocker": _recurring_blocker,
    "slow_chain": _slow_chain,
    "growing_bottleneck": _growing_bottleneck,
    "stuck_subgraph": _stuck_subgraph,
}
assert set(_PLANTERS) == set(DISEASE_TYPES)


def plant_diseases(cfg: GeneratorConfig, normal: list[TaskSpec], tasks: list[TaskSpec],
                   edges: list[EdgeBatch]) -> list[DiseaseInstance]:
    out = []
    for dtype in DISEASE_TYPES:
        if dtype in cfg.excluded_diseases:
            continue
        for severity in SEVERITIES:
            for k in range(cfg.instances_per_severity):
                nominal = NOMINAL_DAYS.get(dtype)
                planter = _Planter(cfg, f"{dtype}-{severity}-{k + 1}", normal, tasks, edges,
                                   _by_sev(nominal, severity) if nominal else None)
                root, params = _PLANTERS[dtype](planter, severity)
                out.append(DiseaseInstance(
                    id=planter.disease_id, type=dtype, severity=severity, root_task_id=root.id,
                    task_ids=[m.id for m in planter.members], start=planter.t0, params=params))
    return out
