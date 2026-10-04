import pytest
from pydantic import ValidationError

from mind.contracts import RawObjectEvent


def test_rejects_invalid_object_key() -> None:
    payload = {
        "object_key": "invalid/path/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_accepts_valid_raw_object_event() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": 0,
        "attachment_count": 1,
        "revision": 0,
        "change_type": "UPSERT",
        "change_significance_score": 100,
        "correlation_id": "550e8400-e29b-41d4-a716-446655440000",
        "transcript_object_key": (
            "MEETING/tenant-123/2026-08-20/transcript.txt"
        ),
    }

    event = RawObjectEvent.model_validate(payload)

    assert event.object_key == (
        "EMAIL/tenant-123/2026-08-20/message.eml"
    )
    assert event.source_type.value == "EMAIL"
    assert event.tenant_id == "tenant-123"
    assert event.scope_decision.value == "INCLUDED"

    assert event.attachment_index == 0
    assert event.attachment_count == 1

    assert event.revision == 0
    assert event.change_type.value == "UPSERT"
    assert event.change_significance_score == 100

    assert str(event.correlation_id) == (
        "550e8400-e29b-41d4-a716-446655440000"
    )

    assert event.transcript_object_key == (
        "MEETING/tenant-123/2026-08-20/transcript.txt"
    )
    
def test_rejects_invalid_source_type() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "FACEBOOK",  # Invalid source type
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_negative_size_bytes() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "size_bytes": -1,
        "schema_version": "1.0",
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_invalid_content_hash() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": "not-a-sha256",
        "schema_version": "1.0",
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_unknown_field() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "unknown_field": "unexpected value",
        "schema_version": "1.0",
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_empty_tenant_id() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_missing_schema_version() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_non_utc_timestamp() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T15:30:00+03:00",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": 0,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_missing_attachment_index() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_negative_attachment_index() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": -1,
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_invalid_correlation_id() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": 0,
        "correlation_id": "not-a-uuid",
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_invalid_transcript_object_key() -> None:
    payload = {
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "content_hash": (
            "sha256:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ),
        "schema_version": "1.0",
        "attachment_index": 0,
        "transcript_object_key": "invalid/transcript/path",
    }

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)
        