"""Assembling an extraction into the outbound contract.

This is the single place where what the model guessed meets what we already
knew. `tenant_id` and `source_type` come from the intake event, `confidence`
comes from our own scorer, and the rest comes from the LLM — keeping that
boundary in one function is what stops an identity or a source type from ever
being model-generated.

The classification fields (tier, urgency, role_context) and the effort
estimate (#352) are filled in here when #351's switch was on and left as None
when it was off. The estimate's confidence is derived from its basis, in
`confidence.py`, and never touches the overall `confidence`.

`source_ref` comes from Bridge, never from the model (#353): Inbox requires it,
and its UI builds the link to the source from source_type + source_ref.
"""

from uuid import UUID

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.extraction.confidence import effort_confidence
from mind.extraction.extractor import ExtractedTask, ExtractionSuccess
from mind.extraction.models import ClassificationFields

#: Marks a source_ref that is Bridge's id for the item rather than the source
#: system's: there is no external link to build from it.
BRIDGE_REF_PREFIX = "bridge:"


def source_ref_for_inbox(bridge_source_ref: str | None, bridge_correlation_id: UUID) -> str:
    """The source system's id for the item when Bridge has it, else Bridge's own.

    Never blank: Inbox's creation request rejects a blank one (`@NotBlank`)
    and the task would never be created. What extraction read from the
    content is deliberately not a candidate — a pointer the text mentions is
    not the item's own id, and a link built from it leads somewhere else.
    """

    if bridge_source_ref is not None and bridge_source_ref.strip():
        return bridge_source_ref.strip()
    return f"{BRIDGE_REF_PREFIX}{bridge_correlation_id}"


def to_task_creation_requested(
    result: ExtractionSuccess,
    tenant_id: UUID,
    *,
    bridge_source_ref: str | None,
    bridge_correlation_id: UUID,
) -> list[TaskCreationRequested]:
    """Build one Inbox-facing proposal per extracted task (#690), in order.

    Each is a proposal, not a decision: Inbox is the only team that creates a
    task record and issues a real task id. A text with no task gives an empty
    list, so nothing is sent for it.

    Every task from one item carries that item's `source_ref`: it names where
    the tasks came from, not which task this is. Inbox tells them apart by
    correlation_id, one per task.
    """

    source_ref = source_ref_for_inbox(bridge_source_ref, bridge_correlation_id)
    return [_proposal(task, result.source_type, tenant_id, source_ref) for task in result.tasks]


def _proposal(
    task: ExtractedTask,
    source_type: SourceType,
    tenant_id: UUID,
    source_ref: str,
) -> TaskCreationRequested:
    """One task's proposal: its own fields and confidence, the shared rest."""

    # With classification switched off the fields object genuinely has no
    # `tier` attribute — reading one raises rather than returning None — so
    # the shape has to be checked before it is read. An isinstance narrowing
    # rather than `getattr(..., "tier", None)`: here the field names are real
    # attributes that mypy verifies, where a misspelled string would quietly
    # return None for ever.
    classification = task.fields if isinstance(task.fields, ClassificationFields) else None

    return TaskCreationRequested(
        tenant_id=tenant_id,
        title=task.fields.title,
        description=task.fields.description,
        source_type=source_type,
        source_ref=source_ref,
        has_dependencies=task.fields.has_dependencies,
        confidence=task.confidence.value,
        # None here means two different things that happen to deserve the same
        # answer: nobody asked for a classification, or the model was asked and
        # honestly declined to guess. Both leave Inbox with an empty field,
        # which is exactly the contract's default.
        tier=classification.tier if classification else None,
        urgency=classification.urgency if classification else None,
        role_context=classification.role_context if classification else None,
        effort_estimate_minutes=(
            classification.effort_estimate_minutes if classification else None
        ),
        effort_estimate_confidence=(
            effort_confidence(classification.effort_estimate_basis) if classification else None
        ),
    )
