from __future__ import annotations

import math
import random
from collections import defaultdict
from datetime import datetime, timedelta

from .config import GeneratorConfig
from .attributes import plan_task_attributes
from .controls import plant_control_chains
from .diseases import plant_diseases
from .model import (DEAD_END_KINDS, TIER_DEP_WEIGHTS, TIER_PROFILES, TIERS, EdgeBatch, Project, TaskSpec,
                    World, task_uuid)
from .timeutil import snap_to_work_time

# (median start delay h, median work h, sigma): a team's pace, before the tier multipliers
ARCHETYPES = {"fast": (1.0, 4.0, 0.5), "medium": (4.0, 12.0, 0.6), "slow": (12.0, 36.0, 0.7)}
DEP_COUNT_WEIGHTS = ((0, 40), (1, 35), (2, 18), (3, 7))
SOURCE_WEIGHTS = (("MIND", 70), ("USER", 25), ("SYSTEM", 5))
LOCAL_DEP_SHARE = 0.8  # share of dependencies that stay inside the task's own project
LOCAL_WINDOW = 5  # ...and point at one of that project's most recent tasks of the chosen tier
FAN_IN_WINDOW = 12  # an integration task looks further back
HUB_ATTACH = 0.6  # chance a new task in a project with an open hub depends on it
BURST_SPREAD_DAYS = 1.5  # std-dev of a burst around its centre


def pick_source(rng: random.Random) -> str:
    return rng.choices([s for s, _ in SOURCE_WEIGHTS], [w for _, w in SOURCE_WEIGHTS])[0]


def plan_world(cfg: GeneratorConfig) -> World:
    projects = _plan_projects(cfg, random.Random(f"{cfg.seed}:projects"))
    normal, edges = _plan_normal(cfg, random.Random(f"{cfg.seed}:normal"))
    tasks = list(normal)
    diseases = plant_diseases(cfg, projects, normal, tasks, edges)
    controls = plant_control_chains(cfg, projects, normal, tasks, edges)
    plan_task_attributes(cfg, projects, tasks)
    tasks.sort(key=lambda t: t.created_at)  # stable: ties keep planning order
    edges.sort(key=lambda e: e.at)
    _assert_acyclic_by_rank(tasks, edges)
    return World(projects=projects, tasks=tasks, edges=edges, diseases=diseases, controls=controls)


def _plan_projects(cfg: GeneratorConfig, rng: random.Random) -> list[Project]:
    projects = []
    for i in range(cfg.n_projects):
        archetype = rng.choice(list(ARCHETYPES))
        start_h, work_h, sigma = ARCHETYPES[archetype]
        pace = rng.lognormvariate(0.0, 0.2)  # teams of the same archetype still differ
        projects.append(Project(i, archetype, start_h * pace, work_h * pace, sigma))
    return projects


def _creation_times(cfg: GeneratorConfig, rng: random.Random) -> list[tuple[datetime, int | None]]:
    """(created_at, forced project) per task: a steady trickle plus bursts where a team opens many at once."""
    span = 0.92 * cfg.history_days * 86400
    n_burst = int(cfg.burst_share * cfg.n_normal_tasks) if cfg.n_bursts else 0
    bursts = [(rng.uniform(0.05, 0.95) * span, rng.sample(range(cfg.n_projects), min(3, cfg.n_projects)))
              for _ in range(cfg.n_bursts)]
    raw: list[tuple[float, int | None]] = [(rng.uniform(0, span), None) for _ in range(cfg.n_normal_tasks - n_burst)]
    for _ in range(n_burst):
        centre, teams = rng.choice(bursts)
        raw.append((min(max(rng.gauss(centre, BURST_SPREAD_DAYS * 86400), 0.0), span), rng.choice(teams)))
    stamped = [(snap_to_work_time(cfg.start + timedelta(seconds=s), rng), p) for s, p in raw]
    return sorted(stamped, key=lambda x: x[0])


class _Pools:
    """Which earlier tasks a new task may depend on."""

    def __init__(self, n_projects: int):
        self.all: list[TaskSpec] = []
        self.by_tier: dict[str, list[TaskSpec]] = defaultdict(list)
        self.by_project_tier: dict[tuple[int, str], list[TaskSpec]] = defaultdict(list)
        self.hubs: dict[int, list[list]] = defaultdict(list)  # project -> [[hub task, slots left]]

    def add(self, spec: TaskSpec) -> None:
        self.all.append(spec)
        self.by_tier[spec.tier].append(spec)
        self.by_project_tier[(spec.project, spec.tier)].append(spec)


def _plan_normal(cfg: GeneratorConfig, rng: random.Random) -> tuple[list[TaskSpec], list[EdgeBatch]]:
    horizon = cfg.history_days * 86400
    cutoff = cfg.start + timedelta(seconds=0.95 * horizon)  # leave room for tasks to play out
    never_ends = cfg.end + timedelta(days=365)
    tier_weights = [TIER_PROFILES[t].share for t in TIERS]

    tasks: list[TaskSpec] = []
    edges: list[EdgeBatch] = []
    pools = _Pools(cfg.n_projects)
    added_at: dict[tuple, datetime] = {}  # (task, dep) -> when the edge was added, for the removal pass

    for i, (created, forced_project) in enumerate(_creation_times(cfg, rng)):
        project = rng.randrange(cfg.n_projects) if forced_project is None else forced_project
        spec = TaskSpec(task_uuid(cfg.seed, f"n{i}"), project, created, tier=rng.choices(TIERS, tier_weights)[0])
        roll = rng.random()
        if roll < cfg.slow_legit_rate:
            spec.kind, spec.work_mult = "slow_legit", rng.uniform(4.0, 8.0)
        elif roll < cfg.slow_legit_rate + cfg.backlog_rate:
            spec.kind, spec.hold_start_until = "backlog", never_ends
        elif roll < cfg.slow_legit_rate + cfg.backlog_rate + cfg.abandoned_rate:
            spec.kind, spec.hold_finish_until = "abandoned", never_ends

        if rng.random() < cfg.fan_in_rate:  # integration task: leans on many earlier ones
            k, window = rng.randint(4, 7), FAN_IN_WINDOW
        else:
            k = rng.choices([c for c, _ in DEP_COUNT_WEIGHTS], [w for _, w in DEP_COUNT_WEIGHTS])[0]
            window = LOCAL_WINDOW
        targets = _pick_targets(rng, spec, k, window, pools)
        if targets:
            at = created + timedelta(seconds=rng.uniform(1, 30))
            edges.append(EdgeBatch(at, "ADDED", spec.id, tuple(t.id for t in targets), pick_source(rng)))
            for t in targets:
                added_at[(spec.id, t.id)] = at
        tasks.append(spec)
        if spec.kind not in DEAD_END_KINDS:
            pools.add(spec)
            if rng.random() < cfg.hub_rate:  # later tasks of this project will pile onto it
                pools.hubs[project].append([spec, rng.randint(3, 8)])

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

    removed_edges = set()
    for (a_id, b_id), at in sorted(added_at.items(), key=lambda kv: kv[1]):
        if rng.random() >= cfg.removal_rate:
            continue
        removed = snap_to_work_time(at + timedelta(days=rng.lognormvariate(math.log(3.0), 0.8)), rng)
        if removed < cutoff:
            edges.append(EdgeBatch(removed, "REMOVED", a_id, (b_id,), rng.choice(("USER", "MIND"))))
            removed_edges.add((a_id, b_id))
    _plan_replanning(cfg, rng, tasks, edges, added_at, removed_edges, cutoff)
    return tasks, edges


REPLAN_GAP_DAYS = (0.5, 2.5)  # about as quick as a recurring blocker's toggles, so tempo alone cannot tell them apart


def _plan_replanning(cfg: GeneratorConfig, rng: random.Random, tasks: list[TaskSpec], edges: list[EdgeBatch],
                     added_at: dict, removed_edges: set, cutoff: datetime) -> None:
    """Healthy look-alikes of a recurring blocker: a dependency is taken out and put back once or twice, so the
    task is blocked 2 or 3 times in a few days. What differs from the disease is that the blocker then finishes
    instead of staying stuck, so a rule that only counts blocks also flags these healthy tasks."""
    by_id = {t.id: t for t in tasks}
    eligible = [(a, b) for (a, b) in added_at if (a, b) not in removed_edges and by_id[a].kind == "normal"
                and (by_id[b].kind == "slow_legit" or by_id[b].tier in ("tactical", "strategic"))]  # long-lived targets
    for a_id, b_id in rng.sample(eligible, min(int(cfg.replan_rate * len(tasks)), len(eligible))):
        at = added_at[(a_id, b_id)]
        for _ in range(rng.choice((1, 2))):  # extra blocks: 2 or 3 blocks in total
            out = snap_to_work_time(at + timedelta(days=rng.uniform(*REPLAN_GAP_DAYS)), rng)  # while the target is still open
            back = snap_to_work_time(out + timedelta(days=rng.uniform(*REPLAN_GAP_DAYS)), rng)
            if back >= cutoff:
                break
            edges.append(EdgeBatch(out, "REMOVED", a_id, (b_id,), rng.choice(("USER", "MIND"))))
            edges.append(EdgeBatch(back, "ADDED", a_id, (b_id,), pick_source(rng)))
            at = back
        by_id[a_id].kind = "replanned_legit"


def _pick_targets(rng: random.Random, spec: TaskSpec, k: int, window: int, pools: _Pools) -> list[TaskSpec]:
    chosen: dict = {}
    hubs = pools.hubs[spec.project]
    if hubs and rng.random() < HUB_ATTACH:
        entry = rng.choice(hubs)
        chosen[entry[0].id] = entry[0]
        entry[1] -= 1
        if entry[1] <= 0:
            hubs.remove(entry)
    weights = TIER_DEP_WEIGHTS[spec.tier]
    for _ in range(k):
        tier = rng.choices(list(weights), list(weights.values()))[0]
        local = pools.by_project_tier[(spec.project, tier)][-window:]
        pool = local if local and rng.random() < LOCAL_DEP_SHARE else pools.by_tier[tier]
        pool = pool or pools.all  # e.g. the very first strategic task has no tactical work to wait on yet
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
