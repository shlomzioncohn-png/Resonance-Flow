"""A landed Bridge item -> the ExtractionItems extraction reads (#349).

One file at a time: read it, check it, turn it into text, and let its bytes go
before the next is read, so memory holds one file, not the whole event. What
the extraction needs besides the text (tenant, source_ref, correlation_id) is
on the event itself, which travels next to the items.

Transcripts are handled first: a RECORDING is skipped only when a transcript of
the event actually gave text, so a missing or broken transcript still leaves
the recording to be transcribed -- and a readable one means the recording is
never even read.
"""

import logging

from mind.contracts import RawObjectEvent
from mind.contracts.base_enums import SourceType
from mind.contracts.raw_object_event import RawObjectRef
from mind.contracts.raw_object_event_enums import (
    ChangeType,
    ObjectRole,
    RawSourceType,
    ScopeDecision,
)
from mind.extraction.extractor import ExtractionItem
from mind.ingestion.event_objects import ObjectReader, read_event_object, tenant_uuid
from mind.ingestion.to_text import MediaPipeline, text_of
from mind.multimodal.asr import transcribe
from mind.multimodal.ocr import extract_text

logger = logging.getLogger(__name__)

# Bridge's source -> the task's. Every RawSourceType is listed, no default: a new
# Bridge value must be mapped on purpose. ZOOM / BIGBLUEBUTTON become MEETING until
# Inbox's own values (#749) reach shared/proto.
_TASK_SOURCE_TYPE: dict[RawSourceType, SourceType] = {
    RawSourceType.EMAIL: SourceType.EMAIL,
    RawSourceType.SLACK: SourceType.SLACK,
    RawSourceType.WHATSAPP: SourceType.WHATSAPP,
    RawSourceType.DRIVE: SourceType.DRIVE,
    RawSourceType.CALENDAR: SourceType.CALENDAR,
    RawSourceType.SCANNED_DOC: SourceType.SCANNED_DOC,
    RawSourceType.ZOOM: SourceType.MEETING,
    RawSourceType.BIGBLUEBUTTON: SourceType.MEETING,
}


def task_source_type(raw: RawSourceType) -> SourceType:
    return _TASK_SOURCE_TYPE[raw]


def _has_words(transcript: str) -> bool:
    """Whether a transcript holds any speech.

    Only a WEBVTT file is checked: it can carry just its header, notes, cue
    identifiers and timing lines (Zoom delivers one when it caught no speech),
    and then the recording must still be transcribed. Anything else with the
    TRANSCRIPT role, a Slack or WhatsApp session's text, is taken as it is.
    """

    if not transcript.lstrip("\ufeff \t\r\n").startswith("WEBVTT"):
        return True
    lines = [line.strip() for line in transcript.splitlines()]
    for i, line in enumerate(lines):
        if not line or line.lstrip("\ufeff").startswith(("WEBVTT", "NOTE")) or "-->" in line:
            continue
        # The line right before a timing line names the cue (any text, not
        # only a number): an identifier, not speech.
        if i + 1 < len(lines) and "-->" in lines[i + 1]:
            continue
        return True
    return False


def extraction_items(
    event: RawObjectEvent,
    read_object: ObjectReader,
    *,
    ocr: MediaPipeline = extract_text,
    asr: MediaPipeline = transcribe,
) -> list[ExtractionItem]:
    """Raises InvalidEvent for a tenant_id that is not a UUID, MediaUnavailable
    when OCR/ASR cannot run here, and lets a storage outage propagate."""

    # Nothing is read for these: Bridge judged an EXCLUDED item not work, and
    # it is often private; a DELETE_REVIEW carries no objects.
    if event.change_type is ChangeType.DELETE_REVIEW:
        return []
    if event.scope_decision is ScopeDecision.EXCLUDED:
        logger.info("extraction_items: skipping EXCLUDED item %s", event.correlation_id)
        return []
    tenant_uuid(event)
    source_type = task_source_type(event.source_type)

    def item_of(obj: RawObjectRef) -> ExtractionItem | None:
        content = read_event_object(obj, read_object, event.correlation_id)
        if content is None:
            return None
        text = text_of(content, obj.modality, obj.object_key, ocr, asr)
        if text is None:
            return None
        if not text.strip():
            logger.info("extraction_items: %s has no text in it, skipped", obj.object_key)
            return None
        if obj.role is ObjectRole.TRANSCRIPT and not _has_words(text):
            logger.info("extraction_items: %s is a transcript with no speech", obj.object_key)
            return None
        return ExtractionItem(text=text, source_type=source_type, item_key=obj.object_key)

    # First pass: transcripts only.
    items: list[ExtractionItem] = []
    for obj in event.objects:
        if obj.role is ObjectRole.TRANSCRIPT and (item := item_of(obj)):
            items.append(item)
    has_transcript = bool(items)

    # Second pass: everything else, one file at a time.
    for obj in event.objects:
        # SOURCE_METADATA describes the item rather than being its content.
        if obj.role in (ObjectRole.TRANSCRIPT, ObjectRole.SOURCE_METADATA):
            continue
        if obj.role is ObjectRole.RECORDING and has_transcript:
            logger.info("extraction_items: %s not read, the event has a transcript", obj.object_key)
            continue
        if item := item_of(obj):
            items.append(item)
    return items
