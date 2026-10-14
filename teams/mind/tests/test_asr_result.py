"""ASRResult's shape, confidence and failure classification (src/mind/multimodal/asr.py).

ASR returns the same envelope as OCR (MediaTextResult, result.py) and the same
failure vocabulary (failures.py), because R2's consumer (#349) routes both into one
extraction pipeline (#478). These tests need no model weights, so they run in CI
as well.
"""

from __future__ import annotations

import math
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from mind.multimodal import asr, ocr
from mind.multimodal.asr import (
    LOW_CONFIDENCE_THRESHOLD,
    WHISPER_LOG_PROB_THRESHOLD,
    ASRFailureReason,
    ASRResult,
    TranscriptSegment,
)
from mind.multimodal.failures import FailureCategory
from mind.multimodal.ocr import OCRFailureReason, OCRResult
from mind.multimodal.result import MediaTextResult


def segment(text: str, confidence: float, start: float = 0.0, end: float = 1.0):
    """A segment whose confidence is `confidence` (stored as Whisper's avg_logprob)."""
    return TranscriptSegment(
        text=text,
        start=start,
        end=end,
        avg_logprob=math.log(confidence),
        compression_ratio=1.5,
        no_speech_prob=0.01,
    )


def whisper_segment(
    text: str = "x",
    start: float = 0.0,
    end: float = 1.0,
    avg_logprob: float = -0.2,
    compression_ratio: float = 1.5,
    no_speech_prob: float = 0.01,
):
    """The fields of a faster-whisper Segment that from_whisper() reads."""
    return SimpleNamespace(
        text=text,
        start=start,
        end=end,
        avg_logprob=avg_logprob,
        compression_ratio=compression_ratio,
        no_speech_prob=no_speech_prob,
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


# --- one failure vocabulary: data / environment / bug (failures.py, R2 on #478) -----


@pytest.mark.parametrize(
    ("result_cls", "reasons"),
    [(ASRResult, ASRFailureReason), (OCRResult, OCRFailureReason)],
)
def test_every_reason_has_a_failure_category(result_cls, reasons):
    # a reason added without a category would make failure_category raise KeyError
    assert set(result_cls.FAILURE_CATEGORIES) == set(reasons)


_EXPECTED_CATEGORIES = [
    (ASRResult, ASRFailureReason.EMPTY_INPUT, FailureCategory.DATA),
    (ASRResult, ASRFailureReason.UNREADABLE_FORMAT, FailureCategory.DATA),
    (ASRResult, ASRFailureReason.NO_SPEECH_DETECTED, FailureCategory.DATA),
    (ASRResult, ASRFailureReason.ASR_UNAVAILABLE, FailureCategory.ENVIRONMENT),
    (ASRResult, ASRFailureReason.ASR_FAILED, FailureCategory.ENVIRONMENT),
    (ASRResult, ASRFailureReason.INTERNAL_ERROR, FailureCategory.BUG),
    (OCRResult, OCRFailureReason.EMPTY_INPUT, FailureCategory.DATA),
    (OCRResult, OCRFailureReason.UNREADABLE_FORMAT, FailureCategory.DATA),
    (OCRResult, OCRFailureReason.NO_TEXT_RECOGNIZED, FailureCategory.DATA),
    (OCRResult, OCRFailureReason.OCR_UNAVAILABLE, FailureCategory.ENVIRONMENT),
    (OCRResult, OCRFailureReason.OCR_FAILED, FailureCategory.ENVIRONMENT),
    (OCRResult, OCRFailureReason.INTERNAL_ERROR, FailureCategory.BUG),
]


@pytest.mark.parametrize(("result_cls", "reason", "category"), _EXPECTED_CATEGORIES)
def test_each_reason_is_in_its_category(result_cls, reason, category):
    result = result_cls(success=False, reason=reason, error="e")
    assert result.failure_category is category
    assert result.retryable is (category is FailureCategory.ENVIRONMENT)


def test_a_bug_and_a_bad_input_are_both_not_retryable_but_are_told_apart():
    # the gap failure_category closes: retryable=False alone meant both "drop it"
    # (DATA) and "park it" (BUG), which R2's consumer handles differently
    bad_input = ASRResult(success=False, reason=ASRFailureReason.UNREADABLE_FORMAT, error="e")
    bug = ASRResult(success=False, reason=ASRFailureReason.INTERNAL_ERROR, error="e")
    assert bad_input.retryable is False
    assert bug.retryable is False
    assert bad_input.failure_category is not bug.failure_category


def test_a_success_has_no_failure_category():
    assert ASRResult(success=True, text="t").failure_category is None
    assert OCRResult(success=True, text="t").failure_category is None


def test_the_category_is_serialized_for_the_consumer():
    dumped = ASRResult(success=False, reason=ASRFailureReason.ASR_FAILED, error="e").model_dump(
        mode="json"
    )
    assert dumped["failure_category"] == "ENVIRONMENT"
    assert dumped["retryable"] is True


def test_both_pipelines_use_the_one_shared_category_enum():
    assert asr.FailureCategory is FailureCategory
    assert ocr.FailureCategory is FailureCategory


# --- confidence: Whisper's own scores, passed through ------------------------------


def test_whispers_scores_are_passed_through_unchanged():
    built = TranscriptSegment.from_whisper(
        whisper_segment(
            text=" Let's ship it on Monday.",
            start=1.5,
            end=3.0,
            avg_logprob=-0.25,
            compression_ratio=2.7,
            no_speech_prob=0.02,
        )
    )
    assert built.text == "Let's ship it on Monday."  # Whisper's leading space dropped
    assert (built.start, built.end) == (1.5, 3.0)
    assert (built.avg_logprob, built.compression_ratio, built.no_speech_prob) == (-0.25, 2.7, 0.02)


def test_confidence_is_avg_logprob_out_of_log_space():
    built = TranscriptSegment.from_whisper(whisper_segment(avg_logprob=-0.25))
    assert built.confidence == pytest.approx(math.exp(-0.25))


def test_timestamps_are_offset_to_the_start_of_the_recording():
    built = TranscriptSegment.from_whisper(whisper_segment(start=1.0, end=2.5), offset_s=60.0)
    assert (built.start, built.end) == (61.0, 62.5)


def test_a_serialized_segment_carries_every_score_and_the_confidence():
    dumped = segment("x", 0.5).model_dump()
    assert set(dumped) == {
        "text",
        "start",
        "end",
        "avg_logprob",
        "compression_ratio",
        "no_speech_prob",
        "confidence",
    }
    assert dumped["confidence"] == pytest.approx(0.5)


def test_the_low_confidence_threshold_is_whispers_own_log_prob_threshold():
    # faster-whisper's default log_prob_threshold, the gate at which Whisper
    # itself re-decodes a segment -- not a cut-off we picked
    assert WHISPER_LOG_PROB_THRESHOLD == -1.0
    assert LOW_CONFIDENCE_THRESHOLD == pytest.approx(math.exp(-1.0))
    at_gate = TranscriptSegment.from_whisper(whisper_segment(avg_logprob=-1.0))
    below = TranscriptSegment.from_whisper(whisper_segment(avg_logprob=-1.01))
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


def test_mean_confidence_is_weighted_by_duration():
    # faster-whisper gives every segment of a ~30 s window that window's score, so a
    # window cut into many short segments must not outweigh one long segment
    result = ASRResult(
        success=True,
        text="long\nshort\nshort\nshort",
        segments=[
            segment("long", 0.9, start=0, end=30),
            segment("short", 0.3, start=30, end=31),
            segment("short", 0.3, start=31, end=32),
            segment("short", 0.3, start=32, end=33),
        ],
        duration_s=33.0,
        duration_available_s=33.0,
    )
    assert result.mean_confidence == pytest.approx((0.9 * 30 + 0.3 * 3) / 33, abs=1e-4)


def test_a_failure_has_no_mean_confidence():
    result = ASRResult(success=False, reason=ASRFailureReason.NO_SPEECH_DETECTED, error="silent")
    assert result.mean_confidence is None
    assert result.low_confidence_count == 0


def test_an_out_of_range_compression_ratio_or_no_speech_prob_is_rejected():
    with pytest.raises(ValidationError):
        TranscriptSegment(
            text="x", start=0, end=1, avg_logprob=-0.1, compression_ratio=-1, no_speech_prob=0
        )
    with pytest.raises(ValidationError):
        TranscriptSegment(
            text="x", start=0, end=1, avg_logprob=-0.1, compression_ratio=1, no_speech_prob=1.5
        )


# --- how much the transcript covers: two independent, stored flags ------------------
# (R2 on #478). Unlike OCR's truncated (pages_available > len(pages)), ASR's flags
# are set by transcribe() from what it did, not derived from the durations:
# derived, a broken file would look like a deliberate cut. How transcribe() sets
# them is tested in test_asr_pipeline.py.


def test_truncated_is_not_derived_from_the_durations():
    # a recording longer than what was transcribed, yet not cut by us: a broken file
    result = ASRResult(
        success=True,
        text="t",
        segments=[segment("t", 0.9)],
        duration_s=2.0,
        duration_available_s=4.0,
        incomplete_file=True,
    )
    assert result.truncated is False


@pytest.mark.parametrize(
    ("truncated", "incomplete_file"), [(False, False), (True, False), (False, True), (True, True)]
)
def test_the_two_flags_are_independent(truncated: bool, incomplete_file: bool):
    result = ASRResult(success=True, text="t", truncated=truncated, incomplete_file=incomplete_file)
    assert (result.truncated, result.incomplete_file) == (truncated, incomplete_file)


def test_a_serialized_result_includes_the_derived_and_coverage_fields():
    dumped = ASRResult(
        success=True,
        text="a\nb",
        segments=[segment("a", 0.9), segment("b", 0.1)],
        duration_s=10.0,
        duration_available_s=20.0,
        truncated=True,
    ).model_dump()
    assert dumped["mean_confidence"] == 0.5
    assert dumped["low_confidence_count"] == 1
    assert dumped["low_confidence_segments"][0]["text"] == "b"
    assert (dumped["truncated"], dumped["incomplete_file"]) == (True, False)
    assert dumped["duration_available_exact"] is False  # not verified unless set
    assert dumped["retryable"] is False
    assert dumped["failure_category"] is None
