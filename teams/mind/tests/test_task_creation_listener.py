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


# --- recovering from a failure, for as long as the outage allows ------------


class ScriptedConsumer:
    """One connection's life: handle some messages, stay up a while, then fail or stop."""

    def __init__(
        self, handled_before: int, then: Exception | None, *, up_for: float = 0.0
    ) -> None:
        self._handled_before, self._then, self._up_for = handled_before, then, up_for
        self.time: FakeTime | None = None

    def run(self, message_type: type, handler: Callable[[object, object], None], **_: object) -> int:
        for _n in range(self._handled_before):
            handler(_event(), FakeMessage(None))
        if self.time is not None:
            self.time.now += self._up_for  # connected and idle, no messages
        if self._then is not None:
            raise self._then
        return self._handled_before


class FakeTime:
    """A clock that only moves when the listener waits: minutes pass in no time."""

    def __init__(self) -> None:
        self.now = 0.0
        self.waits: list[float] = []

    def sleep(self, seconds: float) -> None:
        self.waits.append(seconds)
        self.now += seconds


def _listen(
    consumers: list[ScriptedConsumer],
    time_: FakeTime,
    loop: asyncio.AbstractEventLoop,
    **limits: float,
) -> None:
    for consumer in consumers:
        consumer.time = time_
    connections = iter(consumers)
    run_task_created_listener(
        _flow(FakeVectorStore()),
        loop,
        consumer_factory=lambda: next(connections),
        sleep=time_.sleep,
        clock=lambda: time_.now,
        **limits,  # type: ignore[arg-type]
    )


def _outage(failures: int) -> list[ScriptedConsumer]:
    return [ScriptedConsumer(0, ConnectionError(f"failure {n}")) for n in range(failures)]


def test_after_a_failure_the_listener_reconnects_and_goes_on(
    loop: asyncio.AbstractEventLoop, caplog: pytest.LogCaptureFixture
) -> None:
    """Kafka redelivers the message that failed to the new connection; the
    in-memory store, unlike after a restart, is still there to match it."""

    time_ = FakeTime()
    with caplog.at_level(logging.ERROR, logger="mind.task_creation"):
        _listen([*_outage(1), ScriptedConsumer(1, None)], time_, loop)

    assert time_.waits == [1.0]
    assert "failure 0" in caplog.text


def test_a_database_outage_of_minutes_is_ridden_out(loop: asyncio.AbstractEventLoop) -> None:
    """The case the reconnect exists for. No message succeeds during an outage,
    so counting failures gave up after about 15 seconds and the restart wiped
    every pending request. Ten minutes of failing must be survived."""

    time_ = FakeTime()
    # 1+2+4+8+16+32 = 63 s, then 60 s each: 15 failures is about ten minutes.
    _listen([*_outage(15), ScriptedConsumer(1, None)], time_, loop)

    assert sum(time_.waits) >= 9 * 60


def test_the_wait_doubles_up_to_its_ceiling_and_stays_there(
    loop: asyncio.AbstractEventLoop,
) -> None:
    time_ = FakeTime()
    _listen([*_outage(9), ScriptedConsumer(1, None)], time_, loop)

    assert time_.waits == [1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0, 60.0, 60.0]


def test_an_outage_longer_than_allowed_is_given_up_on(loop: asyncio.AbstractEventLoop) -> None:
    """Then the error leaves the thread, for the process to exit and restart —
    a crash loop anyone can see, rather than a listener failing for ever."""

    time_ = FakeTime()

    with pytest.raises(ConnectionError):
        _listen(_outage(50), time_, loop, max_outage=100.0)

    # Gave up at the first failure past 100 s: after waits 1..32 and one 60.
    assert time_.waits == [1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0]


def test_a_handled_message_ends_the_outage(loop: asyncio.AbstractEventLoop) -> None:
    """Blips hours apart are separate, not one long outage: each starts the
    wait and the outage clock from the beginning."""

    time_ = FakeTime()
    blips = [ScriptedConsumer(1, ConnectionError("blip")) for _ in range(6)]

    _listen([*blips, ScriptedConsumer(1, None)], time_, loop, max_outage=0.5)

    assert time_.waits == [1.0] * 6


def test_a_failure_after_a_long_quiet_connection_is_a_new_outage(
    loop: asyncio.AbstractEventLoop,
) -> None:
    """The review's case: an outage at 09:00, a reconnect that then stays up
    for 20 quiet minutes — no message, so nothing reset the clock — and a new
    failure at 09:20. It is a new outage, not a 20-minute one."""

    time_ = FakeTime()
    _listen(
        [
            ScriptedConsumer(0, ConnectionError("09:00")),
            ScriptedConsumer(0, ConnectionError("09:20"), up_for=20 * 60),
            ScriptedConsumer(1, None),
        ],
        time_,
        loop,
        max_outage=15 * 60,
    )

    assert time_.waits == [1.0, 1.0]  # the second wait starts over too


def test_connections_that_fail_at_once_are_still_one_outage(
    loop: asyncio.AbstractEventLoop,
) -> None:
    """The other side: a database that is down lets Kafka connect and fails
    the first message straight away. Those reconnects are not recoveries."""

    time_ = FakeTime()

    with pytest.raises(ConnectionError):
        _listen(_outage(50), time_, loop, max_outage=100.0)

    assert time_.waits == [1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0]


class SlowFailingMessage:
    """An envelope whose handling takes a while and then fails — as a slow
    on_matched or a hanging index write would."""

    def __init__(self, time_: FakeTime, takes: float) -> None:
        self._time, self._takes = time_, takes

    def headers(self) -> list[tuple[str, bytes]] | None:
        self._time.now += self._takes
        raise ConnectionError("slow failure")


class SlowFailingConsumer(ScriptedConsumer):
    def __init__(self, takes: float) -> None:
        super().__init__(0, None)
        self._takes = takes

    def run(self, message_type: type, handler: Callable[[object, object], None], **_: object) -> int:
        assert self.time is not None
        handler(_event(), SlowFailingMessage(self.time, self._takes))
        return 0


def test_time_spent_handling_a_message_is_not_quiet_uptime(
    loop: asyncio.AbstractEventLoop,
) -> None:
    """A message that takes 90 s to fail each time arrives the moment the
    connection opens: zero quiet time, so the same outage, and the listener
    gives up when the outage runs out instead of retrying it for ever."""

    time_ = FakeTime()
    slow = [SlowFailingConsumer(takes=90.0) for _ in range(50)]

    with pytest.raises(ConnectionError, match="slow failure"):
        _listen(slow, time_, loop, max_outage=10 * 60)
