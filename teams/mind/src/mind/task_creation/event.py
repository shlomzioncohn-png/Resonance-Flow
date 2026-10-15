"""The TaskCreationRequested contract, translated into the message Inbox reads.

Not a second payload shape. The contract is Mind's (`TaskCreationRequested`),
the wire format is the registered one (`shared/proto`), and nothing here
defines a field of its own — it only translates.

The rule that matters most: a None must stay **unset**. Protobuf reads an unset
field as 0, so None and 0 survive the trip as different values only if a None
field is never touched. Inbox validates on that difference.
"""

from collections.abc import Callable
from uuid import UUID

from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.messaging.task_created_event_pb2 import SourceType, TaskRoleContext, TaskTier
from mind.messaging.task_creation_requested_event_pb2 import TaskCreationRequestedEvent

#: The text fields Inbox rejects when blank (@NotBlank on its creation request).
_REQUIRED_BY_INBOX = ("title", "source_ref")


def _on_the_wire(lookup: Callable[[str], object], prefix: str, value: str) -> str:
    """`OPERATIONAL` -> "TASK_TIER_OPERATIONAL", or a loud failure.

    Returns the value's name: protobuf takes an enum field by name, and the
    generated types accept it. Looked up first only so that a value Mind has and
    the registered schema lacks — a contract out of step, which Inbox would
    reject anyway — fails with a message that says so, never as UNSPECIFIED.
    """

    name = f"{prefix}_{value}"
    try:
        lookup(name)
    except ValueError:
        raise ValueError(f"{name} is not in shared/proto; the contracts are out of step") from None
    return name


def to_creation_requested_event(
    request: TaskCreationRequested, correlation_id: UUID
) -> TaskCreationRequestedEvent:
    """Build `task.creation.requested` from the proposal and its correlation id.

    Left unset on purpose: `event_id` (matching is by correlation_id),
    `project_id` and `created_by_user_id` (Mind has no source for them),
    `status` (Inbox starts every task as NEW), and `assignees` — a candidate
    owner is a suggestion, and in `assignees` it would become an assignment
    (agreed with Inbox). The three suggestion fields Inbox added in #747 join
    once they reach shared/proto.
    """

    # Inbox rejects a blank one of these (@NotBlank) into its error topic: the
    # task is never created and Mind waits for a reply that never comes. Both
    # are always filled on the way here, so a blank one is Mind's bug, and this
    # is the place to find it — not sent and silently lost.
    for field in _REQUIRED_BY_INBOX:
        value = getattr(request, field)
        if value is None or not value.strip():
            raise ValueError(
                f"{field} is blank; Inbox would reject the request and no task would "
                f"be created — build the proposal through to_task_creation_requested"
            )

    # None stays unset: protobuf leaves a field passed as None untouched, and
    # an untouched field is what tells "absent" from 0 on the wire.
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
