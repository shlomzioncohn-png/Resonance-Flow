"""The task.created listener: from a Kafka message to TaskCreationFlow (#353).

Kafka itself is not under test, as R3's runner is not. What is: that the
handler `ProtobufConsumer.run` calls hands the event *and the message headers*
on (the correlation_id is on the envelope, not in the letter), and that a
failure propagates — so the offset is not committed and Kafka redelivers.
"""

import asyncio
import logging
import threading
import time
from collections.abc import Callable, Iterator
from datetime import UTC, datetime
from uuid import UUID

import pytest

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.embeddings.index_consumer_runner import _GROUP_ID as R3_GROUP_ID
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.flow import TaskCreationFlow
from mind.task_creation.listener import (
    GROUP_ID,
    run_overdue_timer,
    run_task_created_listener,
    task_created_handler,
)
from mind.task_creation.pending import PendingRequests
from tests.test_index_consumer import FakeVectorStore

ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")
TASK_ID = "4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f"


@pytest.fixture(autouse=True)
def _no_embedding_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("mind.embeddings.index_consumer.embed", lambda _text: [0.1, 0.2, 0.3])


@pytest.fixture
def loop() -> Iterator[asyncio.AbstractEventLoop]:
    """The listener's own loop, as in R3's runner: the consumer calls a plain function."""

    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


class FakeProducer:
    def publish(self, topic: str, message: object, key: str | None = None) -> None:
        pass


class FakeMessage:
    """The envelope: confluent-kafka exposes headers through a method."""

    def __init__(self, headers: list[tuple[str, bytes]] | None) -> None:
        self._headers = headers

    def headers(self) -> list[tuple[str, bytes]] | None:
        return self._headers


class BrokenStore(FakeVectorStore):
    async def upsert_if_absent(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> bool:
        raise ConnectionError("database unavailable")


def _flow(store: FakeVectorStore) -> TaskCreationFlow:
    return TaskCreationFlow(producer=FakeProducer(), index=store, pending=PendingRequests())


def _sent(flow: TaskCreationFlow, loop: asyncio.AbstractEventLoop) -> UUID:
    proposal = TaskCreationRequested(
        tenant_id=UUID("11111111-1111-1111-1111-111111111111"),
        title="Send the Q3 revenue report",
        description="Before Friday.",
        source_type=SourceType.SLACK,
        source_ref="C024BE91L:1728900000.000100",
        has_dependencies=False,
        confidence=0.85,
    )
    return loop.run_until_complete(flow.send(proposal, bridge_correlation_id=ITEM, revision=0))


def _event() -> TaskCreatedEvent:
    return TaskCreatedEvent(id=TASK_ID, title="Send the Q3 revenue report", description="Before Friday.")


def test_the_handler_passes_the_event_and_the_envelopes_headers(
    loop: asyncio.AbstractEventLoop,
) -> None:
    store = FakeVectorStore()
    flow = _flow(store)
    correlation_id = _sent(flow, loop)

    task_created_handler(flow, loop)(_event(), FakeMessage([("correlation_id", str(correlation_id).encode())]))

    assert TASK_ID in store.rows


def test_a_message_with_no_headers_is_ignored(loop: asyncio.AbstractEventLoop) -> None:
    """Tasks created by anyone else; confluent-kafka returns None, not []."""

    store = FakeVectorStore()
    flow = _flow(store)
    _sent(flow, loop)

    task_created_handler(flow, loop)(_event(), FakeMessage(None))

    assert store.rows == {}


def test_a_failure_propagates_so_the_offset_is_not_committed(
    loop: asyncio.AbstractEventLoop,
) -> None:
    flow = _flow(BrokenStore())
    correlation_id = _sent(flow, loop)

    with pytest.raises(ConnectionError):
        task_created_handler(flow, loop)(
            _event(), FakeMessage([("correlation_id", str(correlation_id).encode())])
        )


def test_its_consumer_group_is_not_r3s() -> None:
    """In one group each message reaches one member: both must see every task.created."""

    assert GROUP_ID != R3_GROUP_ID


# --- the overdue timer ---------------------------------------------------------


class RecordingFlow:
    """Stands in for TaskCreationFlow: records each check, can fail one."""

    def __init__(self, stop: threading.Event, stop_after: int, fail_first: bool = False) -> None:
        self.checked_at: list[datetime] = []
        self._stop, self._stop_after, self._fail_first = stop, stop_after, fail_first

    async def check_overdue(self, now: datetime) -> list[UUID]:
        self.checked_at.append(now)
        if len(self.checked_at) >= self._stop_after:
            self._stop.set()
        if self._fail_first and len(self.checked_at) == 1:
            raise ConnectionError("store unavailable")
        return []


NOW = datetime(2026, 10, 15, 12, 0, tzinfo=UTC)


def test_the_timer_checks_with_the_clocks_time_until_stopped() -> None:
    stop = threading.Event()
    flow = RecordingFlow(stop, stop_after=3)

    run_overdue_timer(flow, stop, interval=0.01, clock=lambda: NOW)  # type: ignore[arg-type]

    assert flow.checked_at == [NOW, NOW, NOW]


def test_stopping_does_not_wait_out_the_interval() -> None:
    """Shutdown must not hang for up to a minute."""

    stop = threading.Event()
    stop.set()
    started = time.monotonic()

    run_overdue_timer(RecordingFlow(stop, stop_after=1), stop, interval=60, clock=lambda: NOW)  # type: ignore[arg-type]

    assert time.monotonic() - started < 1


def test_a_failed_check_is_logged_and_the_timer_goes_on(caplog: pytest.LogCaptureFixture) -> None:
    """It only reports: a timer that died on one error would stop reporting in silence."""

    stop = threading.Event()
    flow = RecordingFlow(stop, stop_after=2, fail_first=True)

    with caplog.at_level(logging.ERROR, logger="mind.task_creation"):
        run_overdue_timer(flow, stop, interval=0.01, clock=lambda: NOW)  # type: ignore[arg-type]

    assert len(flow.checked_at) == 2
    assert "store unavailable" in caplog.text


# --- recovering from a failure, up to a limit --------------------------------


class ScriptedConsumer:
    """One connection's life: handle some messages, then fail or stop."""

    def __init__(self, handled_before: int, then: Exception | None) -> None:
        self._handled_before, self._then = handled_before, then

    def run(self, message_type: type, handler: Callable[[object, object], None], **_: object) -> int:
        for _n in range(self._handled_before):
            handler(_event(), FakeMessage(None))
        if self._then is not None:
            raise self._then
        return self._handled_before


def _listen(consumers: list[ScriptedConsumer], waits: list[float], loop: asyncio.AbstractEventLoop) -> None:
    connections = iter(consumers)
    run_task_created_listener(
        _flow(FakeVectorStore()),
        loop,
        consumer_factory=lambda: next(connections),
        sleep=waits.append,
    )


def test_after_a_failure_the_listener_reconnects_and_goes_on(
    loop: asyncio.AbstractEventLoop, caplog: pytest.LogCaptureFixture
) -> None:
    """Kafka redelivers the message that failed to the new connection; the
    in-memory store, unlike after a restart, is still there to match it."""

    waits: list[float] = []
    with caplog.at_level(logging.ERROR, logger="mind.task_creation"):
        _listen([ScriptedConsumer(0, ConnectionError("db blip")), ScriptedConsumer(1, None)], waits, loop)

    assert waits == [1.0]
    assert "db blip" in caplog.text


def test_five_failures_in_a_row_are_given_up_on(loop: asyncio.AbstractEventLoop) -> None:
    """Then the error leaves the thread, for the process to exit and restart."""

    waits: list[float] = []
    failing = [ScriptedConsumer(0, ConnectionError(f"failure {n}")) for n in range(5)]

    with pytest.raises(ConnectionError, match="failure 4"):
        _listen(failing, waits, loop)

    assert waits == [1.0, 2.0, 4.0, 8.0]


def test_a_handled_message_starts_the_count_again(loop: asyncio.AbstractEventLoop) -> None:
    """Six failures, each after a success: six separate blips, not one outage."""

    waits: list[float] = []
    blips = [ScriptedConsumer(1, ConnectionError("blip")) for _ in range(6)]

    _listen([*blips, ScriptedConsumer(1, None)], waits, loop)

    assert waits == [1.0] * 6
