from __future__ import annotations

import math
import random
from datetime import timedelta

from .config import GeneratorConfig
from .diseases import plant_diseases
from .model import DEAD_END_KINDS, EdgeBatch, Project, TaskSpec, World, task_uuid
from .timeutil import snap_to_work_time

# (median start delay h, median work h, sigma)
ARCHETYPES = {"fast": (1.0, 4.0, 0.5), "medium": (4.0, 12.0, 0.6), "slow": (12.0, 36.0, 0.7)}
DEP_COUNT_WEIGHTS = ((0, 40), (1, 35), (2, 18), (3, 7))
SOURCE_WEIGHTS = (("MIND", 70), ("USER", 25), ("SYSTEM", 5))
LOCAL_DEP_SHARE = 0.8  # share of dependencies that stay inside the task's own project
LOCAL_WINDOW = 5  # ...and point at one of that project's most recent tasks


def pick_source(rng: random.Random) -> str:
    return rng.choices([s for s, _ in SOURCE_WEIGHTS], [w for _, w in SOURCE_WEIGHTS])[0]


def plan_world(cfg: GeneratorConfig) -> World:
    projects = _plan_projects(cfg, random.Random(f"{cfg.seed}:projects"))
    normal, edges = _plan_normal(cfg, random.Random(f"{cfg.seed}:normal"))
    tasks = list(normal)
    diseases = plant_diseases(cfg, normal, tasks, edges)
    tasks.sort(key=lambda t: t.created_at)  # stable: ties keep planning order
    edges.sort(key=lambda e: e.at)
    _assert_acyclic_by_rank(tasks, edges)
    return World(projects=projects, tasks=tasks, edges=edges, diseases=diseases)


def _plan_projects(cfg: GeneratorConfig, rng: random.Random) -> list[Project]:
    projects = []
    for i in range(cfg.n_projects):
        archetype = rng.choice(list(ARCHETYPES))
        start_h, work_h, sigma = ARCHETYPES[archetype]
        pace = rng.lognormvariate(0.0, 0.2)  # teams of the same archetype still differ
        projects.append(Project(i, archetype, start_h * pace, work_h * pace, sigma))
    return projects


def _plan_normal(cfg: GeneratorConfig, rng: random.Random) -> tuple[list[TaskSpec], list[EdgeBatch]]:
    horizon = cfg.history_days * 86400
    cutoff = cfg.start + timedelta(seconds=0.95 * horizon)  # leave room for tasks to play out
    times = sorted(
        snap_to_work_time(cfg.start + timedelta(seconds=rng.uniform(0, 0.92 * horizon)), rng)
        for _ in range(cfg.n_normal_tasks)
    )

    tasks: list[TaskSpec] = []
    edges: list[EdgeBatch] = []
    targetable: list[TaskSpec] = []  # tasks other tasks may depend on (not backlog/abandoned)
    by_project: dict[int, list[TaskSpec]] = {p: [] for p in range(cfg.n_projects)}
    never_ends = cfg.end + timedelta(days=365)
    added_at: dict[tuple, object] = {}  # (task, dep) -> when the edge was added, for the removal pass

    for i, created in enumerate(times):
        spec = TaskSpec(task_uuid(cfg.seed, f"n{i}"), rng.randrange(cfg.n_projects), created)
        roll = rng.random()
        if roll < cfg.slow_legit_rate:
            spec.kind, spec.work_mult = "slow_legit", rng.uniform(4.0, 8.0)
        elif roll < cfg.slow_legit_rate + cfg.reopened_legit_rate:
            spec.kind, spec.reopen_budget, spec.reopen_gap_hours = "reopened_legit", 1, (24.0, 120.0)
        elif roll < cfg.slow_legit_rate + cfg.reopened_legit_rate + cfg.backlog_rate:
            spec.kind, spec.hold_start_until = "backlog", never_ends
        elif roll < cfg.slow_legit_rate + cfg.reopened_legit_rate + cfg.backlog_rate + cfg.abandoned_rate:
            spec.kind, spec.hold_finish_until = "abandoned", never_ends

        k = rng.choices([c for c, _ in DEP_COUNT_WEIGHTS], [w for _, w in DEP_COUNT_WEIGHTS])[0]
        targets = _pick_targets(rng, k, targetable, by_project[spec.project])
        if targets:
            at = created + timedelta(seconds=rng.uniform(1, 30))
            edges.append(EdgeBatch(at, "ADDED", spec.id, tuple(t.id for t in targets), pick_source(rng)))
            for t in targets:
                added_at[(spec.id, t.id)] = at
        tasks.append(spec)
        if spec.kind not in DEAD_END_KINDS:
            targetable.append(spec)
            by_project[spec.project].append(spec)

    # edges added long after both tasks exist (a dependency discovered later)
    for _ in range(int(cfg.late_dependency_rate * len(tasks))):
        a_idx = rng.randrange(1, len(tasks))
        b_idx = rng.randrange(0, a_idx)
        a, b = tasks[a_idx], tasks[b_idx]
        if (a.id, b.id) in added_at or b.kind in DEAD_END_KINDS:
            continue
        at = snap_to_work_time(max(a.created_at, b.created_at) + timedelta(hours=rng.uniform(1, 480)), rng)
        if at >= cutoff:
            continue
        edges.append(EdgeBatch(at, "ADDED", a.id, (b.id,), pick_source(rng)))
        added_at[(a.id, b.id)] = at

    # cross-tenant style rejections: logged in dependency_history but never become edges
    for _ in range(int(cfg.rejected_rate * len(tasks))):
        a_idx = rng.randrange(1, len(tasks))
        a, b = tasks[a_idx], tasks[rng.randrange(0, a_idx)]
        at = snap_to_work_time(a.created_at + timedelta(hours=rng.uniform(0.1, 200)), rng)
        if (a.id, b.id) not in added_at and at < cutoff:
            edges.append(EdgeBatch(at, "REJECTED", a.id, (b.id,), "MIND"))

    for (a_id, b_id), at in sorted(added_at.items(), key=lambda kv: kv[1]):
        if rng.random() >= cfg.removal_rate:
            continue
        removed = snap_to_work_time(at + timedelta(days=rng.lognormvariate(math.log(3.0), 0.8)), rng)
        if removed < cutoff:
            edges.append(EdgeBatch(removed, "REMOVED", a_id, (b_id,), rng.choice(("USER", "MIND"))))
    return tasks, edges


def _pick_targets(rng, k, earlier, local) -> list[TaskSpec]:
    chosen: dict = {}
    for _ in range(k):
        pool = local[-LOCAL_WINDOW:] if local and rng.random() < LOCAL_DEP_SHARE else earlier
        if pool:
            target = rng.choice(pool)
            chosen[target.id] = target
    return list(chosen.values())


def _assert_acyclic_by_rank(tasks: list[TaskSpec], edges: list[EdgeBatch]) -> None:
    """Dependencies must only point at earlier-created tasks; that makes cycles impossible."""
    rank = {t.id: i for i, t in enumerate(tasks)}
    for e in edges:
        for dep in e.depends_on:
            if rank[dep] >= rank[e.task_id]:
                raise AssertionError(f"edge {e.task_id} -> {dep} points forward in creation order")
