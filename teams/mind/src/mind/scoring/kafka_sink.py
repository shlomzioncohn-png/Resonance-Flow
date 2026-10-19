"""#357: send a `TaskScoreProposed` to Kafka as the registered proto message.

The proto is Relay's copy in shared/proto/task-score-proposed-event.proto.
Its fields 6 and 7 (effort_estimate_minutes, deadline) are deprecated and kept
only for FULL compatibility: Chain does not need them, so they stay unset.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from mind.contracts.task_enums import TaskTier
from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.messaging.task_created_event_pb2 import TaskTier as ProtoTaskTier
from mind.messaging.task_score_proposed_event_pb2 import TaskScoreProposedEvent

if TYPE_CHECKING:
    from mind.messaging.producer import Publisher

TOPIC = "task.score.proposed"

_TIERS = {
    TaskTier.OPERATIONAL: ProtoTaskTier.TASK_TIER_OPERATIONAL,
    TaskTier.TACTICAL: ProtoTaskTier.TASK_TIER_TACTICAL,
    TaskTier.STRATEGIC: ProtoTaskTier.TASK_TIER_STRATEGIC,
}


def to_proto(payload: TaskScoreProposed) -> TaskScoreProposedEvent:
    message = TaskScoreProposedEvent(
        event_id=str(payload.event_id),
        tenant_id=str(payload.tenant_id),
        task_id=str(payload.task_id),
        mind_score=payload.mind_score,
        confidence=payload.confidence,
    )
    message.scored_at.FromDatetime(payload.scored_at)
    message.factors.update(payload.factors)
    # Unset tier stays TASK_TIER_UNSPECIFIED: Chain sees only a tier someone assigned.
    if payload.tier is not None:
        message.tier = _TIERS[payload.tier]
    return message


class KafkaScoreSink:
    """A `ScoreSink` that publishes to task.score.proposed, keyed by task_id
    so every score for one task lands on one partition, in order."""

    def __init__(self, producer: Publisher) -> None:
        self._producer = producer

    def send(self, payload: TaskScoreProposed) -> None:
        self._producer.publish(TOPIC, to_proto(payload), key=str(payload.task_id))
