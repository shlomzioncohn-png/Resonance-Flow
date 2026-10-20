"""One `bridge.raw.landed` message, from protobuf to ExtractionItems (#349).

ProtobufConsumer commits a message's offset only when the handler returns, so
every failure here is one of two kinds:

- skip (log, return): retrying cannot fix it -- InvalidEvent, raised only
  where Bridge's input is checked: a message the contract rejects, a
  tenant_id that is not a UUID. Logged at ERROR: if Bridge breaks the
  contract, every message lands here and only the log shows it. Any other
  error, a bug of ours included, propagates: loud, not lost.
- retry (raise): it may pass later -- object storage unreachable, OCR/ASR
  unavailable, the hand-off failing. The process stops and the message comes
  again after the restart.

The item is marked processed only after the hand-off, so a crash in between
processes it again; Inbox dedups task requests on correlation_id.
"""

import logging
from collections.abc import Callable

from mind.contracts import RawObjectEvent
from mind.extraction.extractor import ExtractionItem
from mind.ingestion.event_objects import InvalidEvent, ObjectReader
from mind.ingestion.extraction_items import extraction_items
from mind.ingestion.raw_object_event_proto import to_raw_object_event
from mind.ingestion.seen_revisions import RevisionStore, classify_arrival
from mind.messaging.raw_object_event_pb2 import RawObjectEvent as RawObjectEventMessage

logger = logging.getLogger(__name__)

# The hand-off to extraction (#350): the event carries what the items do not
# (tenant, source_ref, correlation_id).
OnItems = Callable[[RawObjectEvent, list[ExtractionItem]], None]
ToItems = Callable[[RawObjectEvent, ObjectReader], list[ExtractionItem]]


class LandedHandler:
    def __init__(
        self,
        *,
        read_object: ObjectReader,
        store: RevisionStore,
        on_items: OnItems,
        to_items: ToItems = extraction_items,
    ) -> None:
        self._read_object = read_object
        self._store = store
        self._on_items = on_items
        self._to_items = to_items

    def __call__(self, message: RawObjectEventMessage, _kafka_message: object) -> None:
        try:
            event = to_raw_object_event(message)
        except InvalidEvent as e:
            logger.error("landed: skipping an invalid message: %s", e)
            return

        arrival = classify_arrival(
            self._store.processed_revision(event.correlation_id), event.revision
        )
        if not arrival.should_process:
            logger.info(
                "landed: %s revision %s skipped, %s",
                event.correlation_id,
                event.revision,
                arrival.value,
            )
            return

        # InvalidEvent here is the tenant_id check. A missing or oversized file
        # is skipped inside; storage being down propagates (retry).
        try:
            items = self._to_items(event, self._read_object)
        except InvalidEvent as e:
            logger.error("landed: skipping %s: %s", event.correlation_id, e)
            return

        if items:
            self._on_items(event, items)
        else:
            logger.info("landed: %s has nothing to extract", event.correlation_id)
        self._store.mark_processed(event.correlation_id, event.revision)
