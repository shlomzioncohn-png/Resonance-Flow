"""Turn an Inbox task event into the `Task` the segmented model scores (#357).

The model was built against the persona eval set's `Task`, which is stricter
than what Inbox sends: on a real task the tier, the effort estimate and the
description may all be missing, and the task_id is a UUID the eval pattern
rejects. This module fills those gaps with fixed, documented defaults so every
task gets a score, without loosening the eval contract itself.
"""

from __future__ import annotations

from datetime import UTC

from mind.evaluation.interfaces import Task, Tier
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.features import QUICK_WIN_CAP_MINUTES

#: Team decision: a task Inbox has not classified yet is *scored* as tactical.
#: Only the score uses this. The tier sent to Chain stays unset, so Chain never
#: sees a tier nobody actually assigned.
DEFAULT_TIER: Tier = "tactical"

#: A missing effort estimate earns no quick-win bonus: at the cap,
#: `features.quick_win` is 0, so an unknown duration is never rewarded.
DEFAULT_EFFORT_MINUTES = QUICK_WIN_CAP_MINUTES

#: The model never reads task_id, and the eval pattern (`^[a-z0-9_]+$`) rejects
#: a UUID, so a fixed placeholder goes in and the real id stays with the caller.
PLACEHOLDER_TASK_ID = "inbox_task"

_TIERS: dict[int, Tier] = {
    TaskTier.TASK_TIER_OPERATIONAL: "operational",
    TaskTier.TASK_TIER_TACTICAL: "tactical",
    TaskTier.TASK_TIER_STRATEGIC: "strategic",
}


def to_scoring_task(event: TaskCreatedEvent | TaskUpdatedEvent) -> Task:
    """Build the model's input from an Inbox task.created / task.updated event.

    `blocked` is always False: blocking is Chain's call, and the score sent to
    Chain is `base_score`, which the model computes before any blocked
    adjustment. `parent_task_id` is None: Inbox's events do not carry it.
    """
    # An unset proto3 enum reads as TASK_TIER_UNSPECIFIED, which is not in _TIERS.
    tier = _TIERS.get(event.tier, DEFAULT_TIER)
    # An unset int reads as 0, and the eval Task needs at least 1 minute.
    effort = event.effort_estimate_minutes
    if effort <= 0:
        effort = DEFAULT_EFFORT_MINUTES
    deadline = event.deadline.ToDatetime(tzinfo=UTC) if event.HasField("deadline") else None

    return Task(
        task_id=PLACEHOLDER_TASK_ID,
        title=event.title,
        # The model only checks whether each keyword appears, so repeating the
        # title adds no score; it just satisfies the eval Task's non-empty rule.
        description=event.description or event.title,
        deadline=deadline,
        effort_estimate=effort,
        tier=tier,
        blocked=False,
        parent_task_id=None,
    )
