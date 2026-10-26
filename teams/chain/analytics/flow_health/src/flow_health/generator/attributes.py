"""Deadline and effort estimate of every task, shaped like Inbox's `deadline` and `effort_estimate_minutes`.

Inbox's dataset design (Task_Dataset_Design.md) has no deadline on 30% of tasks and no estimate on 40%; the
rest of its deadline mix (overdue, this week, this month, later) is a snapshot at one "now", so here the
same mix is expressed as how far after creation the deadline falls. Both are fixed for the life of a task.
"""
from __future__ import annotations

import random
from datetime import timedelta

from .config import GeneratorConfig
from .model import Project, TaskSpec, typical_days, typical_minutes

HAS_DEADLINE = 0.70
HAS_ESTIMATE = 0.60
ESTIMATE_NOISE = 0.3  # lognormal sigma: people estimate roughly, not exactly
ESTIMATE_STEP_MINUTES = 15  # Inbox's smallest estimate is a quarter of an hour

# (share of tasks that have a deadline, lag). "tight" lands near the expected completion, so some are missed.
_LAG_BUCKETS = (
    (0.21, "tight"),
    (0.29, (1.0, 7.0)),  # days
    (0.29, (7.0, 30.0)),
    (0.21, (30.0, 90.0)),
)


def plan_task_attributes(cfg: GeneratorConfig, projects: list[Project], tasks: list[TaskSpec]) -> None:
    """Fills `deadline` and `effort_estimate_minutes` in place; each task has its own seeded RNG."""
    for task in tasks:
        rng = random.Random(f"{cfg.seed}:attrs:{task.id}")
        project = projects[task.project]
        if rng.random() < HAS_ESTIMATE:
            # An estimate says what the task should take. A deliberately slow task (disease, hold) is estimated
            # as a normal one, so overrunning it shows; a task that is slow by nature (`slow_legit`) is not.
            planned = typical_minutes(project, task.tier) * (task.work_mult if task.kind == "slow_legit" else 1.0)
            minutes = planned * rng.lognormvariate(0.0, ESTIMATE_NOISE)
            task.effort_estimate_minutes = max(ESTIMATE_STEP_MINUTES,
                                               round(minutes / ESTIMATE_STEP_MINUTES) * ESTIMATE_STEP_MINUTES)
        if rng.random() < HAS_DEADLINE:
            roll, acc = rng.random(), 0.0
            for share, bucket in _LAG_BUCKETS:
                acc += share
                if roll < acc:
                    break
            if bucket == "tight":
                lag_days = max(0.25, typical_days(project, task.tier) * rng.uniform(0.5, 3.0))
            else:
                lag_days = rng.uniform(*bucket)
            task.deadline = (task.created_at + timedelta(days=lag_days)).replace(second=0, microsecond=0)
