"""The contract every disease module follows, and the planting context handed to it.

A disease module defines a `DISEASE = Disease(...)`; the package discovers it by itself, so adding a
pathology is one new file and nothing else changes.
"""
from __future__ import annotations

import random
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Callable

from ..config import SEVERITIES, GeneratorConfig
from ..model import DEAD_END_KINDS, TIERS, EdgeBatch, Project, TaskSpec, task_uuid, typical_days
from ..timeutil import snap_to_work_time

T0_RANGE = (0.03, 0.55)  # resolved patterns start early enough to finish before the history ends
ONGOING_SHARE = 0.35  # of the diseases that can be ongoing: still unresolved when the history ends


def by_severity(values: tuple, severity: str):
    """Picks the (subtle, moderate, severe) entry."""
    return values[SEVERITIES.index(severity)]


@dataclass(frozen=True)
class Disease:
    name: str  # also the `type` in the ground truth
    plant: Callable[["Planter", str], tuple[TaskSpec, dict]]  # (planter, severity) -> (root task, params)
    # Set when the pattern can still be unfolding at the end of the history: its nominal length in days.
    nominal_days: Callable[["Planter", str], float] | None = None
    tiers: tuple[str, ...] = TIERS  # tiers its root task may have


class Planter:
    """Creates a disease's tasks and edges, and tracks which tasks belong to it."""

    def __init__(self, cfg: GeneratorConfig, projects: list[Project], disease: Disease, severity: str,
                 instance_no: int, normal: list[TaskSpec], tasks: list[TaskSpec], edges: list[EdgeBatch]):
        self.cfg, self.disease, self.severity, self.normal = cfg, disease, severity, normal
        self.disease_id = f"{disease.name}-{severity}-{instance_no}"
        self._tasks, self._edges = tasks, edges
        self.rng = random.Random(f"{cfg.seed}:{self.disease_id}")
        self.members: list[TaskSpec] = []
        self.project = self.rng.randrange(cfg.n_projects)
        self.project_spec = projects[self.project]
        self.tier = self.rng.choice(disease.tiers)
        self.nominal = disease.nominal_days(self, severity) if disease.nominal_days else None
        self.ongoing = self.nominal is not None and self.rng.random() < ONGOING_SHARE
        if self.ongoing:  # begun recently enough that it is only 50-100% of its way through at the end
            begin = cfg.end - timedelta(days=self.nominal * self.rng.uniform(0.5, 1.0))
        else:
            begin = cfg.start + timedelta(seconds=self.rng.uniform(*T0_RANGE) * cfg.history_days * 86400)
        self.t0 = snap_to_work_time(begin, self.rng)
        self.cursor = self.t0

    # ---- yardsticks ----
    def typical_days(self, tier: str | None = None) -> float:
        return typical_days(self.project_spec, tier or self.tier)

    def relative_days(self, floors: tuple, factors: tuple, cap: float) -> float:
        """Tier-relative duration: a multiple of what peers of the same tier take, never below a floor."""
        i = SEVERITIES.index(self.severity)
        return min(cap, max(floors[i], factors[i] * self.typical_days()))

    def dependent_tier(self) -> str:
        """Tasks waiting on the root sit in its tier or the one above (higher tiers wait on lower ones)."""
        i = TIERS.index(self.tier)
        return self.rng.choice(TIERS[i:i + 2])

    # ---- building blocks ----
    def hold_end(self, days: float) -> datetime:
        """When a held task may finish: normally t0 + days, but never within the history if ongoing."""
        return self.cfg.end + timedelta(days=30) if self.ongoing else self.t0 + timedelta(days=days)

    def advance(self, hours: float) -> datetime:
        """Next creation time, strictly after the previous one so rank order matches plan order."""
        nxt = snap_to_work_time(self.cursor + timedelta(hours=hours), self.rng)
        self.cursor = max(nxt, self.cursor + timedelta(seconds=60))
        return self.cursor

    def task(self, created_at: datetime, **overrides) -> TaskSpec:
        overrides.setdefault("tier", self.tier)
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
        before = [n for n in self.normal
                  if n.created_at < root.created_at - timedelta(days=1) and n.kind not in DEAD_END_KINDS]
        pool = [n for n in before if n.project == root.project] or before
        if pool:
            self.depend(root, [self.rng.choice(pool)])
