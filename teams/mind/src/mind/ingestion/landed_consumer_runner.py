"""#349, #919: long-running process that reads `bridge.raw.landed` and runs
Mind's creation pipeline on it, end to end: each landed item to text, each
text to a task, each new task to `task.creation.requested`, and Inbox's
`task.created` reply matched and indexed. Own process, like
index_consumer_runner.

Three threads, one `TaskCreationFlow`: the pending store is in memory, so the
sender (this consumer) and the task.created listener must share it, and so
one process (task_creation/README.md, "Running it"). The overdue timer runs
beside them. When the store moves to Postgres (#918) the listener can move
out.

Every model call is attributed to its item and stage (#482,
observability/context.py), set here, where messages are handled, so no
handler changes: a landed item is `landed`, with its objects' OCR/ASR in
`to_text`, its LLM calls in `extraction` and each candidate's index search in
`duplicate_check`; Inbox's task.created reply is `task_created`, with its task.

Usage:
    make run-landed-consumer
    python -m mind.ingestion.landed_consumer_runner
"""

import asyncio
import logging
import os
import threading
from collections.abc import Callable
from contextlib import AbstractContextManager
from typing import Any
from uuid import UUID

from mind.config import DeadlineSettings, LandedConsumerSettings, get_settings
from mind.contracts import RawObjectEvent
from mind.dependencies.adapter import DependencyAdapter
from mind.dependencies.kafka_sink import KafkaDependencySink
from mind.dependencies.publisher import DependencyPublisher
from mind.embeddings.base import SearchResult
from mind.embeddings.duplicate_detection import find_duplicate
from mind.embeddings.pgvector_store import PgVectorStore
from mind.extraction.assembly import source_ref_for_inbox
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import BasicExtractor, ExtractionItem
from mind.extraction.providers.factory import build_provider
from mind.ingestion.creation_pipeline import CreationPipeline
from mind.ingestion.extraction_items import extraction_items
from mind.ingestion.landed_consumer import LandedHandler
from mind.ingestion.minio_reader import minio_reader
from mind.ingestion.seen_revisions import InMemoryRevisionStore
from mind.messaging import ProtobufConsumer, ProtobufProducer
from mind.messaging.raw_object_event_pb2 import RawObjectEvent as RawObjectEventMessage
from mind.multimodal import asr
from mind.observability.context import CallContext, call_context, message_context
from mind.task_creation.flow import TaskCreationFlow
from mind.task_creation.listener import GROUP_ID as TASK_CREATED_GROUP_ID
from mind.task_creation.listener import TOPIC as TASK_CREATED_TOPIC
from mind.task_creation.listener import run_overdue_timer, run_task_created_listener
from mind.task_creation.pending import PendingRequests

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


def _inbox_source_ref(message: RawObjectEventMessage) -> str | None:
    """The source_ref Mind will send Inbox for this item's tasks, as
    `to_task_creation_requested` builds it -- the one the API sees again on
    task.created -- or None when the event is too broken to have one (it is
    skipped as invalid anyway)."""
    try:
        bridge_ref = message.source_ref if message.HasField("source_ref") else None
        return source_ref_for_inbox(bridge_ref, UUID(message.correlation_id))
    except ValueError:
        return None


def _landed_context(message: RawObjectEventMessage) -> AbstractContextManager[CallContext]:
    """The calls of one landed item, attributed to it (#482)."""
    return message_context(
        stage="landed", tenant_id=message.tenant_id, source_ref=_inbox_source_ref(message)
    )


def _in_stage[**P, R](stage: str, call: Callable[P, R]) -> Callable[P, R]:
    """`call`, with the calls recorded inside it attributed to `stage`."""

    def staged(*args: P.args, **kwargs: P.kwargs) -> R:
        with call_context(stage=stage):
            return call(*args, **kwargs)

    return staged


class _AttributedTaskCreatedConsumer:
    """task.created's consumer, each message's calls attributed to its task (#482).

    Handed to run_task_created_listener as its consumer_factory, so the
    listener itself is unchanged: indexing and dependency resolution run
    inside `handler`, on a loop this thread drives, so they see the context.
    """

    def __init__(self) -> None:
        self._consumer = ProtobufConsumer(
            group_id=TASK_CREATED_GROUP_ID, topics=[TASK_CREATED_TOPIC]
        )

    def run(
        self,
        message_type: type,
        handler: Callable[[object, object], None],
        *,
        idle_timeout: float | None = None,
    ) -> int:
        def attributed(event: Any, message: object) -> None:
            with message_context(
                stage="task_created",
                tenant_id=event.tenant_id,
                source_ref=event.source_ref,
                task=event.id,
            ):
                handler(event, message)

        return self._consumer.run(message_type, attributed, idle_timeout=idle_timeout)


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
    vector_store = get_settings().vector_store
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
    dependencies = DependencyAdapter(
        DependencyPublisher(listener_index, KafkaDependencySink(producer))
    )
    flow = TaskCreationFlow(
        producer=producer,
        index=listener_index,
        pending=PendingRequests(),
        on_matched=dependencies.on_task_created,
    )
    stop = threading.Event()
    # Daemon: the listener blocks in Kafka's poll and ends with the process.
    threading.Thread(
        target=run_task_created_listener,
        args=(flow, listener_loop),
        kwargs={"consumer_factory": _AttributedTaskCreatedConsumer},
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
        with call_context(stage="duplicate_check"):
            return await find_duplicate(search_index.for_tenant(tenant_id), title, description)

    pipeline = CreationPipeline(
        extract=_in_stage(
            "extraction",
            lambda item: extractor.extract(item.text, item.source_type, item.item_key),
        ),
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
        to_items=_in_stage("to_text", extraction_items),
    )

    def _handler(event: Any, msg: object) -> None:
        # Any: run() has already deserialized it into a RawObjectEventMessage.
        # No try/except: a failure that may pass later must propagate, so the
        # offset stays uncommitted and the message comes again after a restart.
        with _landed_context(event):
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
        loop.close()
        producer.close()
        provider.close()


if __name__ == "__main__":
    main()
