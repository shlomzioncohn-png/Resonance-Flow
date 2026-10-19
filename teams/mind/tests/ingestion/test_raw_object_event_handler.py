import hashlib
import logging
from typing import Any
from uuid import UUID

import pytest

from mind.contracts import RawObjectEvent
from mind.contracts.raw_object_event_enums import ObjectRole
from mind.ingestion.raw_object_event_handler import (
    InvalidEvent,
    ObjectNotFound,
    ObjectReader,
    ObjectTooLarge,
    handle_raw_object_event,
)

TENANT = "550e8400-e29b-41d4-a716-446655440000"
BODY_KEY = f"ZOOM/{TENANT}/2026-10-14/meeting.vtt"
META_KEY = f"ZOOM/{TENANT}/2026-10-14/meeting.json"
ATTACHMENT_KEY = f"ZOOM/{TENANT}/2026-10-14/slides.pdf"
STORE = {BODY_KEY: b"WEBVTT", META_KEY: b"{}", ATTACHMENT_KEY: b"%PDF-1.7"}


def _reader(store: dict[str, bytes]) -> ObjectReader:
    """Reads from a dict the way MinIO's reader must: an absent key is ObjectNotFound."""

    def read(key: str) -> bytes:
        try:
            return store[key]
        except KeyError:
            raise ObjectNotFound(key) from None

    return read


def _object(role: str, key: str) -> dict[str, Any]:
    digest = hashlib.sha256(STORE[key]).hexdigest()
    return {"role": role, "object_key": key, "content_hash": f"sha256:{digest}", "modality": "TEXT"}


def _event(**overrides: Any) -> RawObjectEvent:
    payload = {
        "schema_version": "1.0",
        "source_type": "ZOOM",
        "tenant_id": TENANT,
        "correlation_id": "6f1c1f9e-0000-4000-8000-000000000001",
        "ingested_at": "2026-10-14T09:00:00Z",
        "scope_decision": "INCLUDED",
        "revision": 2,
        "change_type": "UPSERT",
        "change_significance_score": 80,
        "source_ref": "zoom-uuid-123",
        "participants": ["alex@example.com"],
        "objects": [_object("BODY", BODY_KEY), _object("SOURCE_METADATA", META_KEY)],
    }
    return RawObjectEvent.model_validate(payload | overrides)


def test_the_body_becomes_a_record_and_source_metadata_is_skipped() -> None:
    (record,) = handle_raw_object_event(_event(), _reader(STORE))

    assert record.object_ref == f"minio://{BODY_KEY}"
    assert record.content == b"WEBVTT"
    assert record.role is ObjectRole.BODY
    assert record.tenant_id == UUID(TENANT)
    assert record.source_ref == "zoom-uuid-123"
    assert record.participants == ["alex@example.com"]
    assert record.correlation_id == UUID("6f1c1f9e-0000-4000-8000-000000000001")
    assert record.revision == 2


def test_excluded_content_is_never_read() -> None:
    def must_not_read(key: str) -> bytes:
        raise AssertionError(f"read {key} although Bridge excluded it")

    assert handle_raw_object_event(_event(scope_decision="EXCLUDED"), must_not_read) == []


def test_a_delete_review_yields_nothing() -> None:
    event = _event(change_type="DELETE_REVIEW", objects=[])

    assert handle_raw_object_event(event, _reader(STORE)) == []


def _with_attachment() -> RawObjectEvent:
    # The attachment first, so skipping it must go on to the body rather than stop.
    return _event(objects=[_object("ATTACHMENT", ATTACHMENT_KEY), _object("BODY", BODY_KEY)])


def test_an_object_that_does_not_match_its_hash_is_skipped_and_the_rest_kept(
    caplog: pytest.LogCaptureFixture,
) -> None:
    tampered = STORE | {ATTACHMENT_KEY: b"something else"}

    (record,) = handle_raw_object_event(_with_attachment(), _reader(tampered))

    assert record.role is ObjectRole.BODY
    assert ATTACHMENT_KEY in caplog.text


def test_a_missing_object_is_skipped_and_the_rest_kept(caplog: pytest.LogCaptureFixture) -> None:
    without_attachment = {k: v for k, v in STORE.items() if k != ATTACHMENT_KEY}

    (record,) = handle_raw_object_event(_with_attachment(), _reader(without_attachment))

    assert record.role is ObjectRole.BODY
    assert ATTACHMENT_KEY in caplog.text


def test_an_object_too_large_to_read_is_skipped_loudly(caplog: pytest.LogCaptureFixture) -> None:
    def attachment_too_large(key: str) -> bytes:
        if key == ATTACHMENT_KEY:
            raise ObjectTooLarge(key)
        return STORE[key]

    (record,) = handle_raw_object_event(_with_attachment(), attachment_too_large)

    assert record.role is ObjectRole.BODY
    assert any(
        r.levelno == logging.ERROR and ATTACHMENT_KEY in r.getMessage() for r in caplog.records
    )


def test_a_storage_outage_is_not_mistaken_for_a_missing_object() -> None:
    def storage_down(key: str) -> bytes:
        raise ConnectionError("MinIO unreachable")

    with pytest.raises(ConnectionError):
        handle_raw_object_event(_with_attachment(), storage_down)


def test_a_tenant_id_that_is_not_a_uuid_is_rejected() -> None:
    with pytest.raises(InvalidEvent):
        handle_raw_object_event(_event(tenant_id="tenant-123"), _reader(STORE))
