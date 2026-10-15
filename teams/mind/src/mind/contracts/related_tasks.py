"""Response contract for GET /v1/tasks/{task_id}/related."""

from __future__ import annotations

from pydantic import BaseModel, Field


class RelatedTask(BaseModel):
    task_id: str
    similarity: float = Field(ge=-1.0, le=1.0)


class RelatedTasksResponse(BaseModel):
    """The body of GET /v1/tasks/{task_id}/related."""

    related: list[RelatedTask]
