import hashlib
import logging
from typing import Any
from uuid import UUID

import pytest

from mind.contracts import RawObjectEvent
from mind.ingestion.landed_consumer import LandedHandler
from mind.ingestion.normalized_record import NormalizedRecord
from mind.ingestion.raw_object_event_handler import ObjectNotFound
from mind.ingestion.seen_revisions import InMemoryRevisionStore
from mind.ingestion.to_text import MediaUnavailable
from mind.messaging import raw_object_event_pb2 as pb

TENANT = "550e8400-e29b-41d4-a716-446655440000"
ITEM = UUID("6f1c1f9e-0000-4000-8000-000000000001")
KEY = f"SLACK/{TENANT}/2026-10-18/thread.txt"
BODY = b"Can you send the updated price quote by tomorrow?"


def _message(**overrides: Any) -> pb.RawObjectEvent:
    body = pb.RawObjectRef(
        role=pb.ROLE_BODY,
        object_key=KEY,
        content_hash=f"sha256:{hashlib.sha256(BODY).hexdigest()}",
        modality=pb.MODALITY_TEXT,
    )
    fields: dict[str, Any] = {
        "schema_version": "1.0",
        "source_type": pb.SOURCE_TYPE_SLACK,
        "tenant_id": TENANT,
        "scope_decision": pb.SCOPE_DECISION_INCLUDED,
        "correlation_id": str(ITEM),
        "revision": 0,
        "change_type": pb.CHANGE_TYPE_UPSERT,
        "change_significance_score": 80,
        "objects": [body],
    }
    message = pb.RawObjectEvent(**(fields | overrides))
    message.ingested_at.GetCurrentTime()
    return message


def _read(key: str) -> bytes:
    if key != KEY:
        raise ObjectNotFound(key)
    return BODY


class _Handed:
    """Stands in for the hand-off to extraction (#350)."""

    def __init__(self) -> None:
        self.calls: list[tuple[RawObjectEvent, list[str]]] = []

    def __call__(self, event: RawObjectEvent, texts: list[tuple[NormalizedRecord, str]]) -> None:
        self.calls.append((event, [text for _, text in texts]))


def _handler(**overrides: Any) -> tuple[LandedHandler, _Handed, InMemoryRevisionStore]:
    handed, store = _Handed(), InMemoryRevisionStore()
    parts: dict[str, Any] = {"read_object": _read, "store": store, "on_texts": handed}
    return LandedHandler(**(parts | overrides)), handed, store


def test_a_landed_message_is_handed_on_as_text_and_marked_processed() -> None:
    handle, handed, store = _handler()

    handle(_message(), None)

    ((event, texts),) = handed.calls
    assert event.correlation_id == ITEM
    assert texts == [BODY.decode()]
    assert store.processed_revision(ITEM) == 0


def test_the_same_message_twice_is_handed_on_once() -> None:
    handle, handed, _ = _handler()

    handle(_message(), None)
    handle(_message(), None)

    assert len(handed.calls) == 1


def test_a_message_the_contract_rejects_is_skipped_loudly(caplog: pytest.LogCaptureFixture) -> None:
    handle, handed, store = _handler()

    handle(_message(source_type=pb.SOURCE_TYPE_UNSPECIFIED), None)  # does not raise

    assert handed.calls == []
    assert store.processed_revision(ITEM) is None
    assert any(r.levelno == logging.ERROR for r in caplog.records)


def _storage_down(key: str) -> bytes:
    raise ConnectionError("MinIO unreachable")


def _no_model(records: list[NormalizedRecord]) -> list[tuple[NormalizedRecord, str]]:
    raise MediaUnavailable("ASR_UNAVAILABLE")


def _our_bug(records: list[NormalizedRecord]) -> list[tuple[NormalizedRecord, str]]:
    # A ValueError of our own, not Bridge's: it must stop the consumer loudly,
    # not be skipped as if the message were invalid.
    raise ValueError("a bug in Mind")


def _hand_off_fails(event: RawObjectEvent, texts: list[tuple[NormalizedRecord, str]]) -> None:
    raise RuntimeError("extraction down")


@pytest.mark.parametrize(
    ("override", "error"),
    [
        ({"read_object": _storage_down}, ConnectionError),
        ({"to_texts": _no_model}, MediaUnavailable),
        ({"on_texts": _hand_off_fails}, RuntimeError),
        ({"to_texts": _our_bug}, ValueError),
    ],
)
def test_a_failure_that_may_pass_later_propagates_and_nothing_is_marked(
    override: dict[str, Any], error: type[Exception]
) -> None:
    handle, _, store = _handler(**override)

    with pytest.raises(error):
        handle(_message(), None)
    assert store.processed_revision(ITEM) is None


def test_a_tenant_id_that_is_not_a_uuid_is_skipped_loudly(caplog: pytest.LogCaptureFixture) -> None:
    # Bridge's Slack and media lanes default to "kamatech" / "demo-tenant" today; Inbox
    # needs a UUID, and the same message would fail on every redelivery.
    handle, handed, store = _handler()

    handle(_message(tenant_id="kamatech"), None)  # does not raise

    assert handed.calls == []
    assert store.processed_revision(ITEM) is None
    assert any(r.levelno == logging.ERROR for r in caplog.records)


def test_an_item_with_nothing_to_extract_is_marked_without_a_hand_off() -> None:
    handle, handed, store = _handler()

    handle(_message(scope_decision=pb.SCOPE_DECISION_EXCLUDED), None)

    assert handed.calls == []
    assert store.processed_revision(ITEM) == 0
