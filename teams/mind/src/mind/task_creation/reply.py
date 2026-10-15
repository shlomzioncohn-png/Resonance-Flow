"""Recognising Inbox's task.created as the answer to a request Mind sent (#353).

The correlation_id comes back in a Kafka header, not in the body (confirmed by
Inbox). A reply that matches a pending request is indexed under the task's real
id, and only then forgotten: if the index write fails, the error propagates, the
offset is not committed, Kafka redelivers — and the redelivery must still find
its request. Forgetting first would turn every redelivery into an "unknown"
reply, dropped in silence.

A reply that matches nothing is logged and ignored, never an error: tasks that
anyone else creates arrive on the same topic.
"""

import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from mind.embeddings.base import VectorStore
from mind.embeddings.index_consumer import handle_task_created
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.pending import PendingRequest, PendingStore

logger = logging.getLogger("mind.task_creation")

#: The header Inbox puts the correlation_id in on task.created.
CORRELATION_HEADER = "correlation_id"

#: Kafka message headers as confluent-kafka returns them: None when there are none.
Headers = Sequence[tuple[str, bytes | None]] | None


@dataclass(frozen=True)
class MatchedReply:
    """A request Inbox has turned into a task.

    The hook for what follows a real task id: #355 publishes dependencies from
    it, and a later update flow compares the item's revision against it.
    """

    correlation_id: UUID
    task_id: str
    request: PendingRequest


#: Told of each request Inbox turned into a task — #355's way in. It may hear of
#: the same task twice (see `handle_task_created_reply`), so it must not mind.
OnMatched = Callable[[MatchedReply], Awaitable[None]]


def _correlation_header(headers: Headers) -> bytes | None:
    """The raw correlation_id header, or None if the message has none."""

    for name, value in headers or ():
        if name == CORRELATION_HEADER and value is not None:
            return value
    return None


def correlation_id_from(headers: Headers) -> UUID | None:
    """The correlation_id header as a UUID, or None if absent or unreadable."""

    raw = _correlation_header(headers)
    if raw is None:
        return None
    try:
        return UUID(raw.decode())
    except (UnicodeDecodeError, ValueError):
        return None


async def handle_task_created_reply(
    store: VectorStore,
    pending: PendingStore,
    event: TaskCreatedEvent,
    headers: Headers,
    on_matched: OnMatched | None = None,
) -> MatchedReply | None:
    """Index a task Mind asked for, tell `on_matched`; ignore one it did not.

    The request is forgotten last. If the index write or `on_matched` fails,
    the error propagates, Kafka redelivers, and the redelivery still finds the
    request — so `on_matched` runs at least once per task, and may run twice.
    """

    correlation_id = correlation_id_from(headers)
    if correlation_id is None:
        raw = _correlation_header(headers)
        if raw is not None:
            # Present but not a UUID: the format changed somewhere. Left at
            # DEBUG it would drop every reply in silence, so it is a warning,
            # with what actually arrived.
            logger.warning(
                "task_creation: task.created %s has an unreadable correlation_id %r; ignored",
                event.id,
                raw,
            )
        else:
            # A task Mind did not ask for — every task anyone else creates
            # comes through here, so this stays below INFO.
            logger.debug("task_creation: task.created %s carries no correlation_id; ignored", event.id)
        return None

    request = await pending.get(correlation_id)
    if request is None:
        # Worth seeing: a reply to a request this process no longer holds — a
        # restart in between, or a redelivery of one already matched.
        logger.info(
            "task_creation: task.created %s matches no pending request (correlation %s); ignored",
            event.id,
            correlation_id,
        )
        return None

    # R3's handler: the same embedding, and upsert_if_absent, so whichever of
    # the two consumers writes first, the other does not overwrite it.
    await handle_task_created(store, event)
    matched = MatchedReply(correlation_id=correlation_id, task_id=event.id, request=request)
    if on_matched is not None:
        await on_matched(matched)
    await pending.discard(correlation_id)
    # The data for setting the overdue limit from real latencies (twice the
    # usual) instead of the fixed default it starts with.
    logger.info(
        "task_creation: request %s answered after %.1fs as task %s",
        correlation_id,
        (datetime.now(UTC) - request.sent_at).total_seconds(),
        event.id,
    )
    return matched
