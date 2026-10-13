"""#357: score Inbox's tasks and publish task.score.proposed to Chain.

Handlers for task.created and task.updated. Each one converts the event,
scores it with the segmented model and hands the payload to a `ScoreSink`.

A score is re-published only when a field the model reads has changed since the
last publish for that task. That is what stops a feedback loop: every score we
send makes Chain publish task.scheduling.updated, which makes Inbox emit
task.updated, which would score the task again. Chain's update only changes
fields the model does not read, so it stops here.

A hash of what was last published per task lives in memory. After a restart, each task's
next event re-publishes once, which is safe: a newer score replaces the old one.

Known limitation: a score is not re-sent just because time passes, although
time pressure rises as a deadline nears. Chain keeps the last score sent.

The handler lets unexpected exceptions propagate (ProtobufConsumer only commits
after it returns), so a failed publish is retried and is not recorded as sent.
"""

from __future__ import annotations

import hashlib
import logging
import threading
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Protocol

from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import RankingModel, ScoreContext
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.inbox_task import to_scoring_task
from mind.scoring.score_event import build_score_event
from mind.scoring.segmented_model import SegmentedRankingModel

logger = logging.getLogger(__name__)

TOPIC = "task.score.proposed"

class ScoreSink(Protocol):
    """Where a built payload goes: Kafka in production, a list in tests."""

    def send(self, payload: TaskScoreProposed) -> None: ...


def _scoring_inputs_hash(event: TaskCreatedEvent | TaskUpdatedEvent) -> bytes:
    """A SHA-256 of every field the model reads: 32 bytes per task, whatever
    the length of its description.

    `urgency` is deliberately left out although #357 lists it: the model infers
    urgency from the text and never reads the field, so a change in it cannot
    change the score.
    """
    fields = (
        event.title,
        event.description,
        event.tier,
        event.deadline.ToDatetime(tzinfo=UTC).isoformat() if event.HasField("deadline") else None,
        event.effort_estimate_minutes if event.HasField("effort_estimate_minutes") else None,
    )
    # repr quotes each string, so no two different field sets serialize alike.
    return hashlib.sha256(repr(fields).encode()).digest()


class ScorePublisher:
    def __init__(
        self,
        sink: ScoreSink,
        model: RankingModel | None = None,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._sink = sink
        self._model = model or SegmentedRankingModel()
        self._clock = clock
        self._last_published: dict[str, bytes] = {}
        # One consumer thread per topic shares the state above.
        self._lock = threading.Lock()

    def handle_task_created(self, event: TaskCreatedEvent) -> None:
        self._publish_if_changed(event.id, event)

    def handle_task_updated(self, event: TaskUpdatedEvent) -> None:
        self._publish_if_changed(event.task_id, event)

    def _publish_if_changed(self, task_id: str, event: TaskCreatedEvent | TaskUpdatedEvent) -> None:
        inputs = _scoring_inputs_hash(event)
        with self._lock:
            if self._last_published.get(task_id) == inputs:
                logger.debug("score_publisher: task %s has no scoring change, skipping", task_id)
                return

            now = self._clock()
            result = self._model.score(to_scoring_task(event), ScoreContext(current_datetime=now))
            self._sink.send(build_score_event(event, result, scored_at=now))
            # Recorded only after a successful send, so a failed one is retried.
            self._last_published[task_id] = inputs
