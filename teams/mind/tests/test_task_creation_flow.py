"""The round trip of #353: a creation request out, task.created back, then the index.

No broker: the producer is a fake, the reply is built by hand with its
correlation_id in the Kafka header (where Inbox puts it on task.created), and
the vector store is R3's in-memory double. The acceptance criteria of the
issue are the tests below, one each.
"""

import logging
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.flow import TaskCreationFlow
from mind.task_creation.pending import PendingRequests
from mind.task_creation.reply import MatchedReply, handle_task_created_reply
from mind.task_creation.send import send_creation_request
from tests.test_index_consumer import FakeVectorStore

ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")
TASK_ID = "4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f"  # what Inbox hands out


@pytest.fixture(autouse=True)
def _no_embedding_model(monkeypatch: pytest.MonkeyPatch) -> None:
    """These tests are about the flow, not the embedding: loading the real
    model downloads it on a first run, and from here the download fails."""

    monkeypatch.setattr("mind.embeddings.index_consumer.embed", lambda _text: [0.1, 0.2, 0.3])


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, object, str | None]] = []

    def publish(self, topic: str, message: object, key: str | None = None) -> None:
        self.sent.append((topic, message, key))


class FailingOnceStore(FakeVectorStore):
    """The database is down for the first write and back for the second."""

    def __init__(self) -> None:
        super().__init__()
        self._failed = False

    async def upsert_if_absent(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> bool:
        if not self._failed:
            self._failed = True
            raise ConnectionError("database unavailable")
        return await super().upsert_if_absent(task_id, vector, content_hash=content_hash)


def _proposal() -> TaskCreationRequested:
    return TaskCreationRequested(
        tenant_id=UUID("11111111-1111-1111-1111-111111111111"),
        title="Send the Q3 revenue report",
        description="Finance needs it before Friday's close.",
        source_type=SourceType.SLACK,
        source_ref="C024BE91L:1728900000.000100",
        has_dependencies=False,
        confidence=0.85,
    )


async def _send(pending: PendingRequests, revision: int = 0) -> UUID:
    return await send_creation_request(
        FakeProducer(), pending, _proposal(), bridge_correlation_id=ITEM, revision=revision
    )


def _reply(correlation_id: object) -> tuple[TaskCreatedEvent, list[tuple[str, bytes]]]:
    """task.created as Inbox sends it: the id in the body, the correlation in a header."""

    event = TaskCreatedEvent(
        id=TASK_ID,
        title="Send the Q3 revenue report",
        description="Finance needs it before Friday's close.",
    )
    return event, [("correlation_id", str(correlation_id).encode())]


async def test_full_round_trip_publish_reply_index() -> None:
    pending, store = PendingRequests(), FakeVectorStore()

    correlation_id = await _send(pending, revision=2)
    assert store.rows == {}  # never indexed before Inbox confirms

    matched = await handle_task_created_reply(store, pending, *_reply(correlation_id))

    assert TASK_ID in store.rows
    assert await pending.get(correlation_id) is None
    assert matched is not None
    # The hook for #355 and for a later update flow: the real id, and what
    # Mind knew when it asked.
    assert (matched.task_id, matched.request.bridge_correlation_id, matched.request.revision) == (
        TASK_ID,
        ITEM,
        2,
    )


async def test_a_reply_matching_nothing_pending_is_ignored(
    caplog: pytest.LogCaptureFixture,
) -> None:
    pending, store = PendingRequests(), FakeVectorStore()

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        matched = await handle_task_created_reply(
            store, pending, *_reply(UUID("00000000-0000-4000-8000-000000000000"))
        )

    assert matched is None
    assert store.rows == {}
    assert "00000000-0000-4000-8000-000000000000" in caplog.text


@pytest.mark.parametrize("headers", [[], [("correlation_id", b"not-a-uuid")], None])
async def test_a_reply_without_a_readable_correlation_id_is_ignored(
    headers: list[tuple[str, bytes]] | None,
) -> None:
    """Tasks created by anyone else carry none; they are not ours to match."""

    pending, store = PendingRequests(), FakeVectorStore()
    await _send(pending)
    event, _ = _reply("unused")

    assert await handle_task_created_reply(store, pending, event, headers) is None
    assert store.rows == {}
    assert len(await pending.all()) == 1


async def test_a_retry_reuses_the_correlation_id_and_one_reply_settles_both() -> None:
    pending, store = PendingRequests(), FakeVectorStore()

    first, second = await _send(pending), await _send(pending)
    assert first == second

    assert await handle_task_created_reply(store, pending, *_reply(first)) is not None
    assert await pending.all() == []


async def test_a_failed_index_write_leaves_the_request_pending_for_redelivery() -> None:
    """The error must propagate, so Kafka redelivers; the redelivery must
    still find its request, or it would be dropped as unknown."""

    pending, store = PendingRequests(), FailingOnceStore()
    correlation_id = await _send(pending)

    with pytest.raises(ConnectionError):
        await handle_task_created_reply(store, pending, *_reply(correlation_id))
    assert await pending.get(correlation_id) is not None

    assert await handle_task_created_reply(store, pending, *_reply(correlation_id)) is not None
    assert TASK_ID in store.rows
    assert await pending.get(correlation_id) is None


# --- through TaskCreationFlow: one store for both sides ---------------------


async def test_the_flow_matches_a_reply_to_a_request_it_sent() -> None:
    """Behaviour, not wiring: were the sender and the listener given separate
    stores, the listener would not find the request and would drop the reply
    as unknown — so this is the test that fails exactly when that bug exists."""

    store = FakeVectorStore()
    flow = TaskCreationFlow(producer=FakeProducer(), index=store, pending=PendingRequests())

    correlation_id = await flow.send(
        _proposal(), bridge_correlation_id=ITEM, revision=1
    )
    matched = await flow.on_task_created(*_reply(correlation_id))

    assert matched is not None and matched.task_id == TASK_ID
    assert TASK_ID in store.rows


async def test_the_flow_reports_what_it_sent_and_never_heard_back_about(
    caplog: pytest.LogCaptureFixture,
) -> None:
    flow = TaskCreationFlow(producer=FakeProducer(), index=FakeVectorStore(), pending=PendingRequests())
    correlation_id = await flow.send(_proposal(), bridge_correlation_id=ITEM, revision=0)

    with caplog.at_level(logging.WARNING, logger="mind.task_creation"):
        overdue = await flow.check_overdue(now=datetime.now(UTC) + timedelta(minutes=11))

    assert overdue == [correlation_id]


async def test_a_task_no_one_asked_mind_for_is_not_logged_at_info(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Every task anyone creates arrives here; one line each would drown the
    lines that matter. Only an unmatched correlation_id is worth an INFO."""

    event, _ = _reply("unused")

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        await handle_task_created_reply(FakeVectorStore(), PendingRequests(), event, None)

    assert caplog.text == ""


# --- the hook for what follows a real task id (#355) ------------------------


async def test_on_matched_hears_of_each_matched_reply_once() -> None:
    heard: list[MatchedReply] = []

    async def on_matched(matched: MatchedReply) -> None:
        heard.append(matched)

    flow = TaskCreationFlow(
        producer=FakeProducer(), index=FakeVectorStore(), pending=PendingRequests(),
        on_matched=on_matched,
    )
    correlation_id = await flow.send(_proposal(), bridge_correlation_id=ITEM, revision=3)
    await flow.on_task_created(*_reply(correlation_id))

    assert [(m.task_id, m.request.revision) for m in heard] == [(TASK_ID, 3)]


async def test_on_matched_does_not_hear_of_a_reply_mind_did_not_ask_for() -> None:
    heard: list[MatchedReply] = []

    async def on_matched(matched: MatchedReply) -> None:
        heard.append(matched)

    flow = TaskCreationFlow(
        producer=FakeProducer(), index=FakeVectorStore(), pending=PendingRequests(),
        on_matched=on_matched,
    )
    await flow.on_task_created(*_reply(UUID("00000000-0000-4000-8000-000000000000")))

    assert heard == []


async def test_a_failed_on_matched_keeps_the_request_so_redelivery_tries_again() -> None:
    """Forgotten first, a redelivery would be 'unknown' and #355 would never hear
    of the task. So the request is forgotten only after the hook has run — and
    the hook must cope with hearing of the same task more than once."""

    calls = 0

    async def on_matched(matched: MatchedReply) -> None:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise ConnectionError("Chain unreachable")

    pending = PendingRequests()
    flow = TaskCreationFlow(
        producer=FakeProducer(), index=FakeVectorStore(), pending=pending, on_matched=on_matched
    )
    correlation_id = await flow.send(_proposal(), bridge_correlation_id=ITEM, revision=0)

    with pytest.raises(ConnectionError):
        await flow.on_task_created(*_reply(correlation_id))
    assert await pending.get(correlation_id) is not None

    assert await flow.on_task_created(*_reply(correlation_id)) is not None
    assert calls == 2
    assert await pending.get(correlation_id) is None


async def test_an_unreadable_correlation_header_is_a_warning_not_silence(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Present but not a UUID means the format changed somewhere. Treated as
    absent, every reply would be dropped at DEBUG and the only symptom would be
    overdue warnings ten minutes later."""

    event, _ = _reply("unused")

    with caplog.at_level(logging.WARNING, logger="mind.task_creation"):
        matched = await handle_task_created_reply(
            FakeVectorStore(), PendingRequests(), event, [("correlation_id", b'"not-a-uuid"')]
        )

    assert matched is None
    assert "not-a-uuid" in caplog.text
