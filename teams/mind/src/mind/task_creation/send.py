"""Sending one creation request to Inbox (#353).

In this order, and the order is the point:

1. the correlation_id, derived from Bridge's item so a retry repeats it;
2. the message — built before anything is registered, so a request Inbox would
   reject (a blank source_ref) fails here and leaves nothing behind;
3. the registration — before publishing, so a fast reply always finds it;
4. the publish. If it fails, a registration this call made is taken back. One
   it found already is kept: that earlier attempt may have arrived. If it
   succeeds, a registration it found is renewed, to describe this attempt.
"""

import asyncio
from typing import TYPE_CHECKING, Protocol
from uuid import UUID

from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.task_creation.correlation import correlation_id_for
from mind.task_creation.event import to_creation_requested_event
from mind.task_creation.pending import PendingRequest, PendingStore

if TYPE_CHECKING:
    from mind.messaging.producer import ProtobufProducer

TOPIC = "task.creation.requested"


class Publisher(Protocol):
    """What `ProtobufProducer` offers: publish one message and wait for the ack."""

    def publish(self, topic: str, message: object, key: str | None = None) -> None: ...


def _the_real_producer_fits(producer: "ProtobufProducer") -> Publisher:
    """Checked by mypy, never called: tests use a fake, production the real one."""

    return producer


async def send_creation_request(
    producer: Publisher,
    pending: PendingStore,
    request: TaskCreationRequested,
    *,
    bridge_correlation_id: UUID,
    revision: int,
    task_index: int = 0,
) -> UUID:
    """Publish `request` to Inbox and register it; return its correlation_id."""

    correlation_id = correlation_id_for(bridge_correlation_id, task_index)
    event = to_creation_requested_event(request, correlation_id)

    entry = PendingRequest(
        bridge_correlation_id=bridge_correlation_id, revision=revision, task_index=task_index
    )
    registered_now = await pending.add(correlation_id, entry)
    try:
        # Keyed by the correlation_id, so a retry lands on the same partition.
        # On a thread: publish() blocks until Kafka acks, and inline it would
        # freeze the caller's event loop for the whole round trip. Still
        # awaited, so the order above holds and a failure still lands below.
        await asyncio.to_thread(producer.publish, TOPIC, event, key=str(correlation_id))
    except Exception:
        if registered_now:
            await pending.discard(correlation_id)
        raise

    if not registered_now:
        # A retry that went out: the entry now describes this request, so its
        # overdue clock restarts and it may be reported again. Only here, after
        # a successful publish — a failed retry changed nothing at Inbox.
        await pending.renew(correlation_id, entry)

    return correlation_id
