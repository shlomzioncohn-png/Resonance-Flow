"""Contract for task.score.proposed events sent from MIND to Chain (#357).

Agreed with Chain (Lea): event_id, task_id, mind_score (float), scored_at,
confidence. No tenant_id or model_version. `factors` is Yonit's addition;
Chain does not use it today.

tier, effort_estimate_minutes and deadline are sent for now: the schema
registered in shared/proto (#551) still carries them. Chain stores the effort
from Inbox's task.created and does not use tier or deadline today. They are
Inbox's values passed through unchanged.

Kafka key = task_id. A re-publish for the same task replaces the previous
score; the latest scored_at wins.
"""

from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
)

from mind.contracts.task_enums import TaskTier


class TaskScoreProposed(BaseModel):
    """Score payload produced by MIND for Chain."""

    model_config = ConfigDict(extra="forbid")

    # Chain dedupes on it.
    event_id: UUID
    task_id: UUID

    # Importance only: the model's base_score, before any blocked adjustment.
    # Blocking is Chain's job, so sending the adjusted score would count it twice.
    mind_score: float = Field(ge=0.0, le=1.0)

    # When MIND computed this score. Chain keeps the latest one per task.
    scored_at: AwareDatetime

    # How far to trust mind_score given which of its inputs were missing:
    # 1.0 when nothing was, lower for each assumption (scoring/confidence.py).
    confidence: float = Field(ge=0.0, le=1.0)

    # The weighted contributions behind mind_score, keyed by factor name.
    # Not used by Chain today; kept for later consumers.
    factors: dict[str, float] = Field(default_factory=dict)

    # As Inbox sent them, None when unset. The tier the model assumes for an
    # unclassified task never goes here: Chain sees only a tier someone assigned.
    tier: TaskTier | None = None
    effort_estimate_minutes: int | None = Field(default=None, gt=0)
    deadline: AwareDatetime | None = None
