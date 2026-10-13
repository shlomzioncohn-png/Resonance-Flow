"""Build the task.score.proposed payload for Chain from a model's result (#357).

Pure: no Kafka, no clock reads. The caller passes `scored_at`, so the same
inputs always build the same payload apart from the generated event_id.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import ScoreResult
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent


def build_score_event(
    event: TaskCreatedEvent | TaskUpdatedEvent,
    result: ScoreResult,
    *,
    scored_at: datetime,
    event_id: UUID | None = None,
) -> TaskScoreProposed:
    """The payload for the task in `event`, carrying `result`'s base score.

    Raises ValueError when `result` has no base_score: falling back to
    `result.score` would send a blocked-adjusted value and count blocking twice.
    """
    if result.factors is None or result.factors.base_score is None:
        raise ValueError("the model returned no base_score; task.score.proposed needs it")

    # Inbox names the id differently on the two topics.
    task_id = event.id if isinstance(event, TaskCreatedEvent) else event.task_id

    return TaskScoreProposed(
        event_id=event_id or uuid4(),
        task_id=UUID(task_id),
        mind_score=result.factors.base_score,
        scored_at=scored_at,
        factors=dict(result.factors.contributions),
        confidence=event.confidence if event.HasField("confidence") else None,
    )
