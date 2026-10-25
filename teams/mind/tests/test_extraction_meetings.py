"""Tests for `evaluate_meetings`: per-meeting grouping, micro/macro, failures (#178).

The outcomes are built as the extractor returns them (`ExtractionSuccess` /
`ExtractionFailure`, the meeting id in `item_key`). Matching uses an exact
similarity with `threshold=1.0`, so every count below is unambiguous.
"""

import pytest

from mind.contracts.base_enums import SourceType
from mind.evaluation.extraction_eval import evaluate_meetings
from mind.evaluation.run_extraction_eval import slice_summary_lines
from mind.extraction.confidence import ConfidenceBreakdown
from mind.extraction.extractor import (
    REASON_INVALID_OUTPUT,
    REASON_PROVIDER_ERROR,
    ExtractedTask,
    ExtractionFailure,
    ExtractionSuccess,
)
from mind.extraction.models import ExtractedFields


def _exact(a: str, b: str) -> float:
    return 1.0 if a == b else 0.0


def _success(meeting_id: str | None, *titles: str) -> ExtractionSuccess:
    return ExtractionSuccess(
        tasks=tuple(
            ExtractedTask(
                fields=ExtractedFields(title=title, has_dependencies=False),
                confidence=ConfidenceBreakdown(base=1.0, penalties=(), value=1.0),
            )
            for title in titles
        ),
        source_type=SourceType.MEETING,
        item_key=meeting_id,
        model="test",
        schema_attempts=1,
        provider_calls=1,
    )


def _failure(meeting_id: str, reason: str) -> ExtractionFailure:
    return ExtractionFailure(
        source_type=SourceType.MEETING,
        item_key=meeting_id,
        reason=reason,
        detail="test",
        schema_attempts=1,
        provider_calls=1,
        environmental=reason == REASON_PROVIDER_ERROR,
    )


def _evaluate(outcomes, gold):  # type: ignore[no-untyped-def]
    return evaluate_meetings(outcomes, gold, similarity=_exact, threshold=1.0)


def test_items_are_grouped_by_item_key_and_scored_per_meeting() -> None:
    report = _evaluate(
        [_success("m1", "a", "b", "x"), _success("m2", "c")],
        {"m1": ["a", "b"], "m2": ["c", "d", "e", "f"]},
    )

    m1, m2 = report.meetings
    assert (m1.meeting_id, m1.true_positives, m1.false_positives, m1.false_negatives) == ("m1", 2, 1, 0)
    assert (m2.meeting_id, m2.true_positives, m2.false_positives, m2.false_negatives) == ("m2", 1, 0, 3)
    assert m1.precision == pytest.approx(2 / 3)
    assert m2.recall == pytest.approx(1 / 4)


def test_micro_weighs_every_item_and_macro_every_meeting() -> None:
    report = _evaluate(
        [_success("m1", "a", "b", "x"), _success("m2", "c")],
        {"m1": ["a", "b"], "m2": ["c", "d", "e", "f"]},
    )

    # Micro: TP 3, FP 1, FN 3.
    assert report.micro_precision == pytest.approx(3 / 4)
    assert report.micro_recall == pytest.approx(3 / 6)
    assert report.micro_f1 == pytest.approx(2 * 0.75 * 0.5 / 1.25)
    # Macro: mean of (2/3, 1/1) and of (2/2, 1/4).
    assert report.macro_precision == pytest.approx((2 / 3 + 1.0) / 2)
    assert report.macro_recall == pytest.approx((1.0 + 0.25) / 2)


def test_outcomes_of_one_meeting_are_scored_together() -> None:
    report = _evaluate([_success("m1", "a"), _success("m1", "b")], {"m1": ["a", "b"]})

    assert report.meetings[0].true_positives == 2
    assert report.micro_recall == 1.0


def test_a_failed_meeting_counts_as_zero_items_not_skipped() -> None:
    report = _evaluate(
        [_success("m1", "a"), _failure("m2", REASON_PROVIDER_ERROR)],
        {"m1": ["a"], "m2": ["b", "c"]},
    )

    failed = report.meetings[1]
    assert failed.failed
    assert (failed.true_positives, failed.false_negatives) == (0, 2)
    assert failed.precision is None  # nothing extracted: undefined, not 0 or 1
    assert failed.recall == 0.0
    # Skipping m2 would have reported a perfect 1.0.
    assert report.micro_recall == pytest.approx(1 / 3)


def test_failures_are_listed_with_their_reason() -> None:
    report = _evaluate(
        [
            _failure("m1", REASON_PROVIDER_ERROR),
            _failure("m2", REASON_PROVIDER_ERROR),
            _failure("m3", REASON_INVALID_OUTPUT),
        ],
        {"m1": ["a"], "m2": ["b"], "m3": ["c"]},
    )

    assert [(f.meeting_id, f.reason) for f in report.failures] == [
        ("m1", REASON_PROVIDER_ERROR),
        ("m2", REASON_PROVIDER_ERROR),
        ("m3", REASON_INVALID_OUTPUT),
    ]
    assert report.failure_reasons == {REASON_PROVIDER_ERROR: 2, REASON_INVALID_OUTPUT: 1}
    assert "failure_reasons" in report.model_dump()


def test_undefined_meeting_metrics_are_none_and_left_out_of_macro() -> None:
    # m2 has no gold items and nothing extracted: no precision, no recall.
    report = _evaluate([_success("m1", "a"), _success("m2")], {"m1": ["a", "b"], "m2": []})

    empty = report.meetings[1]
    assert (empty.precision, empty.recall, empty.f1) == (None, None, None)
    assert report.macro_precision == 1.0
    assert report.macro_recall == pytest.approx(0.5)


def test_micro_is_none_when_nothing_was_extracted_anywhere() -> None:
    report = _evaluate([_success("m1")], {"m1": ["a"]})

    assert report.micro_precision is None
    assert report.micro_recall == 0.0
    assert report.micro_f1 is None


def test_an_outcome_without_item_key_is_rejected() -> None:
    with pytest.raises(ValueError, match="no item_key"):
        _evaluate([_success(None, "a")], {"m1": ["a"]})


def test_an_outcome_for_an_unknown_meeting_is_rejected() -> None:
    with pytest.raises(ValueError, match="'m9'"):
        _evaluate([_success("m1", "a"), _success("m9", "b")], {"m1": ["a"]})


def test_a_gold_meeting_with_no_outcome_is_rejected() -> None:
    with pytest.raises(ValueError, match=r"\['m2'\]"):
        _evaluate([_success("m1", "a")], {"m1": ["a"], "m2": ["b"]})


def test_summary_shows_failures_on_their_own_line() -> None:
    report = _evaluate(
        [_success("m1", "a"), _failure("m2", REASON_PROVIDER_ERROR)],
        {"m1": ["a"], "m2": ["b"]},
    )

    lines = slice_summary_lines(report).splitlines()
    assert lines[1].startswith("  Micro:")
    assert lines[2].startswith("  Macro:")
    assert lines[3] == (
        "  Failures:  1 outcome(s) in 1/2 meeting(s) (provider_error 1); "
        "counted as zero extracted items, not skipped"
    )


def test_summary_says_none_when_nothing_failed() -> None:
    report = _evaluate([_success("m1", "a")], {"m1": ["a"]})

    assert slice_summary_lines(report).splitlines()[-1] == "  Failures:  none"
