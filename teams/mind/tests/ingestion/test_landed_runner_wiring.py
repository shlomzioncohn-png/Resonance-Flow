"""#919: `landed_consumer_runner.main()` as it is composed, with every outside
party faked -- Kafka, MinIO, Postgres, the LLM, Whisper.

`test_creation_pipeline.py` checks the pipeline on its own; this checks what
only the runner decides: that the sender and the listener share one flow (so
one pending store), that each index is opened on the loop that uses it, and
that what main() opened it also closes.
"""

import asyncio
import threading
from types import SimpleNamespace
from typing import Any
from uuid import UUID

import pytest
from tests.extraction.fakes import ScriptedProvider
from tests.ingestion.test_creation_pipeline import ANSWER, TASK_ID, TITLE, FakeProducer
from tests.ingestion.test_landed_consumer import ITEM, TENANT, _message, _read
from tests.test_index_consumer import FakeTenantStore, FakeVectorIndex

import mind.ingestion.landed_consumer_runner as runner
from mind.embeddings.confidence import ScoredResult
from mind.extraction.assembly import source_ref_for_inbox
from mind.messaging.raw_object_event_pb2 import RawObjectEvent as RawObjectEventMessage
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.observability.context import CallContext, current_context, item_ref
from mind.observability.tracking import CallKind, _reset_for_tests, get_recent_calls
from mind.task_creation.correlation import correlation_id_for
from mind.task_creation.flow import TaskCreationFlow


class Store(FakeVectorIndex):
    """Remembers the loop it was opened on, and whether it was closed."""

    def __init__(self, settings: object) -> None:
        super().__init__()
        self.settings = settings
        self.opened_on: asyncio.AbstractEventLoop | None = None
        self.closed = False

    async def open(self) -> None:
        self.opened_on = asyncio.get_running_loop()

    async def close(self) -> None:
        self.closed = True


class Producer(FakeProducer):
    closed = False

    def close(self) -> None:
        self.closed = True


class Provider(ScriptedProvider):
    closed = False

    def close(self) -> None:
        self.closed = True


class World:
    """Every party main() reaches, faked and recorded."""

    def __init__(
        self,
        monkeypatch: pytest.MonkeyPatch,
        messages: list[RawObjectEventMessage],
        match: ScoredResult | None = None,
    ) -> None:
        self.stores: list[Store] = []
        self.searched_in: list[tuple[FakeVectorIndex, UUID]] = []
        self.producer = Producer()
        self.provider = Provider(*[ANSWER] * len(messages))
        self.consumer: dict[str, Any] = {}
        self.listener: tuple[TaskCreationFlow, asyncio.AbstractEventLoop] | None = None
        self.listener_consumer_factory: Any = None
        self.duplicate_check_context: CallContext | None = None
        self.timer: tuple[TaskCreationFlow, threading.Event] | None = None
        self.threads_ran = threading.Barrier(3, timeout=5)

        def make_store(settings: object) -> Store:
            self.stores.append(Store(settings))
            return self.stores[-1]

        async def find_duplicate(
            store: FakeTenantStore, title: str, description: str
        ) -> ScoredResult | None:
            # The tenant's slice of an index: which index, and which tenant (#841).
            self.searched_in.append((store.index, store.tenant_id))
            self.duplicate_check_context = current_context()
            return match

        def listener(
            flow: TaskCreationFlow,
            loop: asyncio.AbstractEventLoop,
            *,
            consumer_factory: Any = None,
        ) -> None:
            self.listener = (flow, loop)
            self.listener_consumer_factory = consumer_factory
            self.threads_ran.wait()

        def timer(flow: TaskCreationFlow, stop: threading.Event) -> None:
            self.timer = (flow, stop)
            self.threads_ran.wait()

        world = self

        class Consumer:
            def __init__(self, **kwargs: Any) -> None:
                world.consumer = kwargs

            def run(self, message_type: type, handler: Any, *, idle_timeout: float | None) -> int:
                for message in messages:
                    handler(message, None)
                return len(messages)

        # Restored after the test: main() installs its own.
        monkeypatch.setattr(threading, "excepthook", threading.excepthook)
        monkeypatch.setattr("mind.embeddings.index_consumer.embed", lambda _text: [0.1, 0.2, 0.3])
        monkeypatch.setattr(
            runner,
            "get_settings",
            lambda: SimpleNamespace(vector_store="vs", pending_store_backend="memory"),
        )
        monkeypatch.setattr(runner, "minio_reader", lambda: _read)
        monkeypatch.setattr(runner, "build_provider", lambda: self.provider)
        monkeypatch.setattr(runner.asr, "warm_up", lambda: True)
        self.embeddings_warmed = False

        def warm_up_embeddings() -> bool:
            self.embeddings_warmed = True
            return True

        monkeypatch.setattr(runner, "warm_up_embeddings", warm_up_embeddings)
        monkeypatch.setattr(runner, "ProtobufProducer", lambda: self.producer)
        monkeypatch.setattr(runner, "PgVectorStore", make_store)
        monkeypatch.setattr(runner, "find_duplicate", find_duplicate)
        monkeypatch.setattr(runner, "run_task_created_listener", listener)
        monkeypatch.setattr(runner, "run_overdue_timer", timer)
        monkeypatch.setattr(runner, "ProtobufConsumer", Consumer)

    def run_main(self) -> None:
        runner.main()
        self.threads_ran.wait()  # both threads have recorded what they were given


def test_a_landed_item_is_sent_and_its_reply_matched_through_the_listeners_flow(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    world = World(monkeypatch, [_message()])

    world.run_main()

    ((topic, request, key),) = world.producer.sent
    assert (topic, request.title) == ("task.creation.requested", TITLE)
    assert key == str(correlation_id_for(ITEM, 0))

    # One flow for the sender, the listener and the timer: the reply finds the
    # request only because the listener's flow holds the same pending store.
    assert world.listener is not None and world.timer is not None
    flow, listener_loop = world.listener
    assert world.timer[0] is flow
    reply = TaskCreatedEvent(
        id=TASK_ID, tenant_id=TENANT, title=TITLE, description="Asked for in Slack, due tomorrow."
    )
    matched = listener_loop.run_until_complete(
        flow.on_task_created(reply, [("correlation_id", key.encode())])
    )
    assert matched is not None and matched.task_id == TASK_ID

    # Two indexes, each opened on the loop that uses it: the listener's gets
    # the matched task, the other is the one searched for duplicates.
    ((searched, tenant),) = world.searched_in
    assert tenant == UUID(TENANT)  # only the item's own tenant's tasks (#841)
    (indexed,) = [store for store in world.stores if TASK_ID in store.rows]
    assert searched is not indexed
    assert indexed.opened_on is listener_loop
    assert searched.opened_on is not listener_loop
    assert {store.settings for store in world.stores} == {"vs"}


def test_a_duplicate_is_not_sent(monkeypatch: pytest.MonkeyPatch) -> None:
    world = World(
        monkeypatch, [_message()], match=ScoredResult(task_id=TASK_ID, similarity=0.9, confidence=0.7)
    )

    world.run_main()

    assert world.producer.sent == []
    assert len(world.searched_in) == 1


def test_the_consumer_reads_bridge_raw_landed_with_a_long_poll_interval(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    world = World(monkeypatch, [])

    world.run_main()

    assert world.consumer["topics"] == ["bridge.raw.landed"]
    assert world.consumer["conf_overrides"] == {"max.poll.interval.ms": 30 * 60 * 1000}


def test_what_main_opened_is_closed_and_the_timer_stopped_when_the_consumer_ends(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    world = World(monkeypatch, [])

    world.run_main()

    assert world.listener is not None and world.timer is not None
    assert world.timer[1].is_set()
    _, listener_loop = world.listener
    searched = [store for store in world.stores if store.opened_on is not listener_loop]
    assert [store.closed for store in searched] == [True]
    assert world.producer.closed and world.provider.closed


def test_a_thread_that_dies_takes_the_process_down(monkeypatch: pytest.MonkeyPatch) -> None:
    world = World(monkeypatch, [])
    exits: list[int] = []
    monkeypatch.setattr(runner.os, "_exit", exits.append)

    world.run_main()
    args = SimpleNamespace(thread=SimpleNamespace(name="task-created-listener"), exc_value=None)
    threading.excepthook(args)  # type: ignore[arg-type]

    assert exits == [1]


# --- #482: each call attributed to its item and stage, set by the runner -----------------

#: The item as the API will name it: from the source_ref Mind sends Inbox.
ITEM_REF = item_ref(TENANT, source_ref_for_inbox(None, ITEM))


def test_a_landed_items_calls_are_attributed_to_it_by_stage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_for_tests()
    world = World(monkeypatch, [_message()])

    world.run_main()

    # The LLM call, recorded by R2's extractor -- which knows nothing of items.
    [llm] = [r for r in get_recent_calls() if r.kind == CallKind.LLM]
    assert (llm.item, llm.stage, llm.task) == (ITEM_REF, "extraction", None)
    # The duplicate check runs on the pipeline's loop, and still sees it.
    assert world.duplicate_check_context == CallContext(item=ITEM_REF, stage="duplicate_check")
    # Nothing is left set once the message is handled.
    assert current_context() == CallContext()


def test_bridges_own_source_ref_names_the_item_when_bridge_sends_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _reset_for_tests()
    world = World(monkeypatch, [_message(source_ref="slack:C024BE91L/1729000000.000100")])

    world.run_main()

    [llm] = [r for r in get_recent_calls() if r.kind == CallKind.LLM]
    assert llm.item == item_ref(TENANT, "slack:C024BE91L/1729000000.000100")


def test_task_created_replies_are_attributed_to_their_task_and_item(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    world = World(monkeypatch, [])
    world.run_main()
    assert world.listener_consumer_factory is runner._AttributedTaskCreatedConsumer

    reply = TaskCreatedEvent(
        id=TASK_ID, tenant_id=TENANT, source_ref=source_ref_for_inbox(None, ITEM)
    )
    seen: list[CallContext] = []

    class Consumer:
        def __init__(self, **kwargs: Any) -> None:
            pass

        def run(self, message_type: type, handler: Any, *, idle_timeout: float | None) -> int:
            handler(reply, None)
            return 1

    monkeypatch.setattr(runner, "ProtobufConsumer", Consumer)
    consumer = runner._AttributedTaskCreatedConsumer()
    consumer.run(TaskCreatedEvent, lambda event, msg: seen.append(current_context()))

    assert seen == [CallContext(item=ITEM_REF, task=TASK_ID, stage="task_created")]


def test_the_embedding_model_is_warmed_at_start_up(monkeypatch: pytest.MonkeyPatch) -> None:
    # #482: its import is lazy, so without this the first item to reach the
    # duplicate check would wait for it (20-100 s measured) inside its handler.
    world = World(monkeypatch, [])

    world.run_main()

    assert world.embeddings_warmed
