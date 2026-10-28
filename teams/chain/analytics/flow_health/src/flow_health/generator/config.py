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
    replan_rate: float = 0.20  # tried on this share of tasks: a dependency is pulled out and put back (2-3 blocks if its blocker stays open)
    rejected_rate: float = 0.01  # REJECTED dependency_history rows per task: noise a consumer must ignore
    missing_tier_rate: float = 0.05  # share of tasks whose tier the analysis does not get to see
    control_chains_per_group: int = 6  # healthy chains per (length, tier, team pace): peers of the slow chain
    n_bursts: int = 12  # load spikes: a team opens many tasks within a day or two
    burst_share: float = 0.15  # share of normal tasks created inside a burst
    fan_in_rate: float = 0.03  # tasks that integrate 4-7 earlier tasks
    hub_rate: float = 0.03  # tasks that several later tasks of the same project depend on
    # Inbox's deadline and effort estimate (see attributes.py)
    has_deadline_rate: float = 0.70  # share of tasks with a deadline (Inbox: 30% have none)
    has_estimate_rate: float = 0.60  # share of tasks with an effort estimate (Inbox: 40% have none)
    estimate_noise: float = 0.3  # lognormal sigma of how far an estimate is from what a healthy task takes
    estimate_step_minutes: int = 15  # estimates are whole multiples of this (Inbox: a quarter of an hour)
    # shares of the deadlines that are: close to the expected completion, within a week, within a month, within three months
    deadline_mix: tuple[float, float, float, float] = (0.21, 0.29, 0.29, 0.21)
    # severity score (see severity.py and docs/severity.md): weights of abnormality, overrun, deadline pressure, scale
    severity_weights: tuple[float, float, float, float] = (0.35, 0.30, 0.25, 0.10)
    severity_high_from: float = 66.0  # score from which a case is "high"
    severity_medium_from: float = 33.0  # ... and from which it is "medium"

    def __post_init__(self) -> None:
        from .diseases import DISEASE_TYPES  # lazy: the diseases package imports this module

        unknown = set(self.excluded_diseases) - set(DISEASE_TYPES)
        if unknown:
            raise ValueError(f"unknown disease types: {sorted(unknown)}")
        if self.start.tzinfo is None:
            raise ValueError("start must be timezone-aware")
        for name in ("has_deadline_rate", "has_estimate_rate"):
            if not 0.0 <= getattr(self, name) <= 1.0:
                raise ValueError(f"{name} must be between 0 and 1")
        if self.estimate_noise < 0 or self.estimate_step_minutes < 1:
            raise ValueError("estimate_noise must not be negative and estimate_step_minutes must be at least 1")
        for name in ("deadline_mix", "severity_weights"):
            values = getattr(self, name)
            if min(values) < 0 or abs(sum(values) - 1.0) > 1e-9:
                raise ValueError(f"{name} must be non-negative and add up to 1")
        if not 0.0 <= self.severity_medium_from < self.severity_high_from <= 100.0:
            raise ValueError("severity thresholds must satisfy 0 <= medium < high <= 100")

    @property
    def end(self) -> datetime:
        return self.start + timedelta(days=self.history_days)
