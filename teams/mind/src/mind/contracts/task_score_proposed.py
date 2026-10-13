"""Contract for task.score.proposed events sent from MIND to Chain (#357).

Agreed with Chain (Lea): event_id, task_id, mind_score (float), scored_at.
No tier, effort_estimate_minutes or deadline: Chain takes those from Inbox.
No tenant_id or model_version. `factors` is Yonit's addition; Chain does not
use it today. Still open: what confidence should carry (to confirm with Yonit).
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

    # The weighted contributions behind mind_score, keyed by factor name.
    # Not used by Chain today; kept for later consumers.
    factors: dict[str, float] = Field(default_factory=dict)

    # Pending Yonit's answer: today this is Inbox's extraction confidence,
    # passed through. Chain reads None as 1.0.
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
