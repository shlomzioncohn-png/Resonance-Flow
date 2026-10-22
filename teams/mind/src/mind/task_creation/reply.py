"""Matching Inbox's task.created to the request Mind sent, by the correlation_id header.

Why index and tell before forgetting, and the three ways a reply is ignored:
README.md, "Order: register, then publish; index, then forget".
"""

import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from mind.embeddings.base import VectorIndex
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
    """A request Inbox has turned into a task: its real id, and what Mind knew when it asked."""

    correlation_id: UUID
    task_id: str
    request: PendingRequest


#: Told of each request Inbox turned into a task (#355's way in) — at least once, maybe twice.
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
    index: VectorIndex,
    pending: PendingStore,
    event: TaskCreatedEvent,
    headers: Headers,
    on_matched: OnMatched | None = None,
) -> MatchedReply | None:
    """Index a task Mind asked for and tell `on_matched`, then forget the
    request; ignore a task it did not ask for. Errors propagate."""

    correlation_id = correlation_id_from(headers)
    if correlation_id is None:
        raw = _correlation_header(headers)
        if raw is not None:
            logger.warning(
                "task_creation: task.created %s has an unreadable correlation_id %r; ignored",
                event.id,
                raw,
            )
        else:
            logger.debug("task_creation: task.created %s carries no correlation_id; ignored", event.id)
        return None

    request = await pending.get(correlation_id)
    if request is None:
        logger.info(
            "task_creation: task.created %s matches no pending request (correlation %s); ignored",
            event.id,
            correlation_id,
        )
        return None

    await handle_task_created(index, event)
    matched = MatchedReply(correlation_id=correlation_id, task_id=event.id, request=request)
    if on_matched is not None:
        await on_matched(matched)
    await pending.discard(correlation_id)
    logger.info(
        "task_creation: request %s answered after %.1fs as task %s",
        correlation_id,
        (datetime.now(UTC) - request.sent_at).total_seconds(),
        event.id,
    )
    return matched
