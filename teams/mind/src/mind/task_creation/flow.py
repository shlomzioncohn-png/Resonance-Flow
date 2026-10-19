"""`TaskCreationFlow`: one object for the sender, the listener and the timer,
so all three use one pending store. How to compose it: README.md, "Running it".
"""

from datetime import datetime, timedelta
from uuid import UUID

from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.embeddings.base import VectorStore
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.overdue import DEFAULT_MAX_WAIT, report_overdue
from mind.task_creation.pending import PendingStore
from mind.task_creation.reply import Headers, MatchedReply, OnMatched, handle_task_created_reply
from mind.task_creation.send import Publisher, send_creation_request


class TaskCreationFlow:
    """Send creation requests, match their replies, report the unanswered."""

    def __init__(
        self,
        *,
        producer: Publisher,
        index: VectorStore,
        pending: PendingStore,
        max_wait: timedelta = DEFAULT_MAX_WAIT,
        on_matched: OnMatched | None = None,
    ) -> None:
        """`on_matched` is told of each request Inbox turned into a task (#355's
        hook) — at least once, maybe twice."""

        self._producer = producer
        self._index = index
        self._pending = pending
        self._max_wait = max_wait
        self._on_matched = on_matched

    async def send(
        self,
        request: TaskCreationRequested,
        *,
        bridge_correlation_id: UUID,
        revision: int,
        task_index: int = 0,
    ) -> UUID:
        """What the pipeline calls for each task it extracted."""

        return await send_creation_request(
            self._producer,
            self._pending,
            request,
            bridge_correlation_id=bridge_correlation_id,
            revision=revision,
            task_index=task_index,
        )

    async def on_task_created(self, event: TaskCreatedEvent, headers: Headers) -> MatchedReply | None:
        """What the listener calls for each task.created."""

        return await handle_task_created_reply(
            self._index, self._pending, event, headers, self._on_matched
        )

    async def check_overdue(self, now: datetime) -> list[UUID]:
        """What the timer calls, about once a minute."""

        return await report_overdue(self._pending, now=now, max_wait=self._max_wait)
