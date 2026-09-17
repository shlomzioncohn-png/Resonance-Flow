import hashlib
from datetime import datetime, timezone

import pytest

from mind.contracts import RawObjectEvent
from mind.contracts.base_enums import SourceType
from mind.contracts.raw_object_event_enums import (
    ChangeType,
    Modality,
    ScopeDecision,
)
from mind.ingestion.raw_object_event_handler import handle_raw_object_event


def make_content_hash(content: bytes) -> str:
    return "sha256:" + hashlib.sha256(content).hexdigest()


def make_event(content_hash: str) -> RawObjectEvent:
    return RawObjectEvent(
        object_key=(
            "SCANNED_DOC/tenant-123/2026-08-20/82092117.png"
        ),
        source_type=SourceType.SCANNED_DOC,
        tenant_id="tenant-123",
        ingested_at=datetime(
            2026,
            8,
            20,
            12,
            30,
            tzinfo=timezone.utc,
        ),
        scope_decision=ScopeDecision.INCLUDED,
        content_hash=content_hash,
        schema_version="1.0",
        attachment_index=0,
        attachment_count=1,
        revision=0,
        change_type=ChangeType.UPSERT,
        change_significance_score=100,
        content_type="image/png",
        modality=Modality.SCANNED_DOC,
    )


def test_converts_raw_object_event_to_normalized_record() -> None:
    content = b"realistic fake png bytes"
    content_hash = make_content_hash(content)

    event = make_event(content_hash)

    def read_object(object_key: str) -> bytes:
        assert object_key == event.object_key
        return content

    record = handle_raw_object_event(
        event=event,
        read_object=read_object,
    )

    assert record.record_id == content_hash
    assert record.source_ref == event.object_key
    assert record.content == content
    assert record.content_type == "image/png"
    assert record.modality == Modality.SCANNED_DOC
    assert record.source_type == SourceType.SCANNED_DOC

    assert record.metadata["tenant_id"] == "tenant-123"
    assert record.metadata["scope_decision"] == "INCLUDED"
    assert record.metadata["schema_version"] == "1.0"
    assert record.metadata["attachment_index"] == 0
    assert record.metadata["attachment_count"] == 1
    assert record.metadata["revision"] == 0
    assert record.metadata["change_type"] == "UPSERT"
    assert record.metadata["change_significance_score"] == 100


def test_rejects_content_when_hash_does_not_match() -> None:
    expected_content = b"expected content"
    actual_content = b"different content"

    event = make_event(
        make_content_hash(expected_content)
    )

    def read_object(object_key: str) -> bytes:
        return actual_content

    with pytest.raises(
        ValueError,
        match="content hash does not match",
    ):
        handle_raw_object_event(
            event=event,
            read_object=read_object,
        )