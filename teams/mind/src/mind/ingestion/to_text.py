"""One landed file's bytes -> its text (#349): `text_of`.

By `modality`: TEXT is decoded, SCANNED_DOC goes to R4's OCR, AUDIO to R4's
ASR. Which files of an event are converted, and in what order (transcripts
first), is extraction_items.py's.

A failure is decided by its reason, not only R4's category
(multimodal/failures.py), because a retry here restarts the process on the
same message and has no bound:
- OCR_UNAVAILABLE / ASR_UNAVAILABLE (the tool is missing here):
  MediaUnavailable is raised, so the item is not marked processed and is
  tried again once the environment is fixed.
- anything else -- DATA (the file is the problem), BUG (retrying fails the
  same way), and OCR_FAILED / ASR_FAILED (ENVIRONMENT in R4's categories, but
  the tool ran and failed, which can be this one file, so it is tried once
  more first): that file is logged and skipped, the rest of the event goes
  on. Retrying those would stop every item behind it for good. BUG ought to be
  parked in a dead-letter queue; there is none yet.
"""

import logging
from collections.abc import Callable
from enum import Enum
from pathlib import PurePosixPath
from typing import assert_never

from mind.contracts.raw_object_event_enums import Modality
from mind.multimodal.asr import ASRFailureReason
from mind.multimodal.failures import FailureCategory
from mind.multimodal.ocr import OCRFailureReason
from mind.multimodal.result import MediaTextResult

logger = logging.getLogger(__name__)

MediaPipeline = Callable[..., MediaTextResult]  # (content, *, filename) -> result

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


def text_of(
    content: bytes, modality: Modality, ref: str, ocr: MediaPipeline, asr: MediaPipeline
) -> str | None:
    """One file's text, or None when it is skipped (logged). `ref` names the
    file in logs, and its last segment is the filename hint OCR and ASR get."""

    match modality:
        case Modality.TEXT:
            try:
                return content.decode("utf-8")
            except UnicodeDecodeError:
                logger.warning("to_text: skipping %s, not UTF-8 text", ref)
                return None
        case Modality.SCANNED_DOC:
            pipeline = ocr
        case Modality.AUDIO:
            pipeline = asr
        case _:
            # A new Modality in the contract must get a branch here on purpose.
            assert_never(modality)

    filename = PurePosixPath(ref).name
    result = pipeline(content, filename=filename)
    if not result.success and result.reason in _TRY_ONCE_MORE:
        logger.info("to_text: %s failed (%s), trying once more", ref, result.reason)
        result = pipeline(content, filename=filename)
    if result.success:
        return result.text
    if result.reason in RETRY_REASONS:
        raise MediaUnavailable(f"{ref}: {result.reason} ({result.error})")
    if result.reason not in SKIP_REASONS:
        logger.error(
            "to_text: skipping %s, undecided failure reason %s (add it to RETRY_REASONS "
            "or SKIP_REASONS): %s",
            ref,
            result.reason,
            result.error,
        )
        return None
    # DATA is the file's fault; anything else may be ours or the host's, so louder.
    level = logging.WARNING if result.failure_category is FailureCategory.DATA else logging.ERROR
    logger.log(
        level,
        "to_text: skipping %s, %s failure %s: %s",
        ref,
        result.failure_category,
        result.reason,
        result.error,
    )
    return None
