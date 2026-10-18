"""Build the task.dependencies.proposed payload for Chain from resolved targets (#355).

Pure: no Kafka, no clock reads. Whether there is anything to send is decided by
the caller (`publisher.py`); this only builds the payload once that's already true.
"""

from __future__ import annotations

from uuid import UUID, uuid4

from mind.contracts.task_dependencies_proposed import DependencyEdge, DependencyProposed


def build_dependency_event(
    task_id: UUID,
    tenant_id: UUID,
    resolved: dict[str, float],
    *,
    event_id: UUID | None = None,
) -> DependencyProposed:
    """`resolved` maps a depends_on_task_id to its confidence.

    Raises ValueError if empty: #133's contract has no event for "no new
    edges" -- `has_dependencies: false` means no event, not an empty one.
    """
    if not resolved:
        raise ValueError("task.dependencies.proposed needs at least one resolved edge")
    return DependencyProposed(
        event_id=event_id or uuid4(),
        task_id=task_id,
        tenant_id=tenant_id,
        dependencies=[
            DependencyEdge(depends_on_task_id=UUID(target), confidence=confidence)
            for target, confidence in resolved.items()
        ],
    )
