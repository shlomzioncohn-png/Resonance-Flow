"""Assembling an extraction into the outbound contract.

This is the single place where what the model guessed meets what we already
knew. `tenant_id` and `source_type` come from the intake event, `confidence`
comes from our own scorer, and only the four remaining fields come from the
LLM — keeping that boundary in one function is what stops an identity or a
source type from ever being model-generated.

The classification fields (tier, urgency, role_context) and the effort fields
are deliberately left unset: they belong to #351 and #352, which run on top of
this result and fill them in before anything is published.
"""

from uuid import UUID

from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.extraction.extractor import ExtractionSuccess


def to_task_creation_requested(
    result: ExtractionSuccess,
    tenant_id: UUID,
) -> TaskCreationRequested:
    """Build the Inbox-facing proposal from a successful extraction.

    The result is a proposal, not a decision: Inbox is the only team that
    creates a task record and issues a real task id.
    """

    return TaskCreationRequested(
        tenant_id=tenant_id,
        title=result.fields.title,
        description=result.fields.description,
        source_type=result.source_type,
        source_ref=result.fields.source_ref,
        has_dependencies=result.fields.has_dependencies,
        confidence=result.confidence.value,
    )
