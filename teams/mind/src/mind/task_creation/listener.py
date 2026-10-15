"""The two loops that run beside the #349 pipeline: task.created, and the overdue timer.

The listener: `ProtobufConsumer.run` calls a plain function for every message,
and the flow is async, so the handler drives the listener's own event loop — as
R3's index runner does. Nothing is caught: an exception propagates, the consumer
does not commit the offset, and Kafka redelivers the message.

The timer: requests go overdue when Inbox is down, which is exactly when no
task.created arrives — so the check cannot ride on the listener. It runs on its
own thread and event loop (two threads cannot drive one loop), and here it is
the other way round: a failed check is logged and the timer goes on, because a
timer that died on one error would stop reporting in silence.
"""

import asyncio
import logging
import threading
import time
from asyncio import AbstractEventLoop
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Protocol, cast
from uuid import UUID

from mind.messaging.consumer import ProtobufConsumer
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.flow import TaskCreationFlow
from mind.task_creation.reply import Headers

TOPIC = "task.created"

logger = logging.getLogger("mind.task_creation")

#: Our own group. R3's index consumer reads task.created too, and within one
#: group each message reaches a single member: both need every message.
GROUP_ID = "mind-task-creation"


class _KafkaMessage(Protocol):
    """The part of confluent-kafka's Message used here: the envelope's headers."""

    def headers(self) -> Headers: ...


def task_created_handler(
    flow: TaskCreationFlow, loop: AbstractEventLoop
) -> Callable[[object, object], None]:
    """The function `ProtobufConsumer.run` calls with (event, message)."""

    def _handle(event: object, message: object) -> None:
        # run() has already deserialised `event` into a TaskCreatedEvent. The
        # correlation_id is on the envelope, so the message goes in for its
        # headers alone.
        loop.run_until_complete(
            flow.on_task_created(cast(TaskCreatedEvent, event), cast(_KafkaMessage, message).headers())
        )

    return _handle


#: How long the listener keeps failing before it gives up. Measured in time,
#: not in failures: no message succeeds during an outage, so a count runs out
#: in seconds — and giving up restarts the process, which wipes the in-memory
#: pending store the reconnect exists to keep. A handled message ends the outage.
MAX_OUTAGE = 15 * 60.0

#: The longest wait between reconnects. Waits double from one second and stay
#: here once they reach it, so a long outage is retried once a minute.
_MAX_RECONNECT_WAIT = 60.0


class _Consumer(Protocol):
    """The part of `ProtobufConsumer` the listener uses."""

    def run(
        self,
        message_type: type,
        handler: Callable[[object, object], None],
        *,
        idle_timeout: float | None = ...,
    ) -> int: ...


def run_task_created_listener(
    flow: TaskCreationFlow,
    loop: AbstractEventLoop,
    *,
    max_outage: float = MAX_OUTAGE,
    consumer_factory: Callable[[], _Consumer] | None = None,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> None:
    """Consume task.created until the process stops. Blocks.

    A failure closes the consumer — `ProtobufConsumer.run` lets the error out —
    so on its own one database blip would end this thread in silence. Instead it
    logs, waits, and reconnects: the message that failed was never committed,
    so Kafka hands it to the new connection, and the in-memory pending store,
    which a restart would wipe, is still here to match it.

    Once failures have gone on for longer than `max_outage` seconds it gives up
    and lets the error out. Then the process should exit and be restarted, as
    R3's runner arranges with a `threading.excepthook` — with a persistent store
    that loses nothing. A message that always fails ends there, visibly.

    The flow's vector store must already be open on `loop`: an async pool
    belongs to the loop that opened it, and this one is the listener's.
    """

    make_consumer = consumer_factory or (lambda: ProtobufConsumer(group_id=GROUP_ID, topics=[TOPIC]))
    handle = task_created_handler(flow, loop)
    # When the current run of failures began, and how many it has had; both
    # reset by any handled message.
    outage_began: float | None = None
    failures = 0

    def _handle_and_reset(event: object, message: object) -> None:
        nonlocal outage_began, failures
        handle(event, message)
        outage_began, failures = None, 0

    while True:
        try:
            make_consumer().run(TaskCreatedEvent, _handle_and_reset, idle_timeout=None)
            return
        except Exception:
            now = clock()
            if outage_began is None:
                outage_began = now
            if now - outage_began >= max_outage:
                logger.critical(
                    "task_creation: task.created listener failing for %.0fs; giving up",
                    now - outage_began,
                )
                raise
            wait = min(2.0**failures, _MAX_RECONNECT_WAIT)
            failures += 1
            logger.exception(
                "task_creation: task.created listener failed (%d in a row); reconnecting in %ss",
                failures,
                wait,
            )
            sleep(wait)


class _OverdueChecker(Protocol):
    """The part of TaskCreationFlow the timer uses."""

    async def check_overdue(self, now: datetime) -> list[UUID]: ...


def run_overdue_timer(
    flow: _OverdueChecker,
    stop: threading.Event,
    *,
    interval: float = 60.0,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> None:
    """Check for overdue requests every `interval` seconds until `stop` is set. Blocks.

    Waits on `stop` rather than sleeping, so a shutdown is immediate instead of
    waiting out the interval.
    """

    loop = asyncio.new_event_loop()
    try:
        while not stop.is_set():
            try:
                loop.run_until_complete(flow.check_overdue(clock()))
            except Exception:
                logger.exception("task_creation: overdue check failed; trying again in %ss", interval)
            if stop.wait(interval):
                break
    finally:
        loop.close()
