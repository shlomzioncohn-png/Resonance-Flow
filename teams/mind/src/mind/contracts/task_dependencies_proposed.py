"""Contract for task.dependencies.proposed events sent from MIND to Chain (#355).

Agreed with Chain (#133): event_id, task_id, tenant_id, dependencies. Re-publishing
for the same task adds edges, never replaces them -- Mind never removes an edge
(shared/proto/task-dependencies-proposed-event.proto).

Sent as shared/proto/task-dependencies-proposed-event.proto (dependencies/kafka_sink.py).
Kafka key = task_id. `has_dependencies: false` means no event at all, not an event
with an empty `dependencies` list -- enforced below, not left to callers.
"""

from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class DependencyEdge(BaseModel):
    """One resolved dependency target and the confidence in that match."""

    model_config = ConfigDict(extra="forbid")

    depends_on_task_id: UUID
    confidence: float = Field(ge=0.0, le=1.0)


class DependencyProposed(BaseModel):
    """One batch of newly-resolved dependency edges for `task_id`."""

    model_config = ConfigDict(extra="forbid")

    # Chain dedupes on it.
    event_id: UUID
    task_id: UUID
    # The same value Chain gets on Inbox's task.created.
    tenant_id: UUID

    dependencies: list[DependencyEdge] = Field(min_length=1)
