"""#470: Kafka -> vector-store indexing. Pure async handler functions, one
per topic, independent of Kafka itself -- testable against a fake
VectorStore with no broker and no real Postgres (see
tests/test_index_consumer.py). The process that wires these to real Kafka
topics is scripts/index_consumer.py.

Every handler is defensive end-to-end (log, never raise): ProtobufConsumer.run()
(messaging/consumer.py) commits the offset right after calling the handler,
with no try/except around either -- an exception here would crash that
topic's consumer thread before the commit, and a restart would reprocess
(and likely re-crash on) the same message forever.
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
    # upsert_if_absent, not upsert: a task already indexed via #353 (e.g.
    # Inbox replaying task.created) must not be overwritten by this path.
    inserted = await store.upsert_if_absent(event.id, vector)
    if not inserted:
        logger.debug("index_consumer: task %s already indexed, skipping", event.id)


async def handle_task_updated(store: VectorStore, event: TaskUpdatedEvent) -> None:
    vector = _embed_task_text(event.title, event.description)
    if vector is None:
        return
    # Plain upsert: an update for a task_id this consumer never saw a
    # task.created for simply creates the row -- self-healing, not an error.
    await store.upsert(event.task_id, vector)


async def handle_task_completed(store: VectorStore, event: TaskCompletedEvent) -> None:
    # No title/description on this event, so there's nothing to backfill
    # from -- mark_completed() is a no-op update, never an insert. Inbox
    # has no fetch-by-id API, so an unknown task_id is log-and-skip, not a
    # recoverable error.
    marked = await store.mark_completed(event.task_id)
    if not marked:
        logger.warning(
            "index_consumer: task %s marked completed but was never indexed, skipping",
            event.task_id,
        )
