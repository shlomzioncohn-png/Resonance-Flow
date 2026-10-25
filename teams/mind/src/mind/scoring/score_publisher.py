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
(#474) changes a parent, and so its children's scores. After a send, the
caller runs `_cascade` outside the lock (a network call must not hold up
every other task's scoring), which asks Chain for the task's direct
children and calls `rescore(child_id)` for each -- but only when that send's
`mind_score` differed from the last one published by at least
`_CASCADE_CHANGE_TOLERANCE` -- never exact equality, since
`mind_score` includes time pressure, which drifts every call just because
real time passed. A fixed tolerance on the difference (not rounding, which
would flip a tiny move across a bucket edge into "changed") absorbs that
drift while still catching a real one, which is also what stops two tasks
that are each other's ancestor from re-triggering each other forever.
`rescore` only queues the id, so it is safe to call from anywhere. A
separate worker (`run_rescores`) takes ids off the queue and re-scores each
on its newest state; a request already queued is dropped. A child no
Inbox event has reached since start-up cannot be re-scored, and a change
stuck behind one is not guaranteed to reach anything past it -- a known
limitation, not something this fixes.

The other half of that integration is `cached_task(task_id)`: propagation
re-scores an ancestor's own last-known state with the baseline, rather than
reading another task's already-hierarchy-adjusted score (averaging does not
compose recursively the way summing once did -- see hierarchical_model.py).
The same in-memory limitation applies: an ancestor no Inbox event has
reached since start-up returns None here, which propagation then leaves out
of its average rather than counting as 0.

Known limitation: a score is not re-sent just because time passes, although
time pressure rises as a deadline nears. Chain keeps the last score sent.

Two kinds of error, handled differently:

- Sending to Kafka (`sink.send`) can fail for a while and then work. That error
  propagates: ProtobufConsumer commits only after the handler returns, so the
  message is retried, and it is not recorded as sent.
- Converting the event, scoring the task or building the payload fails the same
  way on every redelivery: a malformed event, a result without base_score, a
  score outside 0..1, a bug in the model for one task. Propagating it would kill
  the process on the same message forever, so it is logged with the task id and
  skipped, and the next event is handled as usual. Anything the model raises
  counts as this kind, so a model must not raise on a transient failure: #474
  already requires propagation to fall back to the baseline score when Chain's
  traversal API is unavailable.

Known limitation: the lock is still held while the model scores, so
`ChainAncestorSource`'s one call to Chain per task (#474) holds up the other
topic meanwhile -- a short, bounded timeout (`ChainHierarchySettings`), not
eliminated. `_cascade`'s own call moved outside the lock (round-9 review):
it no longer adds a second timeout window on top.
"""

from __future__ import annotations

import hashlib
import logging
import queue
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
from mind.scoring.chain_hierarchy_client import ChainHierarchyClient
from mind.scoring.inbox_task import InboxTask, to_scoring_task
from mind.scoring.inputs import ScorableTask
from mind.scoring.score_event import build_score_event
from mind.scoring.segmented_model import SegmentedRankingModel

logger = logging.getLogger(__name__)

# Breaks a tie between equal Inbox times: a task.created is never newer than a
# task.updated of the same moment.
_CREATED = 0
_UPDATED = 1

#: mind_score tolerance for "did this actually change" -- coarse enough to
#: absorb time-pressure's per-call drift, fine enough to catch a real change.
#: A fixed tolerance on the difference, not rounding: rounding first would
#: flip a tiny, meaningless move across a bucket edge (0.12349 -> 0.12351)
#: into "changed", and could hide a real move that stays inside one bucket.
_CASCADE_CHANGE_TOLERANCE = 0.0005


@dataclass(slots=True)
class _TaskState:
    task: InboxTask  # the newest accepted state, for rescore
    seen: tuple[datetime, int] | None  # (Inbox time, _CREATED / _UPDATED)
    published: bytes | None  # hash of the inputs last published
    last_score: float | None = None  # mind_score last published, for rescore


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
        children_client: ChainHierarchyClient | None = None,
    ) -> None:
        self._sink = sink
        self._model = model if model is not None else SegmentedRankingModel()
        self._clock = clock
        # None in the harness (a one-shot run has nothing to cascade to) and
        # in any test that doesn't care about #474's cascade.
        self._children_client = children_client
        self._state: dict[str, _TaskState] = {}
        # One consumer thread per topic, and the rescore worker, share it.
        # Reentrant: scoring a task can call cached_task() for an ancestor
        # (#474's ChainAncestorSource) from the same thread that already
        # holds this lock in _publish_if_changed/_rescore_now -- a plain
        # Lock would deadlock that thread against itself.
        self._lock = threading.RLock()
        # Task ids waiting to be re-scored; see rescore. `_pending` holds the
        # same ids so a repeated request is dropped. It has its own lock, never
        # the one above: rescore may be called while that one is held.
        self._rescores: queue.SimpleQueue[str] = queue.SimpleQueue()
        self._pending: set[str] = set()
        self._pending_lock = threading.Lock()

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
        except Exception:
            # Fails the same way on every redelivery (InvalidInboxEvent, or a
            # conversion bug): skipped, so it cannot stall the partition.
            raw_id = event.id if isinstance(event, TaskCreatedEvent) else event.task_id
            logger.exception("score_publisher: task %r cannot be converted, skipping", raw_id)
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
            _sent, cascade_task_id = self._score_and_send(state, inputs)
        # Outside the lock: the children lookup is a network call, and must
        # not hold up every other task's scoring while it runs (round-9 review).
        if cascade_task_id is not None:
            self._cascade(cascade_task_id)

    def cached_task(self, task_id: str) -> InboxTask | None:
        """`task_id`'s last-known state, or None if Mind has not seen an
        Inbox event for it since start-up. #474's `ChainAncestorSource`
        re-scores an ancestor's state through this to get its own,
        un-propagated value -- never Chain's own, unrelated `score`, and
        never this task's own already-hierarchy-adjusted result.
        """
        with self._lock:
            state = self._state.get(task_id)
            return None if state is None else state.task

    def rescore(self, task_id: str) -> None:
        """Ask for a task to be scored again, with no Inbox event (#474).

        Only queues the id and returns at once, so it is safe to call from
        anywhere, including from inside the model's `score()` while this
        publisher holds its lock. The worker (`run_rescores`) scores the task
        with this publisher's model on its newest state and publishes it.

        Returns nothing: whether the task is known is decided by the worker, which
        logs and skips one no Inbox event has reached since start-up. A request
        for a task already waiting in the queue is dropped.

        Never raises: an id that is not a UUID is logged and dropped, so a bad id
        cannot fail the scoring of the task the caller was working on.
        """
        try:
            task_id = str(UUID(task_id))
        except ValueError:
            logger.error("score_publisher: rescore of %r ignored: not a UUID", task_id)
            return
        with self._pending_lock:
            if task_id in self._pending:
                return
            self._pending.add(task_id)
        self._rescores.put(task_id)

    def run_rescores(self) -> None:
        """The worker: re-score queued tasks, one at a time, forever."""
        while True:
            self._rescore_now(self._take(self._rescores.get()))

    def drain_rescores(self) -> int:
        """Re-score every task queued so far, and return how many were sent."""
        sent = 0
        while True:
            try:
                task_id = self._rescores.get_nowait()
            except queue.Empty:
                return sent
            sent += self._rescore_now(self._take(task_id))

    def _take(self, task_id: str) -> str:
        # Off the pending set before scoring, so a request made while the task
        # is being scored (its parent changed again) is queued, not lost.
        with self._pending_lock:
            self._pending.discard(task_id)
        return task_id

    def _rescore_now(self, task_id: str) -> bool:
        with self._lock:
            state = self._state.get(task_id)
            if state is None:
                # The state is in memory: unknown since start-up until its next event.
                logger.warning("score_publisher: task %s is unknown, not re-scored", task_id)
                return False
            sent, cascade_task_id = self._score_and_send(
                state, _scoring_inputs_hash(state.task), only_if_changed=True
            )
        # Outside the lock -- see _publish_if_changed.
        if cascade_task_id is not None:
            self._cascade(cascade_task_id)
        return sent

    def _score_and_send(
        self, state: _TaskState, inputs: bytes, *, only_if_changed: bool = False
    ) -> tuple[bool, str | None]:
        """Score `state.task`, publish it, and record it. Caller holds the lock.

        Returns `(sent, cascade_task_id)`. `sent` is False, having sent
        nothing, when the task cannot be scored, or, with `only_if_changed`,
        when its mind_score is unchanged within `_CASCADE_CHANGE_TOLERANCE`.
        `cascade_task_id` is this task's id when `_cascade` should run for
        it, or None -- returned rather than called here, so the caller can
        run it after releasing the lock (a network call must not hold up
        every other task's scoring).
        """
        task_id = state.task.task_id
        # Aware, in UTC, like the deadlines the conversion builds.
        now = self._clock()
        try:
            result = self._model.score(state.task, ScoreContext(current_datetime=now))
            payload = build_score_event(state.task, result, scored_at=now)
        except Exception:
            # Scoring this task fails the same way every time (a result without
            # base_score, a score outside 0..1, a model bug for this task):
            # skipped, so one broken task does not stop all scoring.
            logger.exception("score_publisher: task %s cannot be scored, skipping", task_id)
            return False, None
        previous_score = state.last_score
        changed = previous_score is None or (
            abs(payload.mind_score - previous_score) >= _CASCADE_CHANGE_TOLERANCE
        )
        if only_if_changed and not changed:
            # Nothing for Chain to learn; also what ends a cycle of re-scores.
            logger.debug("score_publisher: task %s re-scored unchanged, not sent", task_id)
            return False, None
        # Outside the try: a Kafka error is transient, so it propagates and the
        # message is retried.
        self._sink.send(payload)
        # Recorded only after a successful send, so a failed one is retried.
        state.published = inputs
        state.last_score = payload.mind_score
        return True, (task_id if changed else None)

    def _cascade(self, task_id: str) -> None:
        """#474: tell each direct child its ancestor's share of the score may
        have moved, so it gets a chance to recompute its own mean. Only
        called after a send whose mind_score genuinely differed (see
        `_score_and_send`), outside the lock -- never unconditionally, and
        never from inside the model's own `score()`.

        Never raises: unlike `ChainHierarchyClient`'s own timeouts/HTTP
        errors (already degraded to []), anything else -- a bad URL
        setting, a bug -- would otherwise escape uncaught here on the
        rescore worker thread and kill the whole process via
        `threading.excepthook`, not just skip this one cascade.
        """
        if self._children_client is None:
            return
        try:
            children = self._children_client.get_direct_children(task_id)
        except Exception:
            logger.exception("score_publisher: could not fetch children of %s, skipping", task_id)
            return
        for child_id in children:
            self.rescore(child_id)
