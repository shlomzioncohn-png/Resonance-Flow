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
from mind.evaluation.interfaces import ScoreContext, ScoreResult
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.inbox_task import to_scoring_task
from mind.scoring.inputs import ScorableTask
from mind.scoring.score_event import build_score_event
from mind.scoring.segmented_model import SegmentedRankingModel

logger = logging.getLogger(__name__)

TOPIC = "task.score.proposed"


class ScoreSink(Protocol):
    """Where a built payload goes: Kafka in production, a list in tests."""

    def send(self, payload: TaskScoreProposed) -> None: ...


class TaskScorer(Protocol):
    """A model that scores a real task. `RankingModel` takes the scenario
    `Task` and so cannot be handed one built from Inbox; the segmented model,
    and any model layered on it, takes the wider `ScorableTask`."""

    def score(self, task: ScorableTask, context: ScoreContext) -> ScoreResult: ...


def _scoring_inputs_hash(task: ScorableTask) -> bytes:
    """A SHA-256 of every field the model reads: 32 bytes per task, whatever
    the length of its description.

    `urgency` is deliberately left out although #357 lists it: the model infers
    urgency from the text and never reads the field, so a change in it cannot
    change the score. `blocked` and `parent_task_id` are left out because the
    conversion always sets them the same way.
    """
    fields = (
        task.title,
        task.description,
        task.tier,
        task.deadline.isoformat() if task.deadline is not None else None,
        task.effort_estimate,
    )
    # repr quotes each string, so no two different field sets serialize alike.
    return hashlib.sha256(repr(fields).encode()).digest()


class ScorePublisher:
    def __init__(
        self,
        sink: ScoreSink,
        model: TaskScorer | None = None,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._sink = sink
        self._model = model or SegmentedRankingModel()
        self._clock = clock
        self._last_published: dict[str, bytes] = {}
        # One consumer thread per topic shares the state above.
        self._lock = threading.Lock()

    def handle_task_created(self, event: TaskCreatedEvent) -> None:
        self._publish_if_changed(to_scoring_task(event))

    def handle_task_updated(self, event: TaskUpdatedEvent) -> None:
        self._publish_if_changed(to_scoring_task(event))

    def _publish_if_changed(self, task: ScorableTask) -> None:
        inputs = _scoring_inputs_hash(task)
        with self._lock:
            if self._last_published.get(task.task_id) == inputs:
                logger.debug("score_publisher: task %s unchanged, skipping", task.task_id)
                return

            # Aware, in UTC, like the deadlines the conversion builds.
            now = self._clock()
            result = self._model.score(task, ScoreContext(current_datetime=now))
            self._sink.send(build_score_event(task, result, scored_at=now))
            # Recorded only after a successful send, so a failed one is retried.
            self._last_published[task.task_id] = inputs
