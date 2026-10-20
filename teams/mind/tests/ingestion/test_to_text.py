from collections.abc import Callable
from enum import Enum
from typing import Any

import pytest

from mind.contracts.raw_object_event_enums import Modality
from mind.ingestion.to_text import RETRY_REASONS, SKIP_REASONS, MediaUnavailable, text_of
from mind.multimodal.asr import ASRFailureReason, ASRResult
from mind.multimodal.ocr import OCRFailureReason, OCRResult

SCAN_KEY = "ZOOM/t/2026-10-20/quote.pdf"
RECORDING_KEY = "ZOOM/t/2026-10-20/meeting.wav"


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


def _scan(ocr: Any) -> str | None:
    return text_of(b"%PDF", Modality.SCANNED_DOC, SCAN_KEY, ocr, ASR_OK)


def _recording(asr: Any) -> str | None:
    return text_of(b"RIFF", Modality.AUDIO, RECORDING_KEY, OCR_OK, asr)


def test_each_modality_goes_its_own_way() -> None:
    assert text_of(b"plain text", Modality.TEXT, "k", OCR_OK, ASR_OK) == "plain text"
    assert _scan(OCR_OK) == "price quote"
    assert _recording(ASR_OK) == "we will send the quote"


def test_the_pipelines_get_the_bytes_and_the_file_name() -> None:
    ocr = _returns(OCRResult(success=True, text="x"))

    _scan(ocr)

    assert ocr.calls == [(b"%PDF", "quote.pdf")]  # type: ignore[attr-defined]


def test_text_that_is_not_utf8_is_skipped() -> None:
    assert text_of(b"\xff\xfe\xfa", Modality.TEXT, "k", OCR_OK, ASR_OK) is None


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
def test_a_failure_that_retrying_cannot_fix_skips_the_file(
    failure: OCRResult, caplog: pytest.LogCaptureFixture
) -> None:
    assert _scan(_returns(failure)) is None
    assert "quote.pdf" in caplog.text


def test_a_recording_the_asr_failed_on_is_skipped(caplog: pytest.LogCaptureFixture) -> None:
    failed = ASRResult(success=False, reason=ASRFailureReason.ASR_FAILED, error="decoder crashed")

    assert _recording(_returns(failed)) is None
    assert "meeting.wav" in caplog.text


@pytest.mark.parametrize(
    ("convert", "failure"),
    [
        (_recording, ASRResult(success=False, reason=ASRFailureReason.ASR_UNAVAILABLE, error="x")),
        (_scan, OCRResult(success=False, reason=OCRFailureReason.OCR_UNAVAILABLE, error="x")),
    ],
)
def test_a_missing_model_or_binary_stops_the_item_so_it_is_retried(
    convert: Callable[[Any], str | None], failure: Any
) -> None:
    with pytest.raises(MediaUnavailable):
        convert(_returns(failure))


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

    assert _scan(ocr) == "price quote"
    assert len(ocr.calls) == 2  # type: ignore[attr-defined]


def test_a_failure_of_the_file_itself_is_not_tried_again() -> None:
    ocr = _returns(OCRResult(success=False, reason=OCRFailureReason.NO_TEXT_RECOGNIZED, error="x"))

    assert _scan(ocr) is None
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
    assert _scan(_returns(_UndecidedFailure())) is None
    assert "undecided" in caplog.text
