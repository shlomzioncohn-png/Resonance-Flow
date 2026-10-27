"""#349, #919: long-running process that reads `bridge.raw.landed` and runs
Mind's creation pipeline on it, end to end: each landed item to text, each
text to a task, each new task to `task.creation.requested`, and Inbox's
`task.created` reply matched and indexed. Own process, like
index_consumer_runner.

Three threads, one `TaskCreationFlow`, one `pending` store: the sender (this
consumer), the task.created listener and the overdue timer all share it
(task_creation/README.md, "Three threads, one store"), so they still need
one process even with `pending_store_backend: "postgres"` (#918) --
`PostgresPendingStore` is safe across the three loops (one pool per loop,
opened lazily), but `TaskCreationFlow` itself still takes exactly one
`pending` reference.

Usage:
    make run-landed-consumer
    python -m mind.ingestion.landed_consumer_runner
"""

import asyncio
import logging
import os
import threading
from typing import Any
from uuid import UUID

from mind.config import DeadlineSettings, LandedConsumerSettings, Settings, get_settings
from mind.contracts import RawObjectEvent
from mind.dependencies.adapter import DependencyAdapter
from mind.dependencies.kafka_sink import KafkaDependencySink
from mind.dependencies.postgres_pending import pending_dependency_store
from mind.dependencies.publisher import DependencyPublisher
from mind.embeddings.base import SearchResult
from mind.embeddings.duplicate_detection import find_duplicate
from mind.embeddings.pgvector_store import PgVectorStore
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import BasicExtractor, ExtractionItem
from mind.extraction.providers.factory import build_provider
from mind.ingestion.creation_pipeline import CreationPipeline
from mind.ingestion.landed_consumer import LandedHandler
from mind.ingestion.minio_reader import minio_reader
from mind.ingestion.seen_revisions import InMemoryRevisionStore
from mind.messaging import ProtobufConsumer, ProtobufProducer
from mind.messaging.raw_object_event_pb2 import RawObjectEvent as RawObjectEventMessage
from mind.multimodal import asr
from mind.task_creation.flow import TaskCreationFlow
from mind.task_creation.listener import run_overdue_timer, run_task_created_listener
from mind.task_creation.pending import PendingRequests, PendingStore
from mind.task_creation.postgres_pending_store import PostgresPendingStore

logger = logging.getLogger(__name__)

_TOPIC = "bridge.raw.landed"
# One message may run several OCR (up to 200 s each) and ASR (up to 240 s
# each) calls; librdkafka's default of 5 minutes between polls would have
# Kafka hand the message on mid-way, over and over. Only a handler stuck this
# long is caught by it: a dead process is noticed by heartbeat, not by this.
_MAX_POLL_INTERVAL_MS = 30 * 60 * 1000


def _log_items(event: RawObjectEvent, items: list[ExtractionItem]) -> None:
    # No content: Bridge may have classified the item HIGH sensitivity, and
    # logs travel further than the content was meant to.
    for item in items:
        logger.info(
            "landed: %s ready for extraction, %d chars",
            event.correlation_id,
            len(item.text),
        )


def _pending_store(settings: Settings) -> PendingStore:
    if settings.pending_store_backend == "memory":
        return PendingRequests()
    return PostgresPendingStore(str(settings.vector_store.dsn.get_secret_value()))


def _die_on_thread_crash(args: threading.ExceptHookArgs) -> None:
    # The listener gives up only after MAX_OUTAGE: the whole process goes, so
    # it is restarted, rather than consuming on with nobody matching replies.
    thread_name = args.thread.name if args.thread is not None else "<unknown>"
    logger.critical("landed: thread %s died, exiting process", thread_name, exc_info=args.exc_value)
    os._exit(1)


def main() -> None:
    settings = LandedConsumerSettings()
    logging.basicConfig(level=settings.log_level)
    # Settings first: a missing credential, DSN or LLM provider fails in
    # milliseconds, not after the Whisper load below.
    mind_settings = get_settings()
    vector_store = mind_settings.vector_store
    read_object = minio_reader()
    provider = build_provider()
    # Load Whisper now, not inside the first audio message's handler (R4, #478).
    asr.warm_up()

    threading.excepthook = _die_on_thread_crash
    producer = ProtobufProducer()

    # The listener's side: its own loop, and the index it writes matched tasks
    # through, opened on that loop (a pool belongs to the loop that opened it).
    # SelectorEventLoop, not new_event_loop(): psycopg's async mode rejects
    # Windows' default ProactorEventLoop (the #359 demo runs this on Windows).
    # On Linux it is the default loop anyway.
    listener_loop = asyncio.SelectorEventLoop()
    listener_index = PgVectorStore(vector_store)
    listener_loop.run_until_complete(listener_index.open())
    # #355: each matched task's dependency mentions, resolved against the same
    # index and published to task.dependencies.proposed. on_matched runs on
    # the listener's loop, so it uses the listener's index (dependencies/README.md).
    # #918: a mention that doesn't resolve now is retried later from the index
    # consumer's on_indexed hook -- both need the same durable store to see it.
    dependencies = DependencyAdapter(
        DependencyPublisher(
            listener_index,
            KafkaDependencySink(producer),
            pending_dependency_store(mind_settings),
        )
    )
    pending = _pending_store(mind_settings)
    flow = TaskCreationFlow(
        producer=producer,
        index=listener_index,
        pending=pending,
        on_matched=dependencies.on_task_created,
    )
    stop = threading.Event()
    # Daemon: the listener blocks in Kafka's poll and ends with the process.
    threading.Thread(
        target=run_task_created_listener,
        args=(flow, listener_loop),
        name="task-created-listener",
        daemon=True,
    ).start()
    threading.Thread(
        target=run_overdue_timer, args=(flow, stop), name="overdue-timer", daemon=True
    ).start()

    # The sender's side: this thread's loop, and the index it searches for duplicates.
    loop = asyncio.SelectorEventLoop()
    search_index = PgVectorStore(vector_store)
    loop.run_until_complete(search_index.open())
    extractor = BasicExtractor(provider, ExtractionConfig(classify=True))

    async def _find_duplicate(tenant_id: UUID, title: str, description: str) -> SearchResult | None:
        # Only the item's own tenant (#841).
        return await find_duplicate(search_index.for_tenant(tenant_id), title, description)

    pipeline = CreationPipeline(
        extract=lambda item: extractor.extract(item.text, item.source_type, item.item_key),
        find_duplicate=_find_duplicate,
        sender=flow,
        # Read once at start: a bad zone or working week stops the runner here,
        # not on the first deadline (#960).
        deadline_settings=DeadlineSettings(),
    )

    def _on_items(event: RawObjectEvent, items: list[ExtractionItem]) -> None:
        _log_items(event, items)
        loop.run_until_complete(pipeline.handle(event, items))

    handle = LandedHandler(
        read_object=read_object,
        store=InMemoryRevisionStore(),
        on_items=_on_items,
    )

    def _handler(event: Any, msg: object) -> None:
        # Any: run() has already deserialized it into a RawObjectEventMessage.
        # No try/except: a failure that may pass later must propagate, so the
        # offset stays uncommitted and the message comes again after a restart.
        handle(event, msg)

    consumer = ProtobufConsumer(
        group_id=settings.group_id,
        topics=[_TOPIC],
        conf_overrides={"max.poll.interval.ms": _MAX_POLL_INTERVAL_MS},
    )
    try:
        consumer.run(RawObjectEventMessage, _handler, idle_timeout=None)
    finally:
        stop.set()
        loop.run_until_complete(search_index.close())
        # This thread's own share of `pending` (it calls flow.send(), which
        # uses `pending` -- see send.py). Its pool for `listener_loop`, and
        # `listener_index`/the dependency pending store there, are not
        # closed here: `run_task_created_listener` has no graceful-stop
        # path today (a pre-existing gap, not one #918 introduces) to run
        # cleanup on that loop while it's still blocked inside it.
        if isinstance(pending, PostgresPendingStore):
            loop.run_until_complete(pending.close())
        loop.close()
        producer.close()
        provider.close()


if __name__ == "__main__":
    main()
