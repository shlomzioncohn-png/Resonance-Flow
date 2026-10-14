"""Turn an Inbox task event into a `ScorableTask` the segmented model scores (#357).

Empty stays empty: a missing tier, effort estimate or description goes to the
model as None, and the model decides how to score it (see `inputs.py` and
`segmented_model.rule_tier`). Nothing here fills a default.

An event that can never be scored, because its task or tenant id is not a UUID,
raises `InvalidInboxEvent`. It is the one error a handler may swallow: the same
message would fail on every redelivery, so retrying it would stall the partition.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from mind.evaluation.interfaces import Tier
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent

# Inbox writes the tier in upper case and the model keys its rules in lower case.
_TIERS: dict[int, Tier] = {
    TaskTier.TASK_TIER_OPERATIONAL: "operational",
    TaskTier.TASK_TIER_TACTICAL: "tactical",
    TaskTier.TASK_TIER_STRATEGIC: "strategic",
}


class InvalidInboxEvent(ValueError):
    """An Inbox event this conversion can never turn into a task: skip, don't retry."""


def _require_uuid(value: str, field: str, task_id: str) -> str:
    """`value` in canonical form, so one task never shows up under two spellings."""
    try:
        return str(UUID(value))
    except ValueError:
        raise InvalidInboxEvent(f"task {task_id!r}: {field} {value!r} is not a UUID") from None


@dataclass(frozen=True)
class InboxTask:
    """A real task as the model reads it. Fits `ScorableTask`.

    `tenant_id` is not read by the model; it is carried for the payload to Chain.
    """

    task_id: str
    tenant_id: str
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
    """Build the model's input from an Inbox task.created / task.updated event.

    Raises InvalidInboxEvent when the task or tenant id is not a UUID.
    """
    # Inbox names the id differently on the two topics.
    raw_task_id = event.id if isinstance(event, TaskCreatedEvent) else event.task_id
    task_id = _require_uuid(raw_task_id, "task_id", raw_task_id)
    tenant_id = _require_uuid(event.tenant_id, "tenant_id", task_id)

    # A non-positive estimate means nothing usable, so it is read as missing.
    # Rejecting the task over its weakest feature would cost its whole score,
    # and a negative one would push quick_win, and so the score, above 1.
    effort = event.effort_estimate_minutes if event.HasField("effort_estimate_minutes") else None
    if effort is not None and effort <= 0:
        effort = None

    return InboxTask(
        task_id=task_id,
        tenant_id=tenant_id,
        title=event.title,
        description=event.description or None,
        # Aware, in UTC: the caller builds `now` the same way, and the model
        # refuses to compare a naive time with an aware one.
        deadline=event.deadline.ToDatetime(tzinfo=UTC) if event.HasField("deadline") else None,
        effort_estimate=effort,
        # An unset proto3 enum reads as TASK_TIER_UNSPECIFIED, which is not in _TIERS.
        tier=_TIERS.get(event.tier),
    )
