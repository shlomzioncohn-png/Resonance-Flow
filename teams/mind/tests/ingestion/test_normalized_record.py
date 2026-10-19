import pytest
from pydantic import ValidationError

from mind.ingestion.normalized_record import NormalizedRecord


def _valid_record() -> dict[str, object]:
    return {
        "object_ref": "minio://ZOOM/tenant-123/2026-10-14/meeting.vtt",
        "content": b"WEBVTT",
        "content_hash": "sha256:" + "a" * 64,
        "content_type": "text/vtt",
        "modality": "TEXT",
        "role": "TRANSCRIPT",
        "source_type": "ZOOM",
        "tenant_id": "550e8400-e29b-41d4-a716-446655440000",
        "source_ref": None,
        "source_timestamp": None,
        "participants": None,
        "correlation_id": None,
        "revision": None,
    }


def test_a_field_may_be_none_but_not_missing() -> None:
    assert NormalizedRecord.model_validate(_valid_record()).source_ref is None

    payload = _valid_record()
    del payload["source_ref"]

    with pytest.raises(ValidationError):
        NormalizedRecord.model_validate(payload)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        pytest.param(
            "object_ref",
            "local-data/funsd/dataset/testing_data/images/82092117.png",
            id="object_ref-without-a-scheme",
        ),
        pytest.param("object_ref", "s3://bucket/key", id="object_ref-unknown-scheme"),
        pytest.param("content_hash", "a" * 64, id="content_hash-without-sha256-prefix"),
        pytest.param("tenant_id", "tenant-123", id="tenant_id-not-a-uuid"),
    ],
)
def test_rejects_a_value_in_the_wrong_shape(field: str, value: str) -> None:
    payload = _valid_record()
    payload[field] = value

    with pytest.raises(ValidationError):
        NormalizedRecord.model_validate(payload)


def test_rejects_an_unknown_field() -> None:
    payload = _valid_record()
    payload["tenant"] = "550e8400-e29b-41d4-a716-446655440000"

    with pytest.raises(ValidationError):
        NormalizedRecord.model_validate(payload)
