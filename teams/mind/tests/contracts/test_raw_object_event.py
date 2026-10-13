import pytest
from pydantic import ValidationError

from mind.contracts import ObjectRole, RawObjectEvent, RawObjectRef


def _valid_event() -> dict[str, object]:
    return {
        "schema_version": "1.0",
        "source_type": "EMAIL",
        "tenant_id": "tenant-123",
        "correlation_id": "550e8400-e29b-41d4-a716-446655440000",
        "ingested_at": "2026-08-20T12:30:00Z",
        "scope_decision": "INCLUDED",
        "revision": 0,
        "change_type": "UPSERT",
        "change_significance_score": 100,
        "source_ref": "<CAF123@mail.example.com>",
        "objects": [
            {
                "role": "BODY",
                "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
                "content_hash": "sha256:" + "a" * 64,
                "content_type": "message/rfc822",
                "size_bytes": 2048,
                "modality": "TEXT",
            },
        ],
    }


def test_accepts_valid_raw_object_event() -> None:
    event = RawObjectEvent.model_validate(_valid_event())

    assert event.source_type.value == "EMAIL"
    assert event.source_ref == "<CAF123@mail.example.com>"
    assert len(event.objects) == 1
    assert isinstance(event.objects[0], RawObjectRef)
    assert event.objects[0].role is ObjectRole.BODY


def test_source_ref_is_optional() -> None:
    payload = _valid_event()
    del payload["source_ref"]

    assert RawObjectEvent.model_validate(payload).source_ref is None


@pytest.mark.parametrize(
    "field",
    [
        # moved into RawObjectRef
        "object_key",
        "content_hash",
        "content_type",
        "size_bytes",
        "modality",
        # removed from the schema
        "attachment_index",
        "attachment_count",
        "transcript_object_key",
    ],
)
def test_rejects_fields_that_left_the_event_level(field: str) -> None:
    payload = _valid_event() | {field: "anything"}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_invalid_object_inside_objects() -> None:
    payload = _valid_event()
    payload["objects"][0]["object_key"] = "invalid/path/message.eml"  # type: ignore[index]

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_invalid_source_type() -> None:
    payload = _valid_event() | {"source_type": "FACEBOOK"}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_empty_tenant_id() -> None:
    payload = _valid_event() | {"tenant_id": ""}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_missing_schema_version() -> None:
    payload = _valid_event()
    del payload["schema_version"]

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_non_utc_timestamp() -> None:
    payload = _valid_event() | {"ingested_at": "2026-08-20T12:30:00+03:00"}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_invalid_correlation_id() -> None:
    payload = _valid_event() | {"correlation_id": "not-a-uuid"}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_correlation_id_is_required() -> None:
    # It is half of the dedup key: correlation_id + ":" + revision.
    payload = _valid_event()
    del payload["correlation_id"]

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_upsert_requires_at_least_one_object() -> None:
    payload = _valid_event() | {"objects": []}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_delete_review_carries_no_objects() -> None:
    payload = _valid_event() | {"change_type": "DELETE_REVIEW", "objects": []}

    assert RawObjectEvent.model_validate(payload).objects == []


def test_delete_review_rejects_objects() -> None:
    payload = _valid_event() | {"change_type": "DELETE_REVIEW"}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


def test_rejects_meeting_source_type() -> None:
    # Bridge sends ZOOM / BIGBLUEBUTTON; MEETING exists only for the task output.
    payload = _valid_event() | {"source_type": "MEETING"}

    with pytest.raises(ValidationError):
        RawObjectEvent.model_validate(payload)


@pytest.mark.parametrize("source_type", ["ZOOM", "BIGBLUEBUTTON"])
def test_accepts_meeting_platforms(source_type: str) -> None:
    payload = _valid_event() | {"source_type": source_type}

    assert RawObjectEvent.model_validate(payload).source_type.value == source_type
