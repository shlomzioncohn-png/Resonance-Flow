"""A landed Bridge item -> the one ExtractionItem extraction reads (#349).

The item is extracted as a whole: an email body and its scanned attachment
read together, so a deadline in one and the work in the other make one task,
not two half-tasks. Its files are joined into one text (item_text), each under
a heading when the event landed several, keyed by the arrival's correlation_id.

One file at a time: read it, check it, turn it into text, and let its bytes go
before the next is read, so memory holds one file plus the texts, not the whole
event. What the extraction needs besides the text (tenant, source_ref,
correlation_id) is on the event itself, which travels next to the item.

Transcripts are handled first: a RECORDING is skipped only when a transcript of
the event actually gave text, so a missing or broken transcript still leaves
the recording to be transcribed -- and a readable one means the recording is
never even read.
"""

import logging
from pathlib import PurePosixPath
from typing import assert_never

from mind.contracts import RawObjectEvent
from mind.contracts.base_enums import SourceType
from mind.contracts.raw_object_event import RawObjectRef
from mind.contracts.raw_object_event_enums import (
    ChangeType,
    Modality,
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


def _heading(obj: RawObjectRef) -> str:
    """What the part is (its role), and how it became text (its modality).
    The source type is not repeated: the extraction prompt already states it."""

    match obj.role:
        case ObjectRole.BODY:
            what = "Body"
        case ObjectRole.TRANSCRIPT:
            what = "Transcript"
        case ObjectRole.RECORDING:
            what = "Recording"
        case ObjectRole.ATTACHMENT:
            what = f"Attachment: {PurePosixPath(obj.object_key).name}"
        case ObjectRole.SOURCE_METADATA:
            # Never read, so never here; listed because assert_never below
            # makes mypy fail until every role has a branch.
            what = "Source metadata"
        case _:
            assert_never(obj.role)
    match obj.modality:
        case Modality.TEXT:
            how = ""
        case Modality.SCANNED_DOC:
            how = ", scanned"
        case Modality.AUDIO:
            how = ", transcribed"
        case _:
            assert_never(obj.modality)
    return f"[{what}{how}]"


def item_text(parts: list[tuple[RawObjectRef, str]], *, headed: bool) -> str:
    """One text for the whole item, the parts in the event's order.

    `headed` says whether the event landed more than one content file. If it
    did, every part goes under its heading, even when only one is left (the
    others missing or unreadable): it is still an attachment, still scanned.
    An item that had one file to begin with goes as it is, so it reads to
    extraction exactly as that file does."""

    if not headed:
        return "\n\n".join(text for _, text in parts)
    return "\n\n".join(f"{_heading(obj)}\n{text}" for obj, text in parts)


def extraction_items(
    event: RawObjectEvent,
    read_object: ObjectReader,
    *,
    ocr: MediaPipeline = extract_text,
    asr: MediaPipeline = transcribe,
) -> list[ExtractionItem]:
    """The item's one ExtractionItem, or an empty list when nothing in it is
    read or readable; a list because that is what extraction takes.

    Raises InvalidEvent for a tenant_id that is not a UUID, MediaUnavailable
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

    def text_of_file(obj: RawObjectRef) -> str | None:
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
        return text

    # (position in the event, file, its text): read out of order, joined in order.
    parts: list[tuple[int, RawObjectRef, str]] = []

    # First pass: transcripts only.
    for position, obj in enumerate(event.objects):
        if obj.role is ObjectRole.TRANSCRIPT and (text := text_of_file(obj)):
            parts.append((position, obj, text))
    has_transcript = bool(parts)

    # Second pass: everything else, one file at a time.
    for position, obj in enumerate(event.objects):
        # SOURCE_METADATA describes the item rather than being its content.
        if obj.role in (ObjectRole.TRANSCRIPT, ObjectRole.SOURCE_METADATA):
            continue
        if obj.role is ObjectRole.RECORDING and has_transcript:
            logger.info("extraction_items: %s not read, the event has a transcript", obj.object_key)
            continue
        if text := text_of_file(obj):
            parts.append((position, obj, text))

    if not parts:
        return []
    parts.sort(key=lambda part: part[0])
    content_files = [o for o in event.objects if o.role is not ObjectRole.SOURCE_METADATA]
    text = item_text([(obj, text) for _, obj, text in parts], headed=len(content_files) > 1)
    return [ExtractionItem(text=text, source_type=source_type, item_key=str(event.correlation_id))]
