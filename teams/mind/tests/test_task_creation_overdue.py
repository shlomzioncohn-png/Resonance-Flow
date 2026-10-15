"""A creation request Inbox never answers (#353).

Inbox down, or the message lost: the request would otherwise wait in silence
for ever. Past `max_wait` it is reported once, as a warning, and kept — a late
reply still matches. Every matched reply logs how long it took, so the limit
can later be set from real latencies (twice the usual) instead of a guess.
"""

import logging
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.overdue import DEFAULT_MAX_WAIT, report_overdue
from mind.task_creation.pending import PendingRequest, PendingRequests
from mind.task_creation.reply import handle_task_created_reply
from tests.test_index_consumer import FakeVectorStore

ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")
REQUEST_ID = UUID("b5603af4-09b6-569a-b3ca-f6d18a0191a1")
SENT = datetime(2026, 10, 14, 12, 0, tzinfo=UTC)


@pytest.fixture(autouse=True)
def _no_embedding_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("mind.embeddings.index_consumer.embed", lambda _text: [0.1, 0.2, 0.3])


async def _pending() -> PendingRequests:
    pending = PendingRequests()
    await pending.add(REQUEST_ID, PendingRequest(bridge_correlation_id=ITEM, revision=0, task_index=0, sent_at=SENT))
    return pending


def test_the_default_wait_is_ten_minutes() -> None:
    assert DEFAULT_MAX_WAIT == timedelta(minutes=10)


async def test_a_request_within_the_wait_is_not_reported() -> None:
    assert await report_overdue(await _pending(), now=SENT + timedelta(minutes=9)) == []


async def test_a_request_past_the_wait_is_reported_as_a_warning(
    caplog: pytest.LogCaptureFixture,
) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.task_creation"):
        overdue = await report_overdue(await _pending(), now=SENT + timedelta(minutes=11))

    assert overdue == [REQUEST_ID]
    assert str(REQUEST_ID) in caplog.text
    assert str(ITEM) in caplog.text


async def test_it_is_reported_once_not_on_every_check(caplog: pytest.LogCaptureFixture) -> None:
    pending = await _pending()
    await report_overdue(pending, now=SENT + timedelta(minutes=11))
    caplog.clear()  # the first report is expected; only the second must be silent

    with caplog.at_level(logging.WARNING, logger="mind.task_creation"):
        again = await report_overdue(pending, now=SENT + timedelta(minutes=30))

    assert again == []
    assert caplog.text == ""


async def test_a_reported_request_stays_pending_and_a_late_reply_still_matches() -> None:
    pending = await _pending()
    await report_overdue(pending, now=SENT + timedelta(minutes=11))

    reply = TaskCreatedEvent(id="4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f", title="t", description="d")
    headers = [("correlation_id", str(REQUEST_ID).encode())]

    assert await handle_task_created_reply(FakeVectorStore(), pending, reply, headers) is not None


async def test_a_matched_reply_logs_how_long_it_took(caplog: pytest.LogCaptureFixture) -> None:
    """The data for setting the limit from real latencies later."""

    reply = TaskCreatedEvent(id="4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f", title="t", description="d")
    headers = [("correlation_id", str(REQUEST_ID).encode())]

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        await handle_task_created_reply(FakeVectorStore(), await _pending(), reply, headers)

    assert "answered after" in caplog.text


async def test_a_request_checked_while_young_is_still_reported_once_it_is_late() -> None:
    """Checking must not use up the one report a request is owed."""

    pending = await _pending()

    assert await report_overdue(pending, now=SENT + timedelta(minutes=5)) == []
    assert await report_overdue(pending, now=SENT + timedelta(minutes=11)) == [REQUEST_ID]


async def test_a_request_already_answered_cannot_be_marked_reported() -> None:
    pending = await _pending()
    await pending.discard(REQUEST_ID)  # its reply was matched

    assert await pending.mark_reported(REQUEST_ID) is False


class AnsweredMidCheck(PendingRequests):
    """The race: the timer takes its snapshot, then the reply is matched and the
    request forgotten, before the timer gets to it."""

    async def all(self) -> list[tuple[UUID, PendingRequest]]:
        snapshot = await super().all()
        await self.discard(REQUEST_ID)
        return snapshot


async def test_no_warning_for_a_request_answered_while_the_timer_ran(
    caplog: pytest.LogCaptureFixture,
) -> None:
    pending = AnsweredMidCheck()
    await pending.add(
        REQUEST_ID, PendingRequest(bridge_correlation_id=ITEM, revision=0, task_index=0, sent_at=SENT)
    )

    with caplog.at_level(logging.WARNING, logger="mind.task_creation"):
        overdue = await report_overdue(pending, now=SENT + timedelta(minutes=11))

    assert overdue == []
    assert caplog.text == ""
