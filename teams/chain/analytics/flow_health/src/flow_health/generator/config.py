from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

SEVERITIES = ("subtle", "moderate", "severe")
# Left out of the dev dataset so nothing is tuned against it; the eval dataset plants every type.
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
    backlog_rate: float = 0.08  # healthy tasks nobody ever picks up; nothing depends on them
    abandoned_rate: float = 0.04  # healthy tasks started and never finished; nothing depends on them
    late_dependency_rate: float = 0.08  # extra edges added after creation, per task
    removal_rate: float = 0.04  # share of edges later removed
    rejected_rate: float = 0.01  # REJECTED dependency_history rows per task: noise a consumer must ignore
    missing_tier_rate: float = 0.05  # share of tasks whose tier the analysis does not get to see
    control_chains_per_group: int = 6  # healthy chains per (length, tier, team pace): peers of the slow chain
    n_bursts: int = 12  # load spikes: a team opens many tasks within a day or two
    burst_share: float = 0.15  # share of normal tasks created inside a burst
    fan_in_rate: float = 0.03  # tasks that integrate 4-7 earlier tasks
    hub_rate: float = 0.03  # tasks that several later tasks of the same project depend on

    def __post_init__(self) -> None:
        from .diseases import DISEASE_TYPES  # lazy: the diseases package imports this module

        unknown = set(self.excluded_diseases) - set(DISEASE_TYPES)
        if unknown:
            raise ValueError(f"unknown disease types: {sorted(unknown)}")
        if self.start.tzinfo is None:
            raise ValueError("start must be timezone-aware")

    @property
    def end(self) -> datetime:
        return self.start + timedelta(days=self.history_days)
