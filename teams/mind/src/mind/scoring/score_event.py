"""Build the task.score.proposed payload for Chain from a model's result (#357).

Pure: no Kafka, no clock reads. The caller passes `scored_at`, so the same
inputs always build the same payload apart from the generated event_id.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from mind.contracts.task_enums import TaskTier
from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import ScoreResult
from mind.scoring.confidence import score_confidence
from mind.scoring.inbox_task import InboxTask


def build_score_event(
    task: InboxTask,
    result: ScoreResult,
    *,
    scored_at: datetime,
    event_id: UUID | None = None,
) -> TaskScoreProposed:
    """The payload for `task`, carrying `result`'s base score.

    Raises ValueError when `result` has no base_score: falling back to
    `result.score` would send a blocked-adjusted value and count blocking twice.
    """
    if result.factors is None or result.factors.base_score is None:
        raise ValueError("the model returned no base_score; task.score.proposed needs it")

    return TaskScoreProposed(
        event_id=event_id or uuid4(),
        tenant_id=UUID(task.tenant_id),
        task_id=UUID(task.task_id),
        mind_score=result.factors.base_score,
        scored_at=scored_at,
        factors=dict(result.factors.contributions),
        confidence=score_confidence(task),
        # Inbox's own value: the conversion leaves an unset tier None, so the
        # tier the model assumes for an unclassified task never reaches Chain.
        tier=TaskTier(task.tier.upper()) if task.tier is not None else None,
    )
