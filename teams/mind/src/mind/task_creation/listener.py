"""The two loops beside the #349 pipeline: the task.created listener and the overdue timer.

Why each one fails the way it does, and how to start them: README.md, "Running it".
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

#: Not R3's: within one group each message reaches one member, and both need every message.
GROUP_ID = "mind-task-creation"


class _KafkaMessage(Protocol):
    """The part of confluent-kafka's Message used here: the envelope's headers."""

    def headers(self) -> Headers: ...


def task_created_handler(
    flow: TaskCreationFlow, loop: AbstractEventLoop
) -> Callable[[object, object], None]:
    """The function `ProtobufConsumer.run` calls with (event, message)."""

    def _handle(event: object, message: object) -> None:
        # The correlation_id is on the envelope: the message goes in for its headers.
        loop.run_until_complete(
            flow.on_task_created(cast(TaskCreatedEvent, event), cast(_KafkaMessage, message).headers())
        )

    return _handle


#: How long the listener keeps failing before it gives up — time, not a count.
MAX_OUTAGE = 15 * 60.0

#: Waits double from one second up to this, and stay here.
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

    Reconnects after a failure; after `max_outage` seconds of failing, lets the
    error out for the process to exit and restart. The flow's vector store must
    already be open on `loop`.
    """

    make_consumer = consumer_factory or (lambda: ProtobufConsumer(group_id=GROUP_ID, topics=[TOPIC]))
    handle = task_created_handler(flow, loop)
    outage_began: float | None = None
    failures = 0
    handling_since: float | None = None  # when the message being handled arrived

    def _handle_and_reset(event: object, message: object) -> None:
        nonlocal outage_began, failures, handling_since
        handling_since = clock()
        handle(event, message)
        outage_began, failures, handling_since = None, 0, None

    while True:
        connected_at = clock()
        handling_since = None
        try:
            make_consumer().run(TaskCreatedEvent, _handle_and_reset, idle_timeout=None)
            return
        except Exception:
            now = clock()
            # Quiet for longer than the longest wait was a recovery, so a new
            # outage. Time spent handling the failing message is not quiet.
            quiet = (handling_since if handling_since is not None else now) - connected_at
            if quiet > _MAX_RECONNECT_WAIT:
                outage_began, failures = None, 0
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

    A failed check is logged and the timer goes on. Waits on `stop`, so a
    shutdown does not wait out the interval.
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
