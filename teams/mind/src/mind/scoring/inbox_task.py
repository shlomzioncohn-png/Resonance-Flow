"""Turn an Inbox task event into a `ScorableTask` the segmented model scores (#357).

Empty stays empty: a missing tier, effort estimate or description goes to the
model as None, and the model decides how to score it (see `inputs.py` and
`segmented_model.rule_tier`). Nothing here fills a default.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime

from mind.evaluation.interfaces import Tier
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent

# Inbox writes the tier in upper case and the model keys its rules in lower case.
_TIERS: dict[int, Tier] = {
    TaskTier.TASK_TIER_OPERATIONAL: "operational",
    TaskTier.TASK_TIER_TACTICAL: "tactical",
    TaskTier.TASK_TIER_STRATEGIC: "strategic",
}


@dataclass(frozen=True)
class InboxTask:
    """A real task as the model reads it. Fits `ScorableTask`."""

    task_id: str
    title: str
    description: str | None
    deadline: datetime | None
    effort_estimate: int | None
    tier: Tier | None
    # Always False: blocking is Chain's call and Inbox has no such field. Only
    # `base_score` may be sent to Chain, since `score` is adjusted by `blocked`
    # and would come out too high for a task that is really blocked.
    blocked: bool = False
    # Inbox's events do not carry it.
    parent_task_id: str | None = None


def to_scoring_task(event: TaskCreatedEvent | TaskUpdatedEvent) -> InboxTask:
    """Build the model's input from an Inbox task.created / task.updated event."""
    # Inbox names the id differently on the two topics.
    task_id = event.id if isinstance(event, TaskCreatedEvent) else event.task_id

    return InboxTask(
        task_id=task_id,
        title=event.title,
        description=event.description or None,
        # Aware, in UTC: the caller builds `now` the same way, and the model
        # refuses to compare a naive time with an aware one.
        deadline=event.deadline.ToDatetime(tzinfo=UTC) if event.HasField("deadline") else None,
        effort_estimate=(
            event.effort_estimate_minutes if event.HasField("effort_estimate_minutes") else None
        ),
        # An unset proto3 enum reads as TASK_TIER_UNSPECIFIED, which is not in _TIERS.
        tier=_TIERS.get(event.tier),
    )
