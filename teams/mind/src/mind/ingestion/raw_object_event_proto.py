"""Bridge's `bridge.raw.landed` message (protobuf) -> the `RawObjectEvent` contract (#349).

Field by field, like `scoring/inbox_task.py`. Only the wire's quirks are
handled here; what is valid stays the contract's decision:

- Enum values carry a prefix on the wire (`ROLE_BODY`), Mind's names do not
  (`BODY`). An UNSPECIFIED value arrives as "UNSPECIFIED" and the contract
  rejects it, or applies its own rule where it has one (unknown quality ->
  None, unknown sensitivity -> HIGH).
- proto3 has no null: an empty string, an empty list, or an unset message or
  optional field is None.
"""

from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

from mind.contracts import RawObjectEvent
from mind.messaging.raw_object_event_pb2 import (
    ChangeType,
    Classification,
    DecidedBy,
    Modality,
    Quality,
    RawObjectRef,
    Role,
    ScopeDecision,
    Sensitivity,
    SourceType,
)
from mind.messaging.raw_object_event_pb2 import RawObjectEvent as RawObjectEventMessage


def _mind_name(name_of: Callable[[int], str], prefix: str, value: int) -> str:
    """ROLE_BODY -> "BODY"."""

    return name_of(value).removeprefix(f"{prefix}_")


def _time(message: Any, field: str) -> datetime | None:
    return getattr(message, field).ToDatetime(tzinfo=UTC) if message.HasField(field) else None


def to_raw_object_event(message: RawObjectEventMessage) -> RawObjectEvent:
    """Raises ValueError (pydantic's ValidationError) for a message the contract rejects."""

    return RawObjectEvent.model_validate(
        {
            "schema_version": message.schema_version,
            "source_type": _mind_name(SourceType.Name, "SOURCE_TYPE", message.source_type),
            "tenant_id": message.tenant_id,
            "ingested_at": _time(message, "ingested_at"),
            "scope_decision": _mind_name(
                ScopeDecision.Name, "SCOPE_DECISION", message.scope_decision
            ),
            "participants": list(message.participants) or None,
            "source_timestamp": _time(message, "source_timestamp"),
            "correlation_id": message.correlation_id,
            "revision": message.revision,
            "change_type": _mind_name(ChangeType.Name, "CHANGE_TYPE", message.change_type),
            "change_significance_score": message.change_significance_score,
            "objects": [_object(ref) for ref in message.objects],
            "source_ref": message.source_ref if message.HasField("source_ref") else None,
            "classification": (
                _classification(message.classification)
                if message.HasField("classification")
                else None
            ),
        }
    )


def _object(ref: RawObjectRef) -> dict[str, Any]:
    return {
        "role": _mind_name(Role.Name, "ROLE", ref.role),
        "object_key": ref.object_key,
        "content_hash": ref.content_hash,
        "content_type": ref.content_type or None,
        "size_bytes": ref.size_bytes if ref.HasField("size_bytes") else None,
        "modality": _mind_name(Modality.Name, "MODALITY", ref.modality),
    }


def _classification(classification: Classification) -> dict[str, Any]:
    category, quality, sensitivity = (
        classification.category,
        classification.quality,
        classification.sensitivity,
    )
    return {
        "decided_by": _mind_name(DecidedBy.Name, "DECIDED_BY", classification.decided_by),
        # A plain string on the wire: Bridge writes its enum's name, unprefixed.
        "category": (
            {"value": category.value, "confidence": category.confidence}
            if classification.HasField("category")
            else None
        ),
        "quality": (
            {
                "value": _mind_name(Quality.Name, "QUALITY", quality.value),
                "confidence": quality.confidence,
            }
            if classification.HasField("quality")
            else None
        ),
        "sensitivity": {
            "value": _mind_name(Sensitivity.Name, "SENSITIVITY", sensitivity.value),
            "confidence": sensitivity.confidence,
        },
        "reason": classification.reason,
        "classifier": {
            "model": classification.classifier.model,
            "prompt_version": classification.classifier.prompt_version,
        },
    }
