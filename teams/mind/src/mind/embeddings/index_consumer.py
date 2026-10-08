"""#470: Kafka -> vector-store indexing handlers, one per topic,
testable against a fake VectorStore with no broker. Wired to real Kafka
topics by scripts/index_consumer.py.

Every handler is defensive end-to-end (log, never raise) -- an uncaught
exception would crash that topic's consumer thread before the commit.
"""

import logging

from mind.embeddings.base import VectorStore
from mind.embeddings.embeddings import embed
from mind.messaging.task_completed_event_pb2 import TaskCompletedEvent
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent

logger = logging.getLogger(__name__)


def _embed_task_text(title: str, description: str) -> list[float] | None:
    text = f"{title} {description}".strip()
    try:
        return embed(text)
    except ValueError:
        logger.warning("index_consumer: no extractable tokens for %r, skipping", text)
        return None


async def handle_task_created(store: VectorStore, event: TaskCreatedEvent) -> None:
    vector = _embed_task_text(event.title, event.description)
    if vector is None:
        return
    # upsert_if_absent: a task already indexed via #353 must not be overwritten.
    inserted = await store.upsert_if_absent(event.id, vector)
    if not inserted:
        logger.debug("index_consumer: task %s already indexed, skipping", event.id)


async def handle_task_updated(store: VectorStore, event: TaskUpdatedEvent) -> None:
    vector = _embed_task_text(event.title, event.description)
    if vector is None:
        return
    # Plain upsert: self-heals if this consumer never saw task.created.
    await store.upsert(event.task_id, vector)


async def handle_task_completed(store: VectorStore, event: TaskCompletedEvent) -> None:
    # No title/description on this event -- nothing to backfill from.
    marked = await store.mark_completed(event.task_id)
    if not marked:
        logger.warning(
            "index_consumer: task %s marked completed but was never indexed, skipping",
            event.task_id,
        )
