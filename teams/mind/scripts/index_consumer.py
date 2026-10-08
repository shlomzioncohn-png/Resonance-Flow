#!/usr/bin/env python3
"""#470: long-running process keeping the vector store in sync with
Inbox's task.{created,updated,completed} topics. Own process/container,
not inside the FastAPI lifespan -- ProtobufConsumer.run() blocks with no
stop signal, and each topic needs its own type/thread/event loop.

Usage:
    python scripts/index_consumer.py
"""

import asyncio
import logging
import threading

from mind.config import get_settings
from mind.embeddings.index_consumer import (
    handle_task_completed,
    handle_task_created,
    handle_task_updated,
)
from mind.embeddings.pgvector_store import PgVectorStore
from mind.messaging import ProtobufConsumer
from mind.messaging.task_completed_event_pb2 import TaskCompletedEvent
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent

logger = logging.getLogger(__name__)

_GROUP_ID = "mind-index-consumer"
_TOPICS = [
    ("task.created", TaskCreatedEvent, handle_task_created),
    ("task.updated", TaskUpdatedEvent, handle_task_updated),
    ("task.completed", TaskCompletedEvent, handle_task_completed),
]


def _run_topic(topic: str, message_type: type, handle) -> None:
    loop = asyncio.new_event_loop()
    store = PgVectorStore(get_settings().vector_store)
    loop.run_until_complete(store.open())

    def _handler(event: object, msg: object) -> None:
        try:
            loop.run_until_complete(handle(store, event))
        except Exception:
            logger.exception("index_consumer: failed handling a message on %s", topic)

    consumer = ProtobufConsumer(group_id=_GROUP_ID, topics=[topic])
    try:
        consumer.run(message_type, _handler, idle_timeout=None)
    finally:
        loop.run_until_complete(store.close())
        loop.close()


def main() -> None:
    logging.basicConfig(level=get_settings().log_level)
    threads = [
        threading.Thread(target=_run_topic, args=t, name=f"index-consumer-{t[0]}")
        for t in _TOPICS
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


if __name__ == "__main__":
    main()
