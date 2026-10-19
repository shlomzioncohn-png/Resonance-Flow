import logging
from collections.abc import Callable
from enum import Enum
from typing import Any
from uuid import UUID

import pytest

from mind.contracts.raw_object_event_enums import Modality, ObjectRole, RawSourceType
from mind.ingestion.normalized_record import NormalizedRecord
from mind.ingestion.to_text import RETRY_REASONS, SKIP_REASONS, MediaUnavailable, to_texts
from mind.multimodal.asr import ASRFailureReason, ASRResult
from mind.multimodal.ocr import OCRFailureReason, OCRResult

HASH = "sha256:" + "a" * 64


def _record(role: ObjectRole, modality: Modality, content: bytes, name: str) -> NormalizedRecord:
    return NormalizedRecord(
        object_ref=f"minio://ZOOM/t/2026-10-18/{name}",
        content=content,
        content_hash=HASH,
        content_type=None,
        modality=modality,
        role=role,
        source_type=RawSourceType.ZOOM,
        tenant_id=UUID("550e8400-e29b-41d4-a716-446655440000"),
        source_ref=None,
        source_timestamp=None,
        participants=None,
        correlation_id=None,
        revision=None,
    )


RECORDING = _record(ObjectRole.RECORDING, Modality.AUDIO, b"RIFF", "meeting.wav")
TRANSCRIPT = _record(ObjectRole.TRANSCRIPT, Modality.TEXT, b"WEBVTT ready", "meeting.vtt")
SCAN = _record(ObjectRole.ATTACHMENT, Modality.SCANNED_DOC, b"%PDF", "quote.pdf")


def _returns(result: Any) -> Callable[..., Any]:
    def pipeline(content: bytes, *, filename: str = "") -> Any:
        pipeline.calls.append((content, filename))  # type: ignore[attr-defined]
        return result

    pipeline.calls = []  # type: ignore[attr-defined]
    return pipeline


def _returns_in_turn(*results: Any) -> Callable[..., Any]:
    def pipeline(content: bytes, *, filename: str = "") -> Any:
        pipeline.calls.append((content, filename))  # type: ignore[attr-defined]
        return results[len(pipeline.calls) - 1]  # type: ignore[attr-defined]

    pipeline.calls = []  # type: ignore[attr-defined]
    return pipeline


OCR_OK = _returns(OCRResult(success=True, text="price quote"))
ASR_OK = _returns(ASRResult(success=True, text="we will send the quote"))


def _texts(records: list[NormalizedRecord], ocr: Any = OCR_OK, asr: Any = ASR_OK) -> list[str]:
    return [text for _, text in to_texts(records, ocr=ocr, asr=asr)]


def test_each_modality_goes_its_own_way() -> None:
    texts = _texts([RECORDING, SCAN, TRANSCRIPT.model_copy(update={"role": ObjectRole.BODY})])

    assert texts == ["we will send the quote", "price quote", "WEBVTT ready"]


def test_a_transcript_in_the_event_means_the_recording_is_not_transcribed() -> None:
    asr = _returns(ASRResult(success=True, text="should not run"))

    assert _texts([RECORDING, TRANSCRIPT], asr=asr) == ["WEBVTT ready"]
    assert asr.calls == []  # type: ignore[attr-defined]


def test_a_transcript_replaces_only_the_recording_not_other_audio(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO)
    voice_note = _record(ObjectRole.ATTACHMENT, Modality.AUDIO, b"OggS", "note.ogg")

    assert _texts([RECORDING, TRANSCRIPT, voice_note]) == [
        "WEBVTT ready",
        "we will send the quote",
    ]
    assert "meeting.wav" in caplog.text


def test_the_pipelines_get_the_bytes_and_the_file_name() -> None:
    ocr = _returns(OCRResult(success=True, text="x"))

    _texts([SCAN], ocr=ocr)

    assert ocr.calls == [(b"%PDF", "quote.pdf")]  # type: ignore[attr-defined]


@pytest.mark.parametrize(
    "failure",
    [
        # DATA: the file itself is the problem.
        OCRResult(success=False, reason=OCRFailureReason.NO_TEXT_RECOGNIZED, error="blank"),
        # BUG: retrying fails the same way and would block every item behind it.
        OCRResult(success=False, reason=OCRFailureReason.INTERNAL_ERROR, error="boom"),
        # The tool ran and failed (a page past its timeout): ENVIRONMENT in R4's
        # categories, but it can be this one file, so a retry may never pass.
        OCRResult(success=False, reason=OCRFailureReason.OCR_FAILED, error="timeout"),
    ],
)
def test_a_failure_that_retrying_cannot_fix_skips_only_that_record(
    failure: OCRResult, caplog: pytest.LogCaptureFixture
) -> None:
    assert _texts([SCAN, TRANSCRIPT], ocr=_returns(failure)) == ["WEBVTT ready"]
    assert "quote.pdf" in caplog.text


def test_a_recording_the_asr_failed_on_is_skipped(caplog: pytest.LogCaptureFixture) -> None:
    failed = ASRResult(success=False, reason=ASRFailureReason.ASR_FAILED, error="decoder crashed")

    assert _texts([RECORDING, SCAN], asr=_returns(failed)) == ["price quote"]
    assert "meeting.wav" in caplog.text


def test_text_that_is_not_utf8_is_skipped() -> None:
    broken = TRANSCRIPT.model_copy(update={"content": b"\xff\xfe\xfa"})

    assert _texts([broken, SCAN]) == ["price quote"]


def test_a_record_with_no_text_in_it_is_skipped() -> None:
    blank_body = TRANSCRIPT.model_copy(update={"role": ObjectRole.BODY, "content": b"  \n"})

    assert _texts([blank_body, SCAN]) == ["price quote"]


@pytest.mark.parametrize(
    ("records", "pipeline"),
    [
        (
            [RECORDING],
            {"asr": ASRResult(success=False, reason=ASRFailureReason.ASR_UNAVAILABLE, error="x")},
        ),
        (
            [SCAN],
            {"ocr": OCRResult(success=False, reason=OCRFailureReason.OCR_UNAVAILABLE, error="x")},
        ),
    ],
)
def test_a_missing_model_or_binary_stops_the_item_so_it_is_retried(
    records: list[NormalizedRecord], pipeline: dict[str, Any]
) -> None:
    with pytest.raises(MediaUnavailable):
        _texts(records, **{name: _returns(result) for name, result in pipeline.items()})


def test_every_ocr_and_asr_failure_reason_has_exactly_one_decision() -> None:
    # The reasons are R4's (multimodal/). A new one must be placed here on
    # purpose, retry or skip, rather than fall into skip unnoticed. Counted by
    # identity: they are str enums, so OCR's EMPTY_INPUT == ASR's EMPTY_INPUT.
    decided = [*RETRY_REASONS, *SKIP_REASONS]

    for reason in [*OCRFailureReason, *ASRFailureReason]:
        assert sum(reason is d for d in decided) == 1, reason


def test_a_tool_failure_is_tried_once_more_and_may_pass() -> None:
    # OCR_FAILED can be a timeout on a busy host, not the file.
    ocr = _returns_in_turn(
        OCRResult(success=False, reason=OCRFailureReason.OCR_FAILED, error="timeout"),
        OCRResult(success=True, text="price quote"),
    )

    assert _texts([SCAN], ocr=ocr) == ["price quote"]
    assert len(ocr.calls) == 2  # type: ignore[attr-defined]


def test_a_failure_of_the_file_itself_is_not_tried_again() -> None:
    ocr = _returns(OCRResult(success=False, reason=OCRFailureReason.NO_TEXT_RECOGNIZED, error="x"))

    assert _texts([SCAN], ocr=ocr) == []
    assert len(ocr.calls) == 1  # type: ignore[attr-defined]


class _NewReason(str, Enum):
    GPU_MELTED = "GPU_MELTED"  # one R4 might add before Mind decides on it


class _UndecidedFailure:
    success = False
    text = ""
    reason = _NewReason.GPU_MELTED
    error = "no decision for this one"
    failure_category = None


def test_a_reason_without_a_decision_is_skipped_and_named(
    caplog: pytest.LogCaptureFixture,
) -> None:
    assert _texts([SCAN, TRANSCRIPT], ocr=_returns(_UndecidedFailure())) == ["WEBVTT ready"]
    assert "undecided" in caplog.text
