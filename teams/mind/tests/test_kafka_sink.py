"""#357: the payload goes to Kafka as shared/proto's TaskScoreProposedEvent,
keyed by task_id. No broker: the producer is a fake that records calls."""

from datetime import UTC, datetime
from uuid import UUID

import pytest
from google.protobuf.message import Message
from google.protobuf.timestamp_pb2 import Timestamp

from mind.contracts.task_enums import TaskTier
from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_created_event_pb2 import TaskTier as ProtoTaskTier
from mind.messaging.task_score_proposed_event_pb2 import TaskScoreProposedEvent
from mind.scoring.kafka_sink import TOPIC, KafkaScoreSink, to_proto
from mind.scoring.score_publisher import ScorePublisher

PAYLOAD = TaskScoreProposed(
    event_id=UUID("11111111-2222-4333-8444-555555555555"),
    tenant_id=UUID("7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a"),
    task_id=UUID("3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"),
    mind_score=0.5,
    scored_at=datetime(2026, 10, 15, 9, 0, tzinfo=UTC),
    confidence=0.75,
    factors={"time_pressure": 0.25, "impact": 0.25},
    tier=TaskTier.OPERATIONAL,
)


class FakeProducer:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Message, str | None]] = []

    def publish(self, topic: str, message: Message, key: str | None = None) -> None:
        self.calls.append((topic, message, key))


def test_every_field_is_carried_over():
    message = to_proto(PAYLOAD)

    assert message.event_id == str(PAYLOAD.event_id)
    assert message.tenant_id == str(PAYLOAD.tenant_id)
    assert message.task_id == str(PAYLOAD.task_id)
    assert message.mind_score == 0.5
    assert message.confidence == 0.75
    assert message.scored_at.ToDatetime(tzinfo=UTC) == PAYLOAD.scored_at
    assert dict(message.factors) == PAYLOAD.factors
    assert message.tier == ProtoTaskTier.TASK_TIER_OPERATIONAL


def test_an_unset_tier_stays_unspecified():
    message = to_proto(PAYLOAD.model_copy(update={"tier": None}))

    assert message.tier == ProtoTaskTier.TASK_TIER_UNSPECIFIED


def test_the_deprecated_fields_are_never_filled():
    # Chain does not need them; the proto keeps them only for FULL compatibility.
    message = to_proto(PAYLOAD)

    assert message.effort_estimate_minutes == 0
    assert not message.HasField("deadline")


def test_the_message_survives_serialization():
    sent = to_proto(PAYLOAD)

    received = TaskScoreProposedEvent.FromString(sent.SerializeToString())

    assert received == sent


def test_the_sink_publishes_to_the_topic_keyed_by_task_id():
    producer = FakeProducer()

    KafkaScoreSink(producer).send(PAYLOAD)

    [(topic, message, key)] = producer.calls
    assert topic == TOPIC == "task.score.proposed"
    assert key == str(PAYLOAD.task_id)
    assert message == to_proto(PAYLOAD)


@pytest.mark.parametrize(
    "tier",
    [
        ProtoTaskTier.TASK_TIER_OPERATIONAL,
        ProtoTaskTier.TASK_TIER_TACTICAL,
        ProtoTaskTier.TASK_TIER_STRATEGIC,
    ],
)
def test_inbox_tier_reaches_chain_unchanged(tier):
    # End to end through all three hand-written tier maps: Inbox event, model
    # input, payload, proto. A swapped entry in any of them fails here.
    created_at = Timestamp()
    created_at.FromDatetime(datetime(2026, 10, 15, 8, 0, tzinfo=UTC))
    event = TaskCreatedEvent(
        id=str(PAYLOAD.task_id),
        tenant_id=str(PAYLOAD.tenant_id),
        title="Fix the checkout crash",
        tier=tier,
        created_at=created_at,
    )
    producer = FakeProducer()
    publisher = ScorePublisher(
        KafkaScoreSink(producer), clock=lambda: datetime(2026, 10, 15, 9, 0, tzinfo=UTC)
    )

    publisher.handle_task_created(event)

    [(_, message, _)] = producer.calls
    assert message.tier == tier
