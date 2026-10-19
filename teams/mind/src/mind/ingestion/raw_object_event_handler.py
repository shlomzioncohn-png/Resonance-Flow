"""A Bridge RawObjectEvent -> NormalizedRecord, one per content object."""

import hashlib
import logging
from collections.abc import Callable
from uuid import UUID

from mind.contracts import RawObjectEvent
from mind.contracts.raw_object_event_enums import ChangeType, ObjectRole, ScopeDecision
from mind.ingestion.normalized_record import NormalizedRecord

logger = logging.getLogger(__name__)


class InvalidEvent(Exception):
    """What Bridge sent cannot be processed, and never will be: the consumer
    skips it. Raised on purpose, only where Bridge's input is checked, so a
    ValueError from a bug of ours is not mistaken for it."""


class ObjectNotFound(Exception):
    """The store has no object under this key. Readers raise it for that case
    only: any other failure (the store is down) must propagate, or a whole
    outage would be skipped object by object and lost."""


class ObjectTooLarge(Exception):
    """The object is over the reader's size limit, so it was not read whole.
    Reading it would risk the process's memory, and a crash there would come
    back on the same message after every restart."""


# object_key -> bytes, raising ObjectNotFound when the key is absent and
# ObjectTooLarge when it is over the limit. MinIO in production; a dict in the tests.
ObjectReader = Callable[[str], bytes]


def handle_raw_object_event(
    event: RawObjectEvent, read_object: ObjectReader
) -> list[NormalizedRecord]:
    """Read the event's content objects and normalize each.

    Nothing comes out of a DELETE_REVIEW (no objects) or an EXCLUDED item, and
    an EXCLUDED item's objects are never read: Bridge judged them not work, and
    they are often private. SOURCE_METADATA describes the item rather than
    being its content, so it is skipped too.

    A missing object, one too large to read, or one that does not match its
    content_hash is logged and skipped; the rest of the event still comes through.
    """

    if event.change_type is ChangeType.DELETE_REVIEW:
        return []
    if event.scope_decision is ScopeDecision.EXCLUDED:
        logger.info("raw_object_event: skipping EXCLUDED item %s", event.correlation_id)
        return []

    # Here and not in NormalizedRecord, so the error names Bridge's field.
    try:
        tenant_id = UUID(event.tenant_id)
    except ValueError:
        raise InvalidEvent(f"RawObjectEvent.tenant_id is not a UUID: {event.tenant_id!r}") from None

    records = []
    for obj in event.objects:
        if obj.role is ObjectRole.SOURCE_METADATA:
            continue
        try:
            content = read_object(obj.object_key)
        except ObjectNotFound:
            logger.warning(
                "raw_object_event: skipping missing object %s of %s",
                obj.object_key,
                event.correlation_id,
            )
            continue
        except ObjectTooLarge:
            logger.error(
                "raw_object_event: skipping %s of %s, over the size limit",
                obj.object_key,
                event.correlation_id,
            )
            continue
        if f"sha256:{hashlib.sha256(content).hexdigest()}" != obj.content_hash:
            logger.warning(
                "raw_object_event: skipping %s of %s, content does not match its hash",
                obj.object_key,
                event.correlation_id,
            )
            continue
        records.append(
            NormalizedRecord(
                object_ref=f"minio://{obj.object_key}",
                content=content,
                content_hash=obj.content_hash,
                content_type=obj.content_type,
                modality=obj.modality,
                role=obj.role,
                source_type=event.source_type,
                tenant_id=tenant_id,
                source_ref=event.source_ref,
                source_timestamp=event.source_timestamp,
                participants=event.participants,
                correlation_id=event.correlation_id,
                revision=event.revision,
                metadata={"ingested_at": event.ingested_at.isoformat()},
            )
        )
    return records
