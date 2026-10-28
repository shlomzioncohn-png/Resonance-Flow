"""Deadline and effort estimate of every task, shaped like Inbox's `deadline` and `effort_estimate_minutes`.

Inbox's dataset design (Task_Dataset_Design.md) has no deadline on 30% of tasks and no estimate on 40%; the
rest of its deadline mix (overdue, this week, this month, later) is a snapshot at one "now", so here the
same mix is expressed as how far after creation the deadline falls. Both are fixed for the life of a task.
The rates and the mix are `GeneratorConfig` fields.
"""
from __future__ import annotations

import random
from datetime import timedelta

from .config import GeneratorConfig
from .model import Project, TaskSpec, typical_days, typical_minutes

# How far after creation the deadline falls, per class of `GeneratorConfig.deadline_mix`. "tight" lands near
# the expected completion (a multiple of what the task normally takes), so some are missed; the others are days.
_LAGS = ("tight", (1.0, 7.0), (7.0, 30.0), (30.0, 90.0))
TIGHT_MULTIPLE = (0.5, 3.0)


def plan_task_attributes(cfg: GeneratorConfig, projects: list[Project], tasks: list[TaskSpec]) -> None:
    """Fills `deadline` and `effort_estimate_minutes` in place; each task has its own seeded RNG."""
    for task in tasks:
        rng = random.Random(f"{cfg.seed}:attrs:{task.id}")
        project = projects[task.project]
        if rng.random() < cfg.has_estimate_rate:
            # An estimate says what the task should take. A deliberately slow task (disease, hold) is estimated
            # as a normal one, so overrunning it shows; a task that is slow by nature (`slow_legit`) is not.
            planned = typical_minutes(project, task.tier) * (task.work_mult if task.kind == "slow_legit" else 1.0)
            minutes = planned * rng.lognormvariate(0.0, cfg.estimate_noise)
            step = cfg.estimate_step_minutes
            task.effort_estimate_minutes = max(step, round(minutes / step) * step)
        if rng.random() < cfg.has_deadline_rate:
            roll, acc = rng.random(), 0.0
            for share, bucket in zip(cfg.deadline_mix, _LAGS):
                acc += share
                if roll < acc:
                    break
            if bucket == "tight":
                lag_days = max(0.25, typical_days(project, task.tier) * rng.uniform(*TIGHT_MULTIPLE))
            else:
                lag_days = rng.uniform(*bucket)
            task.deadline = (task.created_at + timedelta(days=lag_days)).replace(second=0, microsecond=0)
