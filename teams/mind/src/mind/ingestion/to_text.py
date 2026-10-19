"""One event's records -> the text extraction reads (#349).

By `modality`: TEXT is decoded, SCANNED_DOC goes to R4's OCR, AUDIO to R4's
ASR -- unless the event already carries a TRANSCRIPT, in which case its
RECORDING is not transcribed at all. That is decided per event, not per
record: the transcript is a record of its own. Only the RECORDING role is
skipped; other audio in the event (a voice note attached) has no transcript
and is transcribed.

A failure follows R4's categories (multimodal/failures.py):
- DATA (the file is the problem) and BUG (retrying fails the same way and
  would block every item behind it): that record is logged and skipped, the
  rest of the event goes on. BUG ought to be parked in a dead-letter queue;
  there is none yet.
- ENVIRONMENT (a model or binary is missing): MediaUnavailable is raised, so
  the item is not marked processed and is tried again.

Known gap, chosen deliberately: when an event has a transcript that turns out
unreadable, its recording is still not transcribed, so the meeting yields no
text. Rare -- the transcript comes from Bridge or Zoom, not a user upload --
and logged when it happens.
"""

import logging
from collections.abc import Callable
from pathlib import PurePosixPath

from mind.contracts.raw_object_event_enums import Modality, ObjectRole
from mind.ingestion.normalized_record import NormalizedRecord
from mind.multimodal.asr import transcribe
from mind.multimodal.failures import FailureCategory
from mind.multimodal.ocr import extract_text
from mind.multimodal.result import MediaTextResult

logger = logging.getLogger(__name__)

MediaPipeline = Callable[..., MediaTextResult]  # (content, *, filename) -> result


class MediaUnavailable(Exception):
    """OCR or ASR could not run here; the item should be retried later."""


def to_texts(
    records: list[NormalizedRecord],
    *,
    ocr: MediaPipeline = extract_text,
    asr: MediaPipeline = transcribe,
) -> list[tuple[NormalizedRecord, str]]:
    has_transcript = any(r.role is ObjectRole.TRANSCRIPT for r in records)
    texts = []
    for record in records:
        if record.role is ObjectRole.RECORDING and has_transcript:
            logger.info(
                "to_text: %s not transcribed, the event has a transcript", record.object_ref
            )
            continue
        text = _text_of(record, ocr, asr)
        if text is not None and text.strip():
            texts.append((record, text))
        elif text is not None:
            logger.info("to_text: %s has no text in it, skipped", record.object_ref)
    return texts


def _text_of(record: NormalizedRecord, ocr: MediaPipeline, asr: MediaPipeline) -> str | None:
    if record.modality is Modality.TEXT:
        try:
            return record.content.decode("utf-8")
        except UnicodeDecodeError:
            logger.warning("to_text: skipping %s, not UTF-8 text", record.object_ref)
            return None

    pipeline = ocr if record.modality is Modality.SCANNED_DOC else asr
    result = pipeline(record.content, filename=PurePosixPath(record.object_ref).name)
    if result.success:
        return result.text
    if result.failure_category is FailureCategory.ENVIRONMENT:
        raise MediaUnavailable(f"{record.object_ref}: {result.reason} ({result.error})")
    logger.warning(
        "to_text: skipping %s, %s failure %s: %s",
        record.object_ref,
        result.failure_category,
        result.reason,
        result.error,
    )
    return None
