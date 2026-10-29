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
from uuid import UUID

from google.protobuf.message import Message

from mind.config import get_settings
from mind.dependencies.kafka_sink import KafkaDependencySink
from mind.dependencies.pending import PendingDependencyStore
from mind.dependencies.postgres_pending import (
    PostgresPendingDependencyStore,
    pending_dependency_store,
)
from mind.dependencies.publisher import DependencyPublisher
from mind.dependencies.retry import retry_pending_dependencies
from mind.embeddings.base import VectorIndex
from mind.embeddings.index_consumer import (
    OnIndexed,
    handle_task_completed,
    handle_task_created,
    handle_task_updated,
)
from mind.embeddings.pgvector_store import PgVectorStore
from mind.messaging import ProtobufConsumer
from mind.messaging.producer import ProtobufProducer
from mind.messaging.task_completed_event_pb2 import TaskCompletedEvent
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent

logger = logging.getLogger(__name__)

_GROUP_ID = "mind-index-consumer"

_EventT = TypeVar("_EventT", bound=Message)
_Handler = Callable[[VectorIndex, _EventT], Awaitable[None]]

_TOPICS: list[tuple[str, type[Message], _Handler]] = [
    ("task.updated", TaskUpdatedEvent, handle_task_updated),
    ("task.completed", TaskCompletedEvent, handle_task_completed),
]


def _make_dependency_retry_hook(
    pending: PendingDependencyStore, publisher: DependencyPublisher
) -> OnIndexed:
    """#918: an `on_indexed` callback that re-resolves every dependency
    mention still pending for the indexed task's tenant -- not just the
    task that was just indexed, since any of them could be its target."""

    async def _on_indexed(task_id: str, tenant_id: str, event: TaskCreatedEvent) -> None:
        try:
            tenant_uuid = UUID(tenant_id)
        except ValueError:
            logger.warning(
                "index_consumer: task %s has tenant_id %r, not a UUID, "
                "skipping dependency retry",
                task_id,
                tenant_id,
            )
            return
        await retry_pending_dependencies(pending, publisher, tenant_uuid)

    return _on_indexed


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


def _run_task_created_topic() -> None:
    """Same as `_run_topic`, plus #918's dependency-retry `on_indexed`
    hook -- its own `PostgresPendingDependencyStore` and Kafka producer,
    bound to this thread's loop like `store` already is."""

    loop = asyncio.new_event_loop()
    try:
        settings = get_settings()
        store = PgVectorStore(settings.vector_store)
        pending = pending_dependency_store(settings)
        try:
            loop.run_until_complete(store.open())
            if isinstance(pending, PostgresPendingDependencyStore):
                loop.run_until_complete(pending.open())

            producer = ProtobufProducer()
            sink = KafkaDependencySink(producer)
            publisher = DependencyPublisher(store, sink, pending)
            on_indexed = [_make_dependency_retry_hook(pending, publisher)]

            def _handler(event: object, msg: object) -> None:
                loop.run_until_complete(
                    handle_task_created(store, cast(TaskCreatedEvent, event), on_indexed)
                )

            consumer = ProtobufConsumer(group_id=_GROUP_ID, topics=["task.created"])
            consumer.run(TaskCreatedEvent, _handler, idle_timeout=None)
        finally:
            if isinstance(pending, PostgresPendingDependencyStore):
                loop.run_until_complete(pending.close())
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
        threading.Thread(target=_run_task_created_topic, name="index-consumer-task.created")
    ] + [
        threading.Thread(target=_run_topic, args=t, name=f"index-consumer-{t[0]}")
        for t in _TOPICS
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


if __name__ == "__main__":
    main()
