from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import pytest

from mind.contracts.raw_object_event_enums import (
    Category,
    ChangeType,
    DecidedBy,
    Modality,
    ObjectRole,
    RawSourceType,
    ScopeDecision,
    Sensitivity,
)
from mind.ingestion.raw_object_event_proto import to_raw_object_event
from mind.messaging import raw_object_event_pb2 as pb

TENANT = "550e8400-e29b-41d4-a716-446655440000"
ITEM = "6f1c1f9e-0000-4000-8000-000000000001"
KEY = f"ZOOM/{TENANT}/2026-10-18/meeting.vtt"
HASH = "sha256:" + "a" * 64
INGESTED = datetime(2026, 10, 18, 9, 0, tzinfo=UTC)


def _message(**overrides: Any) -> pb.RawObjectEvent:
    fields: dict[str, Any] = {
        "schema_version": "1.0",
        "source_type": pb.SOURCE_TYPE_ZOOM,
        "tenant_id": TENANT,
        "scope_decision": pb.SCOPE_DECISION_INCLUDED,
        "correlation_id": ITEM,
        "revision": 0,  # proto3's default: still a real revision, not "missing"
        "change_type": pb.CHANGE_TYPE_UPSERT,
        "change_significance_score": 80,
        "objects": [
            pb.RawObjectRef(
                role=pb.ROLE_TRANSCRIPT,
                object_key=KEY,
                content_hash=HASH,
                modality=pb.MODALITY_TEXT,
            )
        ],
    }
    message = pb.RawObjectEvent(**(fields | overrides))
    message.ingested_at.FromDatetime(INGESTED)
    return message


def test_a_message_becomes_the_contract_with_mind_names() -> None:
    event = to_raw_object_event(_message())

    assert event.source_type is RawSourceType.ZOOM
    assert event.scope_decision is ScopeDecision.INCLUDED
    assert event.change_type is ChangeType.UPSERT
    assert event.correlation_id == UUID(ITEM)
    assert event.revision == 0
    assert event.ingested_at == INGESTED
    (obj,) = event.objects
    assert (obj.role, obj.modality, obj.object_key) == (ObjectRole.TRANSCRIPT, Modality.TEXT, KEY)


def test_what_bridge_did_not_send_is_none() -> None:
    event = to_raw_object_event(_message())

    assert event.source_ref is None
    assert event.source_timestamp is None
    assert event.participants is None
    assert event.classification is None
    assert (event.objects[0].content_type, event.objects[0].size_bytes) == (None, None)


def test_what_bridge_did_send_comes_through() -> None:
    sent = pb.RawObjectRef(
        role=pb.ROLE_TRANSCRIPT,
        object_key=KEY,
        content_hash=HASH,
        content_type="text/vtt",
        size_bytes=0,  # sent, though zero: not the same as absent
        modality=pb.MODALITY_TEXT,
    )
    message = _message(
        source_ref="zoom-uuid-123", participants=["alex@example.com"], objects=[sent]
    )
    message.source_timestamp.FromDatetime(INGESTED)

    event = to_raw_object_event(message)

    assert event.source_ref == "zoom-uuid-123"
    assert event.participants == ["alex@example.com"]
    assert event.source_timestamp == INGESTED
    assert (event.objects[0].content_type, event.objects[0].size_bytes) == ("text/vtt", 0)


def test_a_classification_with_no_quality_keeps_the_rest() -> None:
    classification = pb.Classification(
        decided_by=pb.DECIDED_BY_MODEL,
        # Bridge writes its Java enum name, unprefixed (RawObjectEventProtoMapper).
        category=pb.CategoryLabel(value="PLANNING", confidence=0.8),
        sensitivity=pb.SensitivityLabel(value=pb.SENSITIVITY_LOW, confidence=0.9),
        reason="internal planning call",
        classifier=pb.ClassifierInfo(model="gemini-3.8-flash", prompt_version="v3"),
    )

    event = to_raw_object_event(_message(classification=classification))

    assert event.classification is not None
    assert event.classification.decided_by is DecidedBy.MODEL
    assert event.classification.category is not None
    assert event.classification.category.value is Category.PLANNING
    assert event.classification.sensitivity.value is Sensitivity.LOW
    assert event.classification.quality is None


def test_a_message_without_a_source_type_is_rejected() -> None:
    with pytest.raises(ValueError):
        to_raw_object_event(_message(source_type=pb.SOURCE_TYPE_UNSPECIFIED))
