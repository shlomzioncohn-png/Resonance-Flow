from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

SEVERITIES = ("subtle", "moderate", "severe")
DISEASE_TYPES = (
    "chronic_blocking",
    "recurring_blocker",
    "slow_chain",
    "growing_bottleneck",
    "stuck_subgraph",
)
# Kept out of the dev dataset so the model is never tuned against it; planted only with --include-holdout.
HOLDOUT_DISEASES = ("stuck_subgraph",)


@dataclass(frozen=True)
class GeneratorConfig:
    seed: int = 42
    start: datetime = datetime(2026, 1, 5, tzinfo=timezone.utc)  # a Monday
    history_days: int = 180
    n_normal_tasks: int = 5000
    n_projects: int = 20
    instances_per_severity: int = 5
    excluded_diseases: tuple[str, ...] = HOLDOUT_DISEASES
    slow_legit_rate: float = 0.03  # healthy tasks that are just slow: hard negatives
    reopened_legit_rate: float = 0.03  # healthy tasks reopened once
    backlog_rate: float = 0.08  # healthy tasks nobody ever picks up; nothing depends on them
    abandoned_rate: float = 0.04  # healthy tasks started and never finished; nothing depends on them
    late_dependency_rate: float = 0.08  # extra edges added after creation, per task
    removal_rate: float = 0.04  # share of edges later removed
    rejected_rate: float = 0.01  # REJECTED dependency_history rows per task: noise a consumer must ignore

    def __post_init__(self) -> None:
        unknown = set(self.excluded_diseases) - set(DISEASE_TYPES)
        if unknown:
            raise ValueError(f"unknown disease types: {sorted(unknown)}")
        if self.start.tzinfo is None:
            raise ValueError("start must be timezone-aware")

    @property
    def end(self) -> datetime:
        return self.start + timedelta(days=self.history_days)
