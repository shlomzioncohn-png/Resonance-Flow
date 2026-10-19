"""Sending a creation request, and keeping track of it until Inbox replies (#353).

The order is the point: id, message, register, publish. Registering first means
a fast reply always finds its request; registering after would let a reply that
beats the bookkeeping be thrown away as unknown, silently. A failed publish
takes its registration back, so nothing waits for a message never sent.
"""

import asyncio
import threading
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.task_creation.correlation import correlation_id_for
from mind.task_creation.overdue import report_overdue
from mind.task_creation.pending import PendingRequest, PendingRequests
from mind.task_creation.send import TOPIC, send_creation_request

ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")


class FakeProducer:
    """Records what was published, and what was already pending at that moment."""

    def __init__(self, pending: PendingRequests, fail: bool = False) -> None:
        self.sent: list[tuple[str, object, str | None]] = []
        self.pending_at_publish: list[bool] = []
        self._pending = pending
        self._fail = fail

    def publish(self, topic: str, message: object, key: str | None = None) -> None:
        # Synchronous, like the real producer, so it reads the in-memory ids directly.
        self.pending_at_publish.append(correlation_id_for(ITEM) in self._pending.ids())
        if self._fail:
            raise RuntimeError("broker unavailable")
        self.sent.append((topic, message, key))


def _request(source_ref: str = "C024BE91L:1728900000.000100") -> TaskCreationRequested:
    return TaskCreationRequested(
        tenant_id=UUID("11111111-1111-1111-1111-111111111111"),
        title="Send the Q3 revenue report",
        source_type=SourceType.SLACK,
        source_ref=source_ref,
        has_dependencies=False,
        confidence=0.85,
    )


async def _send(producer: FakeProducer, pending: PendingRequests, **overrides: object) -> UUID:
    request = overrides.pop("request", _request())
    return await send_creation_request(
        producer, pending, request,  # type: ignore[arg-type]
        bridge_correlation_id=ITEM, revision=0, **overrides,  # type: ignore[arg-type]
    )


async def test_it_is_published_on_inboxs_topic_keyed_by_its_correlation_id() -> None:
    pending = PendingRequests()
    producer = FakeProducer(pending)

    correlation_id = await _send(producer, pending)

    [(topic, message, key)] = producer.sent
    assert topic == TOPIC == "task.creation.requested"
    assert key == str(correlation_id)
    assert message.correlation_id == str(correlation_id)  # type: ignore[attr-defined]


async def test_it_is_registered_before_it_is_published() -> None:
    """Otherwise a fast reply can arrive first and be dropped as unknown."""

    pending = PendingRequests()
    producer = FakeProducer(pending)

    await _send(producer, pending)

    assert producer.pending_at_publish == [True]


async def test_what_is_registered_keeps_the_item_its_revision_and_task_number() -> None:
    """The revision is what a later update feature will compare against."""

    pending = PendingRequests()
    correlation_id = await send_creation_request(
        FakeProducer(pending), pending, _request(),
        bridge_correlation_id=ITEM, revision=3, task_index=0,
    )

    entry = await pending.get(correlation_id)
    assert entry is not None
    assert (entry.bridge_correlation_id, entry.revision, entry.task_index) == (ITEM, 3, 0)


async def test_a_failed_publish_takes_its_registration_back() -> None:
    pending = PendingRequests()

    with pytest.raises(RuntimeError):
        await _send(FakeProducer(pending, fail=True), pending)

    assert await pending.get(correlation_id_for(ITEM)) is None


async def test_a_retry_carries_the_same_id_and_is_registered_once() -> None:
    pending = PendingRequests()

    first = await _send(FakeProducer(pending), pending)
    second = await _send(FakeProducer(pending), pending)

    assert first == second
    assert len(await pending.all()) == 1


async def test_a_failed_retry_keeps_the_registration_it_found() -> None:
    """The first attempt may have arrived, and its reply may be on its way."""

    pending = PendingRequests()
    await _send(FakeProducer(pending), pending)

    with pytest.raises(RuntimeError):
        await _send(FakeProducer(pending, fail=True), pending)

    assert await pending.get(correlation_id_for(ITEM)) is not None


async def test_a_request_inbox_would_reject_is_neither_registered_nor_sent() -> None:
    pending = PendingRequests()
    producer = FakeProducer(pending)

    with pytest.raises(ValueError, match="source_ref"):
        await _send(producer, pending, request=_request(source_ref=" "))

    assert await pending.all() == []
    assert producer.sent == []


async def test_waiting_for_the_broker_does_not_block_the_event_loop() -> None:
    """publish() blocks until Kafka acks; run inline, it would freeze the
    caller's loop and everything else on it for the whole round trip.

    The producer here waits for a signal that only another coroutine on the
    same loop can give. If publishing blocked the loop, that coroutine could
    never run and the wait would time out.
    """

    released = threading.Event()

    class BlockingProducer:
        def publish(self, topic: str, message: object, key: str | None = None) -> None:
            assert released.wait(timeout=2), "the event loop was blocked while publishing"

    async def something_else_on_the_loop() -> None:
        released.set()

    pending = PendingRequests()
    await asyncio.gather(
        send_creation_request(
            BlockingProducer(), pending, _request(), bridge_correlation_id=ITEM, revision=0
        ),
        something_else_on_the_loop(),
    )


async def test_a_retry_that_went_out_restarts_the_clock_and_keeps_its_revision() -> None:
    """The entry describes the latest request Inbox received, not the first."""

    pending = PendingRequests()
    first = await send_creation_request(
        FakeProducer(pending), pending, _request(), bridge_correlation_id=ITEM, revision=0
    )
    sent_first = (await pending.get(first)).sent_at  # type: ignore[union-attr]

    await send_creation_request(
        FakeProducer(pending), pending, _request(), bridge_correlation_id=ITEM, revision=1
    )

    entry = await pending.get(first)
    assert entry is not None
    assert entry.revision == 1
    assert entry.sent_at > sent_first


async def test_a_resent_request_can_be_reported_overdue_again() -> None:
    pending = PendingRequests()
    correlation_id = await _send(FakeProducer(pending), pending)
    await report_overdue(pending, now=datetime.now(UTC) + timedelta(minutes=11))

    await _send(FakeProducer(pending), pending)

    assert await report_overdue(pending, now=datetime.now(UTC) + timedelta(minutes=11)) == [
        correlation_id
    ]


async def test_a_failed_retry_leaves_the_registration_exactly_as_it_was() -> None:
    """Nothing went out, so nothing about the earlier attempt has changed."""

    pending = PendingRequests()
    correlation_id = await _send(FakeProducer(pending), pending)
    before = await pending.get(correlation_id)

    with pytest.raises(RuntimeError):
        await send_creation_request(
            FakeProducer(pending, fail=True), pending, _request(),
            bridge_correlation_id=ITEM, revision=5,
        )

    assert await pending.get(correlation_id) == before


async def test_renewing_does_not_bring_back_a_request_already_answered() -> None:
    """The race: the retry goes out, Inbox answers at once, the listener
    matches the reply and forgets the request — and only then does the sender
    renew it. Renewing must not put it back: its reply has been and gone, so it
    would wait for ever and be reported overdue for a task that exists."""

    pending = PendingRequests()
    correlation_id = correlation_id_for(ITEM)
    entry = PendingRequest(bridge_correlation_id=ITEM, revision=1, task_index=0)
    await pending.add(correlation_id, entry)

    await pending.discard(correlation_id)  # the listener matched the reply
    await pending.renew(correlation_id, entry)  # the sender, a moment late

    assert await pending.get(correlation_id) is None
