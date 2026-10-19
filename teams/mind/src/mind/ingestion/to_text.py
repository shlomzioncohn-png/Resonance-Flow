"""One event's records -> the text extraction reads (#349).

By `modality`: TEXT is decoded, SCANNED_DOC goes to R4's OCR, AUDIO to R4's
ASR -- unless the event already carries a TRANSCRIPT, in which case its
RECORDING is not transcribed at all. That is decided per event, not per
record: the transcript is a record of its own. Only the RECORDING role is
skipped; other audio in the event (a voice note attached) has no transcript
and is transcribed.

A failure is decided by its reason, not only R4's category
(multimodal/failures.py), because a retry here restarts the process on the
same message and has no bound:
- OCR_UNAVAILABLE / ASR_UNAVAILABLE (the tool is missing here):
  MediaUnavailable is raised, so the item is not marked processed and is
  tried again once the environment is fixed.
- anything else -- DATA (the file is the problem), BUG (retrying fails the
  same way), and OCR_FAILED / ASR_FAILED (ENVIRONMENT in R4's categories, but
  the tool ran and failed, which can be this one file): that record is logged
  and skipped, the rest of the event goes on. Retrying those would stop every
  item behind it for good. BUG ought to be parked in a dead-letter queue;
  there is none yet.

Known gap, chosen deliberately: when an event has a transcript that turns out
unreadable, its recording is still not transcribed, so the meeting yields no
text. Rare -- the transcript comes from Bridge or Zoom, not a user upload --
and logged when it happens.
"""

import logging
from collections.abc import Callable
from enum import Enum
from pathlib import PurePosixPath

from mind.contracts.raw_object_event_enums import Modality, ObjectRole
from mind.ingestion.normalized_record import NormalizedRecord
from mind.multimodal.asr import ASRFailureReason, transcribe
from mind.multimodal.failures import FailureCategory
from mind.multimodal.ocr import OCRFailureReason, extract_text
from mind.multimodal.result import MediaTextResult

logger = logging.getLogger(__name__)

MediaPipeline = Callable[..., MediaTextResult]  # (content, *, filename) -> result
Texts = list[tuple[NormalizedRecord, str]]  # each record with the text read from it

# The reasons are R4's (multimodal/ocr.py, asr.py); what to do with each is
# ours, and a test fails until a new reason is placed in exactly one of these.
# Tuples, not sets: the reasons are str enums, and a set would fold OCR's
# EMPTY_INPUT and ASR's EMPTY_INPUT into one.
# Retry the item only when the tool itself is missing here.
RETRY_REASONS: tuple[Enum, ...] = (
    OCRFailureReason.OCR_UNAVAILABLE,
    ASRFailureReason.ASR_UNAVAILABLE,
)
SKIP_REASONS: tuple[Enum, ...] = (
    OCRFailureReason.EMPTY_INPUT,
    OCRFailureReason.UNREADABLE_FORMAT,
    OCRFailureReason.NO_TEXT_RECOGNIZED,
    OCRFailureReason.OCR_FAILED,
    OCRFailureReason.INTERNAL_ERROR,
    ASRFailureReason.EMPTY_INPUT,
    ASRFailureReason.UNREADABLE_FORMAT,
    ASRFailureReason.NO_SPEECH_DETECTED,
    ASRFailureReason.ASR_FAILED,
    ASRFailureReason.INTERNAL_ERROR,
)
# The tool ran and failed: maybe the file, maybe a busy host. One more try
# tells a flaky run from a bad file, without the unbounded retry of a restart.
_TRY_ONCE_MORE: tuple[Enum, ...] = (OCRFailureReason.OCR_FAILED, ASRFailureReason.ASR_FAILED)


class MediaUnavailable(Exception):
    """OCR or ASR could not run here; the item should be retried later."""


def to_texts(
    records: list[NormalizedRecord],
    *,
    ocr: MediaPipeline = extract_text,
    asr: MediaPipeline = transcribe,
) -> Texts:
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
    filename = PurePosixPath(record.object_ref).name
    result = pipeline(record.content, filename=filename)
    if not result.success and result.reason in _TRY_ONCE_MORE:
        logger.info("to_text: %s failed (%s), trying once more", record.object_ref, result.reason)
        result = pipeline(record.content, filename=filename)
    if result.success:
        return result.text
    if result.reason in RETRY_REASONS:
        raise MediaUnavailable(f"{record.object_ref}: {result.reason} ({result.error})")
    if result.reason not in SKIP_REASONS:
        logger.error(
            "to_text: skipping %s, undecided failure reason %s (add it to RETRY_REASONS "
            "or SKIP_REASONS): %s",
            record.object_ref,
            result.reason,
            result.error,
        )
        return None
    # DATA is the file's fault; anything else may be ours or the host's, so louder.
    level = logging.WARNING if result.failure_category is FailureCategory.DATA else logging.ERROR
    logger.log(
        level,
        "to_text: skipping %s, %s failure %s: %s",
        record.object_ref,
        result.failure_category,
        result.reason,
        result.error,
    )
    return None
