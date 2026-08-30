"""Models representing task assignees and MIND assignee candidates."""

from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from .task_enums import AssigneeType


class Assignee(BaseModel):
    """A user or team assigned to a Task."""

    model_config = ConfigDict(extra="forbid")

    type: AssigneeType
    id: UUID


class AssigneeCandidate(BaseModel):
    """A user or team proposed by MIND as a possible assignee."""

    model_config = ConfigDict(extra="forbid")

    type: AssigneeType
    id: UUID

    confidence: float = Field(
        ge=0.0,
        le=1.0,
    )
    