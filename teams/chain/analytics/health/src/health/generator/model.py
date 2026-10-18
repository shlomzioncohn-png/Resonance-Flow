from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from uuid import UUID

DEAD_END_KINDS = {"backlog", "abandoned"}  # healthy tasks that never finish; nothing may depend on them
_NAMESPACE = uuid.UUID("5f0c1c1e-0000-4000-8000-00000000c4a1")


def task_uuid(seed: int, label: str) -> UUID:
    """Stable id per (seed, label) so excluding a disease type never renames the other tasks."""
    return uuid.uuid5(_NAMESPACE, f"{seed}:task:{label}")


def row_uuid(seed: int, table: str, sequence: int) -> UUID:
    return uuid.uuid5(_NAMESPACE, f"{seed}:{table}:{sequence}")


@dataclass(frozen=True)
class Project:
    id: int
    archetype: str  # fast | medium | slow
    start_hours: float  # median PENDING -> ACTIVE delay
    work_hours: float  # median ACTIVE -> DONE duration
    sigma: float  # lognormal spread of both


@dataclass
class TaskSpec:
    id: UUID
    project: int
    created_at: datetime
    kind: str = "normal"  # normal | slow_legit | reopened_legit | disease
    disease_id: str | None = None
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
    root_task_id: UUID
    task_ids: list[UUID]  # every task that belongs to the pattern, root included
    start: datetime
    params: dict = field(default_factory=dict)


@dataclass
class World:
    projects: list[Project]
    tasks: list[TaskSpec]  # sorted by created_at; position = rank, dependencies only point to lower ranks
    edges: list[EdgeBatch]
    diseases: list[DiseaseInstance]
