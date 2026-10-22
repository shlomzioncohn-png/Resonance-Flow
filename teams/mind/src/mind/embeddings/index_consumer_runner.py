"""#470: long-running process syncing the vector store with Inbox's
task.{created,updated,completed} topics. Own process, not the FastAPI
lifespan -- ProtobufConsumer.run() blocks, one thread per topic.

Lives under src/, not scripts/, so ruff/mypy actually check it.

Usage:
    python -m mind.embeddings.index_consumer_runner
"""

import asyncio
import logging
import os
import threading
from collections.abc import Awaitable, Callable
from typing import TypeVar, cast

from google.protobuf.message import Message

from mind.config import get_settings
from mind.embeddings.base import VectorStore
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

_EventT = TypeVar("_EventT", bound=Message)
_Handler = Callable[[VectorStore, _EventT], Awaitable[None]]

_TOPICS: list[tuple[str, type[Message], _Handler]] = [
    ("task.created", TaskCreatedEvent, handle_task_created),
    ("task.updated", TaskUpdatedEvent, handle_task_updated),
    ("task.completed", TaskCompletedEvent, handle_task_completed),
]


def _run_topic(topic: str, message_type: type[Message], handle: _Handler) -> None:
    loop = asyncio.new_event_loop()
    try:
        store = PgVectorStore(get_settings().vector_store)
        try:
            loop.run_until_complete(store.open())

            def _handler(event: object, msg: object) -> None:
                # No try/except -- must propagate so the offset stays uncommitted.
                # cast: run() already deserialized event into this exact type.
                loop.run_until_complete(handle(store, cast(Message, event)))

            consumer = ProtobufConsumer(group_id=_GROUP_ID, topics=[topic])
            consumer.run(message_type, _handler, idle_timeout=None)
        finally:
            loop.run_until_complete(store.close())
    finally:
        loop.close()


def _die_on_thread_crash(args: threading.ExceptHookArgs) -> None:
    # A dead topic-thread takes the whole process down, not just itself.
    thread_name = args.thread.name if args.thread is not None else "<unknown>"
    logger.critical("index_consumer: thread %s died, exiting process", thread_name, exc_info=args.exc_value)
    os._exit(1)


def main() -> None:
    logging.basicConfig(level=get_settings().log_level)
    threading.excepthook = _die_on_thread_crash
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
