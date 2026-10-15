"""#357: score Inbox's tasks and publish task.score.proposed to Chain.

Handlers for task.created and task.updated. Each one converts the event,
scores it with the segmented model and hands the payload to a `ScoreSink`.

A score is re-published only when a field the model reads has changed since the
last publish for that task. That is what stops a feedback loop: every score we
send makes Chain publish task.scheduling.updated, which makes Inbox emit
task.updated, which would score the task again. Chain's update only changes
fields the model does not read, so it stops here.

Events older than the newest one already seen for a task are dropped. The two
topics are consumed on separate threads with no ordering between them, so a
task.created can be handled after a task.updated that came later; scoring it
would replace the right score with a stale one. "Older" is judged by Inbox's
own clock, created_at / updated_at, the same last-write-wins rule Chain applies.
Inbox's clock has millisecond precision, so at an equal time a task.created
counts as older than a task.updated.

Per task, in memory: the newest accepted state, the newest event seen, and a
hash of what was last published. After a restart, each task's next event
re-publishes once. That is safe when the event is the task's newest state. It is
not when a task.created was still uncommitted at the crash while a newer
task.updated had already been sent: the older score goes out again until the
next update.

A score can also change without any Inbox event: R3's hierarchical propagation
(#474) changes a parent, and so its children's scores. Her code calls
`rescore(child_id)` for each child, and the child is scored again with this
publisher's model on its newest state. Since the state lives in memory, a child
no Inbox event has reached since start-up cannot be re-scored: it is skipped
until its next event.

Known limitation: a score is not re-sent just because time passes, although
time pressure rises as a deadline nears. Chain keeps the last score sent.

The handler lets unexpected exceptions propagate (ProtobufConsumer only commits
after it returns), so a failed publish is retried and is not recorded as sent.
The one exception is an event that can never be scored (InvalidInboxEvent): it
is logged and skipped, since it would fail on every redelivery.
"""

from __future__ import annotations

import hashlib
import logging
import threading
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Protocol
from uuid import UUID

from google.protobuf.timestamp_pb2 import Timestamp

from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import ScoreContext, ScoreResult
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.inbox_task import InboxTask, InvalidInboxEvent, to_scoring_task
from mind.scoring.inputs import ScorableTask
from mind.scoring.score_event import build_score_event
from mind.scoring.segmented_model import SegmentedRankingModel

logger = logging.getLogger(__name__)

# Breaks a tie between equal Inbox times: a task.created is never newer than a
# task.updated of the same moment.
_CREATED = 0
_UPDATED = 1


@dataclass(slots=True)
class _TaskState:
    task: InboxTask  # the newest accepted state, for rescore
    seen: tuple[datetime, int] | None  # (Inbox time, _CREATED / _UPDATED)
    published: bytes | None  # hash of the inputs last published


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


def _event_time(event: TaskCreatedEvent | TaskUpdatedEvent, field: str) -> datetime | None:
    """When Inbox wrote this state of the task, or None when the event lacks it."""
    if not event.HasField(field):
        return None
    timestamp: Timestamp = getattr(event, field)
    return timestamp.ToDatetime(tzinfo=UTC)


class ScorePublisher:
    def __init__(
        self,
        sink: ScoreSink,
        model: TaskScorer | None = None,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._sink = sink
        self._model = model if model is not None else SegmentedRankingModel()
        self._clock = clock
        self._state: dict[str, _TaskState] = {}
        # One consumer thread per topic, and the caller of rescore, share it.
        self._lock = threading.Lock()

    def handle_task_created(self, event: TaskCreatedEvent) -> None:
        self._handle(event, _event_time(event, "created_at"), _CREATED)

    def handle_task_updated(self, event: TaskUpdatedEvent) -> None:
        self._handle(event, _event_time(event, "updated_at"), _UPDATED)

    def _handle(
        self,
        event: TaskCreatedEvent | TaskUpdatedEvent,
        event_time: datetime | None,
        kind: int,
    ) -> None:
        try:
            task = to_scoring_task(event)
        except InvalidInboxEvent:
            # Only this is caught: the message fails the same way on every
            # redelivery, so propagating it would stall the partition. Anything
            # else, a bug or a broker error, still propagates and is retried.
            logger.exception("score_publisher: skipping an event that cannot be scored")
            return
        self._publish_if_changed(task, None if event_time is None else (event_time, kind))

    def _publish_if_changed(self, task: InboxTask, seen: tuple[datetime, int] | None) -> None:
        inputs = _scoring_inputs_hash(task)
        with self._lock:
            state = self._state.get(task.task_id)
            if seen is None:
                # Cannot tell whether it is stale. Scoring it at worst sends one
                # extra score; dropping it could lose a real update.
                logger.warning("score_publisher: task %s event has no timestamp", task.task_id)
            elif state is not None and state.seen is not None and seen < state.seen:
                logger.info(
                    "score_publisher: task %s event from %s is older than %s, skipping",
                    task.task_id,
                    seen[0],
                    state.seen[0],
                )
                return

            if state is None:
                state = _TaskState(task=task, seen=seen, published=None)
                self._state[task.task_id] = state
            else:
                state.task = task
                # Moved even when nothing is published below: Chain's own update
                # changes no scored field, yet an older event after it is stale.
                if seen is not None:
                    state.seen = seen

            if state.published == inputs:
                logger.debug("score_publisher: task %s unchanged, skipping", task.task_id)
                return
            self._score_and_send(state, inputs)

    def rescore(self, task_id: str) -> bool:
        """Score a task again with no Inbox event, and publish the result.

        For #474's propagation: a parent changed, so this child's score did. The
        child is scored with this publisher's model on its newest state, under
        the same lock as the event handlers, so the score is never computed from
        a state older than one already published.

        Returns False, and sends nothing, for a task no Inbox event has reached
        since this process started (the state is in memory). Raises ValueError
        for a task_id that is not a UUID.
        """
        task_id = str(UUID(task_id))
        with self._lock:
            state = self._state.get(task_id)
            if state is None:
                logger.warning("score_publisher: task %s is unknown, not re-scored", task_id)
                return False
            self._score_and_send(state, _scoring_inputs_hash(state.task))
            return True

    def _score_and_send(self, state: _TaskState, inputs: bytes) -> None:
        """Score `state.task`, publish it, and record it. Caller holds the lock."""
        # Aware, in UTC, like the deadlines the conversion builds.
        now = self._clock()
        result = self._model.score(state.task, ScoreContext(current_datetime=now))
        self._sink.send(build_score_event(state.task, result, scored_at=now))
        # Recorded only after a successful send, so a failed one is retried.
        state.published = inputs
