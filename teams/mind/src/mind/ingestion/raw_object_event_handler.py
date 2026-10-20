"""Reading a landed event's files: the reader contract, its errors, and the
checks on what Bridge sent (tenant, content_hash). Used by extraction_items.py."""

import hashlib
import logging
from collections.abc import Callable
from uuid import UUID

from mind.contracts import RawObjectEvent
from mind.contracts.raw_object_event import RawObjectRef

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


def tenant_uuid(event: RawObjectEvent) -> UUID:
    """Inbox needs the tenant as a UUID; Bridge's contract allows any string."""

    # Here and not in a model, so the error names Bridge's field.
    try:
        return UUID(event.tenant_id)
    except ValueError:
        raise InvalidEvent(f"RawObjectEvent.tenant_id is not a UUID: {event.tenant_id!r}") from None


def read_checked(obj: RawObjectRef, read_object: ObjectReader, item: UUID) -> bytes | None:
    """The file's bytes, or None when it is missing, too large, or does not
    match its content_hash (logged). Any other read failure propagates."""

    try:
        content = read_object(obj.object_key)
    except ObjectNotFound:
        logger.warning("raw_object_event: skipping missing object %s of %s", obj.object_key, item)
        return None
    except ObjectTooLarge:
        logger.error(
            "raw_object_event: skipping %s of %s, over the size limit", obj.object_key, item
        )
        return None
    if f"sha256:{hashlib.sha256(content).hexdigest()}" != obj.content_hash:
        logger.warning(
            "raw_object_event: skipping %s of %s, content does not match its hash",
            obj.object_key,
            item,
        )
        return None
    return content
