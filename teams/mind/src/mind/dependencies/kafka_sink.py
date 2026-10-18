"""#355: send a `DependencyProposed` to Kafka as the registered proto message.

The proto is Relay's copy in shared/proto/task-dependencies-proposed-event.proto.
"""

from __future__ import annotations

from typing import Protocol

from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.messaging.producer import Publisher
from mind.messaging.task_dependencies_proposed_event_pb2 import (
    Dependency as ProtoDependency,
)
from mind.messaging.task_dependencies_proposed_event_pb2 import (
    DependencyProposedEvent,
)

TOPIC = "task.dependencies.proposed"


def to_proto(payload: DependencyProposed) -> DependencyProposedEvent:
    return DependencyProposedEvent(
        event_id=str(payload.event_id),
        task_id=str(payload.task_id),
        tenant_id=str(payload.tenant_id),
        dependencies=[
            ProtoDependency(
                depends_on_task_id=str(edge.depends_on_task_id),
                confidence=edge.confidence,
            )
            for edge in payload.dependencies
        ],
    )


class DependencySink(Protocol):
    """Where a built payload goes: Kafka in production, a list in tests."""

    def send(self, payload: DependencyProposed) -> None: ...


class KafkaDependencySink:
    """A `DependencySink` that publishes to task.dependencies.proposed, keyed by
    task_id so every edge for one task lands on one partition, in order."""

    def __init__(self, producer: Publisher) -> None:
        self._producer = producer

    def send(self, payload: DependencyProposed) -> None:
        self._producer.publish(TOPIC, to_proto(payload), key=str(payload.task_id))
