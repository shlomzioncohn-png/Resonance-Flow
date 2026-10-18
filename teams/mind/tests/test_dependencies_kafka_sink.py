"""#355: the payload goes to Kafka as shared/proto's DependencyProposedEvent,
keyed by task_id. No broker: the producer is a fake that records calls."""

from uuid import UUID

import pytest
from google.protobuf.message import Message

from mind.contracts.task_dependencies_proposed import DependencyEdge, DependencyProposed
from mind.dependencies.kafka_sink import TOPIC, KafkaDependencySink, to_proto
from mind.messaging.task_dependencies_proposed_event_pb2 import DependencyProposedEvent

PAYLOAD = DependencyProposed(
    event_id=UUID("11111111-2222-4333-8444-555555555555"),
    task_id=UUID("3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"),
    tenant_id=UUID("7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a"),
    dependencies=[
        DependencyEdge(
            depends_on_task_id=UUID("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"), confidence=0.9
        ),
        DependencyEdge(
            depends_on_task_id=UUID("1e0f9a8b-7c6d-5e4f-4a3b-8c2d9a8b7c6d"), confidence=0.7
        ),
    ],
)


class FakeProducer:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Message, str | None]] = []

    def publish(self, topic: str, message: Message, key: str | None = None) -> None:
        self.calls.append((topic, message, key))


def test_every_field_is_carried_over():
    message = to_proto(PAYLOAD)

    assert message.event_id == str(PAYLOAD.event_id)
    assert message.task_id == str(PAYLOAD.task_id)
    assert message.tenant_id == str(PAYLOAD.tenant_id)
    assert [d.depends_on_task_id for d in message.dependencies] == [
        str(e.depends_on_task_id) for e in PAYLOAD.dependencies
    ]
    # proto `float` is 32-bit; a Python double literal is not always exactly
    # representable in it (0.9 -> 0.8999999761581421).
    assert [d.confidence for d in message.dependencies] == pytest.approx(
        [e.confidence for e in PAYLOAD.dependencies]
    )


def test_the_message_survives_serialization():
    sent = to_proto(PAYLOAD)

    received = DependencyProposedEvent.FromString(sent.SerializeToString())

    assert received == sent


def test_the_sink_publishes_to_the_topic_keyed_by_task_id():
    producer = FakeProducer()

    KafkaDependencySink(producer).send(PAYLOAD)

    [(topic, message, key)] = producer.calls
    assert topic == TOPIC == "task.dependencies.proposed"
    assert key == str(PAYLOAD.task_id)
    assert message == to_proto(PAYLOAD)
