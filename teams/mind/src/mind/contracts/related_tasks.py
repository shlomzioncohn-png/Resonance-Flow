"""Response contract for GET /v1/tasks/{task_id}/related."""

from __future__ import annotations

from pydantic import BaseModel, Field


class RelatedTask(BaseModel):
    task_id: str = Field(..., description="A related task's id.")
    similarity: float = Field(..., ge=-1.0, le=1.0, description="Cosine similarity, -1.0 to 1.0.")
    confidence: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="Confidence this task is really related (#476): distance above the "
        "decision threshold, not the raw similarity above.",
    )


class RelatedTasksResponse(BaseModel):
    """The body of GET /v1/tasks/{task_id}/related."""

    related: list[RelatedTask] = Field(
        ..., description="Ranked, best match first. Empty for 'nothing related'."
    )
