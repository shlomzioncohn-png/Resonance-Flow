"""Sending one creation request to Inbox: id, message, register, publish — in that order.

Why the order, and what a failed or repeated publish does: README.md,
"Order: register, then publish; index, then forget".
"""

from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING
from uuid import UUID

from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.task_creation.correlation import correlation_id_for
from mind.task_creation.event import to_creation_requested_event
from mind.task_creation.pending import PendingRequest, PendingStore

if TYPE_CHECKING:
    from mind.messaging.producer import ProtobufProducer, Publisher

TOPIC = "task.creation.requested"


def _the_real_producer_fits(producer: ProtobufProducer) -> Publisher:
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
    dependency_mentions: tuple[str, ...] = (),
) -> UUID:
    """Publish `request` to Inbox and register it; return its correlation_id."""

    correlation_id = correlation_id_for(bridge_correlation_id, task_index)
    event = to_creation_requested_event(request, correlation_id)

    entry = PendingRequest(
        bridge_correlation_id=bridge_correlation_id,
        revision=revision,
        task_index=task_index,
        tenant_id=request.tenant_id,
        dependency_mentions=tuple(dependency_mentions),
    )
    registered_now = await pending.add(correlation_id, entry)
    try:
        # Off the loop: publish() blocks until Kafka acks. Keyed so a retry
        # lands on the same partition.
        await asyncio.to_thread(producer.publish, TOPIC, event, key=str(correlation_id))
    except Exception:
        if registered_now:
            await pending.discard(correlation_id)
        raise

    if not registered_now:
        # A retry that went out (only now: a failed one changed nothing).
        await pending.renew(correlation_id, entry)

    return correlation_id
