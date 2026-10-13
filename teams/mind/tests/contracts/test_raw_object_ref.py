import pytest
from pydantic import ValidationError

from mind.contracts import ObjectRole, RawObjectRef


def _valid_ref() -> dict[str, object]:
    return {
        "role": "BODY",
        "object_key": "EMAIL/tenant-123/2026-08-20/message.eml",
        "content_hash": "sha256:" + "a" * 64,
        "content_type": "message/rfc822",
        "size_bytes": 2048,
        "modality": "TEXT",
    }


def test_accepts_valid_raw_object_ref() -> None:
    ref = RawObjectRef.model_validate(_valid_ref())

    assert ref.role is ObjectRole.BODY
    assert ref.object_key == "EMAIL/tenant-123/2026-08-20/message.eml"
    assert ref.size_bytes == 2048


def test_role_is_required() -> None:
    payload = _valid_ref()
    del payload["role"]

    with pytest.raises(ValidationError):
        RawObjectRef.model_validate(payload)


def test_modality_is_required() -> None:
    # A default of TEXT would silently mislabel a RECORDING as text.
    payload = _valid_ref() | {"role": "RECORDING"}
    del payload["modality"]

    with pytest.raises(ValidationError):
        RawObjectRef.model_validate(payload)


def test_rejects_invalid_object_key() -> None:
    payload = _valid_ref() | {"object_key": "invalid/path/message.eml"}

    with pytest.raises(ValidationError):
        RawObjectRef.model_validate(payload)


def test_rejects_invalid_content_hash() -> None:
    payload = _valid_ref() | {"content_hash": "md5:abc"}

    with pytest.raises(ValidationError):
        RawObjectRef.model_validate(payload)


def test_rejects_fields_that_left_the_schema() -> None:
    payload = _valid_ref() | {"attachment_index": 0}

    with pytest.raises(ValidationError):
        RawObjectRef.model_validate(payload)
