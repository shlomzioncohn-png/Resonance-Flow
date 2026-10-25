"""Build the task.dependencies.proposed payload for Chain from resolved targets (#355).

Pure: no Kafka, no clock reads.
"""

from __future__ import annotations

from uuid import UUID, uuid4

from mind.contracts.task_dependencies_proposed import DependencyEdge, DependencyProposed


def build_dependency_event(
    task_id: UUID,
    tenant_id: UUID,
    resolved: dict[UUID, float],
    *,
    event_id: UUID | None = None,
) -> DependencyProposed:
    """`resolved` maps a depends_on_task_id to its confidence. Must be
    non-empty -- `DependencyProposed` itself enforces that."""
    return DependencyProposed(
        event_id=event_id or uuid4(),
        task_id=task_id,
        tenant_id=tenant_id,
        dependencies=[
            DependencyEdge(depends_on_task_id=target, confidence=confidence)
            for target, confidence in resolved.items()
        ],
    )
