"""`TaskCreationRequested` translated into the `TaskCreationRequestedEvent` Inbox reads.

Why each rule: README.md, "Translating to the wire".
"""

from collections.abc import Callable
from uuid import UUID

from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.messaging.task_created_event_pb2 import SourceType, TaskRoleContext, TaskTier
from mind.messaging.task_creation_requested_event_pb2 import TaskCreationRequestedEvent

#: The text fields Inbox rejects when blank (@NotBlank on its creation request).
_REQUIRED_BY_INBOX = ("title", "source_ref")


def _on_the_wire(lookup: Callable[[str], object], prefix: str, value: str) -> str:
    """`OPERATIONAL` -> "TASK_TIER_OPERATIONAL"; a ValueError if shared/proto lacks it."""

    name = f"{prefix}_{value}"
    try:
        lookup(name)
    except ValueError:
        raise ValueError(f"{name} is not in shared/proto; the contracts are out of step") from None
    return name


def to_creation_requested_event(
    request: TaskCreationRequested, correlation_id: UUID
) -> TaskCreationRequestedEvent:
    """Build `task.creation.requested`. Raises ValueError, before anything is
    sent, for a blank required field or an enum value shared/proto lacks."""

    for field in _REQUIRED_BY_INBOX:
        value = getattr(request, field)
        if value is None or not value.strip():
            raise ValueError(
                f"{field} is blank; Inbox would reject the request and no task would "
                f"be created — build the proposal through to_task_creation_requested"
            )

    # Passed as None, a field stays unset: absent, not 0.
    event = TaskCreationRequestedEvent(
        correlation_id=str(correlation_id),
        tenant_id=str(request.tenant_id),
        title=request.title,
        description=request.description,
        source_type=_on_the_wire(SourceType.Value, "SOURCE_TYPE", request.source_type.value),
        source_ref=request.source_ref,
        tier=(
            _on_the_wire(TaskTier.Value, "TASK_TIER", request.tier.value)
            if request.tier is not None
            else None
        ),
        role_context=(
            _on_the_wire(TaskRoleContext.Value, "TASK_ROLE_CONTEXT", request.role_context.value)
            if request.role_context is not None
            else None
        ),
        urgency=request.urgency,
        effort_estimate_minutes=request.effort_estimate_minutes,
        confidence=request.confidence,
        has_dependencies=request.has_dependencies,
    )
    if request.deadline is not None:
        event.deadline.FromDatetime(request.deadline)

    return event
