"""`TaskCreationFlow`: one object for the sender, the listener and the timer,
so all three use one pending store. How to compose it: README.md, "Running it".
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence
from datetime import datetime, timedelta
from typing import TYPE_CHECKING
from uuid import UUID

from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.embeddings.base import VectorStore
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.overdue import DEFAULT_MAX_WAIT, report_overdue
from mind.task_creation.pending import PendingStore
from mind.task_creation.reply import Headers, MatchedReply, OnMatched, handle_task_created_reply
from mind.task_creation.send import send_creation_request

if TYPE_CHECKING:
    from mind.messaging.producer import Publisher


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
        on_tick: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        """`on_matched` is told of each request Inbox turned into a task (#355's
        hook) — at least once, maybe twice. `on_tick` runs on every overdue
        check, independent of on_matched, for work that needs to happen
        periodically regardless of whether Mind sent the request that created
        the task being waited on."""

        self._producer = producer
        self._index = index
        self._pending = pending
        self._max_wait = max_wait
        self._on_matched = on_matched
        self._on_tick = on_tick

    async def send(
        self,
        request: TaskCreationRequested,
        *,
        bridge_correlation_id: UUID,
        revision: int,
        task_index: int = 0,
        dependency_mentions: Sequence[str] = (),
    ) -> UUID:
        """What the pipeline calls for each task it extracted."""

        return await send_creation_request(
            self._producer,
            self._pending,
            request,
            bridge_correlation_id=bridge_correlation_id,
            revision=revision,
            task_index=task_index,
            dependency_mentions=dependency_mentions,
        )

    async def on_task_created(self, event: TaskCreatedEvent, headers: Headers) -> MatchedReply | None:
        """What the listener calls for each task.created."""

        return await handle_task_created_reply(
            self._index, self._pending, event, headers, self._on_matched
        )

    async def check_overdue(self, now: datetime) -> list[UUID]:
        """What the timer calls, about once a minute."""

        if self._on_tick is not None:
            await self._on_tick()
        return await report_overdue(self._pending, now=now, max_wait=self._max_wait)
