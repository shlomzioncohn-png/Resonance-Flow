"""#470: Kafka -> vector-store indexing handlers, wired to real Kafka
topics by index_consumer_runner.

A handler must let unexpected exceptions propagate (ProtobufConsumer only
commits after it returns) -- only empty/whitespace text is caught here.
Completion comes from task.updated's status, not task.completed (Inbox
has no real caller for that topic)."""

import hashlib
import logging

from mind.embeddings.base import VectorStore
from mind.embeddings.embeddings import embed, task_text
from mind.messaging.task_completed_event_pb2 import TaskCompletedEvent
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskStatus
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent

logger = logging.getLogger(__name__)

# None of these are "active" -- a search shouldn't surface them as live.
_FINISHED_STATUSES = frozenset(
    {TaskStatus.TASK_STATUS_DONE, TaskStatus.TASK_STATUS_REJECTED, TaskStatus.TASK_STATUS_CANCELLED}
)


def _content_hash(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def _embed_task_text(title: str, description: str) -> list[float] | None:
    text = task_text(title, description)
    try:
        return embed(text)
    except ValueError:
        logger.warning("index_consumer: no extractable tokens for %r, skipping", text)
        return None


async def _sync_status(store: VectorStore, task_id: str, status: TaskStatus) -> None:
    if status in _FINISHED_STATUSES:
        await store.mark_completed(task_id)
    else:
        await store.mark_incomplete(task_id)  # handles reopen: DONE -> back to e.g. IN_PROGRESS.


async def handle_task_created(store: VectorStore, event: TaskCreatedEvent) -> None:
    text = task_text(event.title, event.description)
    vector = _embed_task_text(event.title, event.description)
    if vector is None:
        return
    # upsert_if_absent: a task already indexed via #353 must not be overwritten.
    inserted = await store.upsert_if_absent(event.id, vector, content_hash=_content_hash(text))
    if not inserted:
        logger.debug("index_consumer: task %s already indexed, skipping", event.id)
        return
    await _sync_status(store, event.id, event.status)


async def handle_task_updated(store: VectorStore, event: TaskUpdatedEvent) -> None:
    # Skip re-embedding if Chain's scheduling updates didn't change the text.
    text = task_text(event.title, event.description)
    content_hash = _content_hash(text)
    if await store.get_content_hash(event.task_id) != content_hash:
        vector = _embed_task_text(event.title, event.description)
        if vector is None:
            return
        # Plain upsert: self-heals if this consumer never saw task.created.
        await store.upsert(event.task_id, vector, content_hash=content_hash)
    await _sync_status(store, event.task_id, event.status)


async def handle_task_completed(store: VectorStore, event: TaskCompletedEvent) -> None:
    # Honor the event's actual value, don't assume True (see module docstring).
    if event.is_completed:
        marked = await store.mark_completed(event.task_id)
    else:
        marked = await store.mark_incomplete(event.task_id)
    if not marked:
        logger.warning(
            "index_consumer: task %s completion update but was never indexed, skipping",
            event.task_id,
        )
