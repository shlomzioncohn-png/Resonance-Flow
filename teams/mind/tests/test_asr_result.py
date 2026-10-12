"""ASRResult's shape, confidence and failure classification (src/mind/multimodal/asr.py).

ASR returns the same envelope as OCR (MediaTextResult, result.py), because R2's
consumer (#349) routes both into one extraction pipeline (#478). These tests need
no model weights, so they run in CI as well.
"""

from __future__ import annotations

import math

import pytest
from pydantic import ValidationError

from mind.multimodal.asr import (
    LOW_CONFIDENCE_THRESHOLD,
    WHISPER_LOG_PROB_THRESHOLD,
    ASRFailureReason,
    ASRResult,
    TranscriptSegment,
)
from mind.multimodal.ocr import OCRFailureReason, OCRResult
from mind.multimodal.result import MediaTextResult


def segment(text: str, confidence: float, start: float = 0.0, end: float = 1.0):
    return TranscriptSegment(
        text=text, start=start, end=end, confidence=confidence, no_speech_prob=0.01
    )


# --- one envelope for OCR and ASR ----------------------------------------------


def test_ocr_and_asr_share_one_envelope():
    assert issubclass(OCRResult, MediaTextResult)
    assert issubclass(ASRResult, MediaTextResult)


def test_reasons_with_the_same_meaning_have_the_same_name_in_both():
    shared = {"EMPTY_INPUT", "UNREADABLE_FORMAT", "INTERNAL_ERROR"}
    assert shared <= {r.value for r in OCRFailureReason}
    assert shared <= {r.value for r in ASRFailureReason}


def test_retryable_is_true_only_for_the_environment_reasons():
    environment = {ASRFailureReason.ASR_UNAVAILABLE, ASRFailureReason.ASR_FAILED}
    for reason in ASRFailureReason:
        result = ASRResult(success=False, reason=reason, error="e")
        assert result.retryable is (reason in environment), reason


def test_a_failed_result_must_carry_a_reason_and_a_message():
    with pytest.raises(ValidationError):
        ASRResult(success=False, error="something")  # no reason
    with pytest.raises(ValidationError):
        ASRResult(success=False, reason=ASRFailureReason.EMPTY_INPUT)  # no message


def test_a_successful_result_cannot_carry_a_reason():
    with pytest.raises(ValidationError):
        ASRResult(success=True, text="t", reason=ASRFailureReason.ASR_FAILED, error="e")


def test_an_ocr_reason_is_not_accepted_on_an_asr_result():
    with pytest.raises(ValidationError):
        ASRResult(success=False, reason=OCRFailureReason.OCR_FAILED, error="e")  # type: ignore[arg-type]


# --- confidence: Whisper's own score, passed through ------------------------------


def test_confidence_is_whispers_avg_logprob_out_of_log_space():
    built = TranscriptSegment.from_whisper(
        text=" Let's ship it on Monday.", start=1.5, end=3.0, avg_logprob=-0.25, no_speech_prob=0.02
    )
    assert built.confidence == pytest.approx(math.exp(-0.25))
    assert built.text == "Let's ship it on Monday."  # Whisper's leading space dropped
    assert (built.start, built.end, built.no_speech_prob) == (1.5, 3.0, 0.02)


def test_the_low_confidence_threshold_is_whispers_own_log_prob_threshold():
    # faster-whisper's default log_prob_threshold, the gate at which Whisper
    # itself re-decodes a segment -- not a cut-off we picked
    assert WHISPER_LOG_PROB_THRESHOLD == -1.0
    assert LOW_CONFIDENCE_THRESHOLD == pytest.approx(math.exp(-1.0))
    at_gate = TranscriptSegment.from_whisper(
        text="x", start=0, end=1, avg_logprob=-1.0, no_speech_prob=0
    )
    below = TranscriptSegment.from_whisper(
        text="x", start=0, end=1, avg_logprob=-1.01, no_speech_prob=0
    )
    assert at_gate.is_low_confidence is False
    assert below.is_low_confidence is True


def test_counts_and_mean_confidence_cover_every_segment():
    result = ASRResult(
        success=True,
        text="sure\nmumble\nok",
        segments=[segment("sure", 0.9), segment("mumble", 0.2), segment("ok", 0.7)],
        duration_s=3.0,
        duration_available_s=3.0,
    )
    assert result.mean_confidence == 0.6
    assert [s.text for s in result.low_confidence_segments] == ["mumble"]
    assert result.low_confidence_count == 1


def test_a_failure_has_no_mean_confidence():
    result = ASRResult(success=False, reason=ASRFailureReason.NO_SPEECH_DETECTED, error="silent")
    assert result.mean_confidence is None
    assert result.low_confidence_count == 0


def test_a_confidence_outside_zero_to_one_is_rejected():
    with pytest.raises(ValidationError):
        segment("x", 1.5)


# --- long recordings: truncated, not refused ------------------------------------


def test_a_recording_cut_at_the_cap_says_so():
    result = ASRResult(
        success=True,
        text="t",
        segments=[segment("t", 0.9)],
        duration_s=600.0,
        duration_available_s=1800.0,
    )
    assert result.truncated is True


def test_a_recording_under_the_cap_is_not_truncated():
    result = ASRResult(
        success=True,
        text="t",
        segments=[segment("t", 0.9)],
        duration_s=42.0,
        duration_available_s=42.0,
    )
    assert result.truncated is False


def test_a_serialized_result_includes_the_derived_fields():
    dumped = ASRResult(
        success=True,
        text="a\nb",
        segments=[segment("a", 0.9), segment("b", 0.1)],
        duration_s=10.0,
        duration_available_s=20.0,
    ).model_dump()
    assert dumped["mean_confidence"] == 0.5
    assert dumped["low_confidence_count"] == 1
    assert dumped["low_confidence_segments"][0]["text"] == "b"
    assert dumped["truncated"] is True
    assert dumped["retryable"] is False
