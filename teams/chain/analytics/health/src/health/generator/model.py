from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from uuid import UUID

DEAD_END_KINDS = {"backlog", "abandoned"}  # healthy tasks that never finish; nothing may depend on them
_NAMESPACE = uuid.UUID("5f0c1c1e-0000-4000-8000-00000000c4a1")


def task_uuid(seed: int, label: str) -> UUID:
    """Stable id per (seed, label) so excluding a disease type never renames the other tasks."""
    return uuid.uuid5(_NAMESPACE, f"{seed}:task:{label}")


def row_uuid(seed: int, table: str, sequence: int) -> UUID:
    return uuid.uuid5(_NAMESPACE, f"{seed}:{table}:{sequence}")


TIERS = ("operational", "tactical", "strategic")  # ascending scope; higher tiers wait on lower ones


@dataclass(frozen=True)
class TierProfile:
    share: float  # share of all tasks
    start_mult: float  # multiplies the project's PENDING -> ACTIVE delay
    work_mult: float  # multiplies the project's ACTIVE -> DONE duration


TIER_PROFILES = {
    "operational": TierProfile(0.70, 1.0, 1.0),
    "tactical": TierProfile(0.22, 1.5, 2.5),
    "strategic": TierProfile(0.08, 3.0, 6.0),
}
# A task with no (known) tier behaves like the share-weighted average task.
DEFAULT_TIER_PROFILE = TierProfile(
    1.0,
    sum(p.share * p.start_mult for p in TIER_PROFILES.values()),
    sum(p.share * p.work_mult for p in TIER_PROFILES.values()),
)


def tier_profile(tier: str | None) -> TierProfile:
    """Pace of a tier; a missing or unknown tier gets the average instead of failing."""
    return TIER_PROFILES.get(tier, DEFAULT_TIER_PROFILE)


# which tier a task of the key tier depends on (strategic work waits on tactical work, and so on down)
TIER_DEP_WEIGHTS = {
    "operational": {"operational": 0.9, "tactical": 0.1},
    "tactical": {"operational": 0.6, "tactical": 0.4},
    "strategic": {"tactical": 0.7, "strategic": 0.3},
}


@dataclass(frozen=True)
class Project:
    id: int
    archetype: str  # fast | medium | slow
    start_hours: float  # median PENDING -> ACTIVE delay
    work_hours: float  # median ACTIVE -> DONE duration
    sigma: float  # lognormal spread of both


def typical_days(project: Project, tier: str | None) -> float:
    """Rough elapsed days a healthy task of this tier takes in this project (the peer yardstick)."""
    p = tier_profile(tier)
    # 1.4: working hours only (nights and weekends) stretch elapsed time beyond the raw hours
    return (project.start_hours * p.start_mult + project.work_hours * p.work_mult) / 24 * 1.4


@dataclass
class TaskSpec:
    id: UUID
    project: int
    created_at: datetime
    tier: str | None = "operational"  # None = unknown tier; its pace falls back to the average
    kind: str = "normal"  # normal | slow_legit | reopened_legit | backlog | abandoned | disease | control_chain
    disease_id: str | None = None
    control_id: str | None = None  # set on the links of a healthy control chain
    work_mult: float = 1.0
    hold_start_until: datetime | None = None  # task cannot leave PENDING before this
    hold_finish_until: datetime | None = None  # task cannot reach DONE before this
    reopen_budget: int = 0  # times it is reopened after reaching DONE
    reopen_gap_hours: tuple[float, float] = (24.0, 96.0)


@dataclass(frozen=True)
class EdgeBatch:
    """One write to dependency_history: all edges of one task added (or one removed) at one instant."""

    at: datetime
    action: str  # ADDED | REMOVED
    task_id: UUID
    depends_on: tuple[UUID, ...]
    source: str  # MIND | USER | SYSTEM


@dataclass
class DiseaseInstance:
    """Ground-truth label of one planted pathology. Never an input of the model."""

    id: str
    type: str
    severity: str
    tier: str  # tier of the root task; "similar tasks" means the same tier
    in_stage_a: bool  # False for diseases outside stage A: present in the data, ignored by the detectors
    root_task_id: UUID
    task_ids: list[UUID]  # every task that belongs to the pattern, root included
    start: datetime
    params: dict = field(default_factory=dict)


@dataclass
class ControlChain:
    """A healthy chain built like a slow-chain disease but at normal speed: what peers look like."""

    id: str
    length: int
    shape: str  # linear
    tier: str
    project: int
    task_ids: list[UUID]  # in chain order


def pick_upstream(rng, normal: list[TaskSpec], created_at: datetime, project: int) -> TaskSpec | None:
    """A healthy task (preferably of the same project) created well before, for a planted root to wait on."""
    before = [n for n in normal if n.created_at < created_at - timedelta(days=1) and n.kind not in DEAD_END_KINDS]
    pool = [n for n in before if n.project == project] or before
    return rng.choice(pool) if pool else None


@dataclass
class World:
    projects: list[Project]
    tasks: list[TaskSpec]  # sorted by created_at; position = rank, dependencies only point to lower ranks
    edges: list[EdgeBatch]
    diseases: list[DiseaseInstance]
    controls: list[ControlChain] = field(default_factory=list)
