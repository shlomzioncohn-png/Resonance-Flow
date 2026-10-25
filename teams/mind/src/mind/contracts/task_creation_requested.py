"""Contract for Task creation requests sent from MIND to Inbox."""

from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
)

from mind.contracts.assignee import AssigneeCandidate
from mind.contracts.base_enums import SourceType
from mind.contracts.task_enums import (
    RoleContext,
    TaskTier,
)


class TaskCreationRequested(BaseModel):
    """Task creation payload produced by MIND for Inbox."""

    model_config = ConfigDict(extra="forbid")

    # Tenant known by MIND/Bridge.
    # Inbox maps this internally to organization_id.
    tenant_id: UUID

    # Extracted task content.
    title: str = Field(min_length=1)
    description: str | None = None

    # Original source from which MIND extracted the task.
    source_type: SourceType
    source_ref: str | None = None

    # MIND classification fields.
    tier: TaskTier | None = None
    role_context: RoleContext | None = None

    urgency: int | None = Field(
        default=None,
        ge=1,
        le=5,
    )

    # Estimated execution time in minutes.
    effort_estimate_minutes: int | None = Field(
        default=None,
        gt=0,
    )

    # MIND confidence specifically in the effort estimate.
    effort_estimate_confidence: float | None = Field(
        default=None,
        ge=0.0,
        le=1.0,
    )

    deadline: AwareDatetime | None = None

    # Tells Inbox whether the task initially has dependencies.
    # The dependency IDs themselves are sent separately to Chain.
    # True when the task depends on, or is blocked by, other work: it cannot
    # be finished until that work is done. Not set for a task that others
    # wait for (narrowed in #690 from "depends on, is blocked by, or blocks",
    # so that it means what Chain's depends_on edges mean).
    has_dependencies: bool

    # General confidence in the extracted Task.
    confidence: float = Field(
        ge=0.0,
        le=1.0,
    )

    # MIND proposes an owner.
    # The owner is always a User, never a Team.
    suggested_owner_user_id: UUID | None = None

    # Confidence specifically in the proposed owner.
    suggested_owner_confidence: float | None = Field(
        default=None,
        ge=0.0,
        le=1.0,
    )

    # MIND may propose multiple Users and/or Teams.
    candidate_assignees: list[AssigneeCandidate] = Field(
        default_factory=list
    )
