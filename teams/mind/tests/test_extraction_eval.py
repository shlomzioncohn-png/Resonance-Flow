"""Tests for mind.evaluation.extraction_eval.

Covers the cases named in the issue — perfect match, a false positive, a missed
item, empty input on either side (no division by zero) — plus the hand-built
fixture, the greedy one-to-one rule, deterministic tie-breaking, normalization,
and that `threshold` is a real knob.

Most tests use an exact-match similarity (`_exact`, 1.0 iff the normalized
strings are equal) with `threshold=1.0`, so the expected counts are unambiguous.
The fixture test and the paraphrase test use the real `default_similarity`.
"""

import pytest

from mind.evaluation.extraction_eval import (
    _normalize,
    default_similarity,
    evaluate_extraction,
)
from mind.evaluation.extraction_fixture import (
    FIXTURE_ANNOTATED,
    FIXTURE_EXPECTED_F1,
    FIXTURE_EXPECTED_PRECISION,
    FIXTURE_EXPECTED_RECALL,
    FIXTURE_EXTRACTED,
    FIXTURE_THRESHOLD,
)


def _exact(a: str, b: str) -> float:
    return 1.0 if a == b else 0.0


def _constant(_a: str, _b: str) -> float:
    return 0.9


# --- the four cases the issue names ------------------------------------------------


def test_perfect_match_is_precision_recall_one():
    items = [
        "The Industrial Designer will present the components concept.",
        "The Marketing Expert will present the marketing concept.",
        "The Project Manager will book a meeting room.",
    ]
    report = evaluate_extraction(items, items, similarity=_exact, threshold=1.0)

    assert report.precision == 1.0
    assert report.recall == 1.0
    assert report.f1 == 1.0
    assert report.true_positives == 3
    assert report.missed == []
    assert report.false_positives == []


def test_a_false_positive_lowers_precision_only():
    annotated = ["task a", "task b"]
    extracted = ["task a", "task b", "task c is a hallucination"]
    report = evaluate_extraction(extracted, annotated, similarity=_exact, threshold=1.0)

    assert report.precision == pytest.approx(2 / 3)
    assert report.recall == 1.0
    assert report.false_positives == ["task c is a hallucination"]
    assert report.missed == []
    assert report.f1 == pytest.approx(2 * (2 / 3) * 1.0 / ((2 / 3) + 1.0))


def test_a_missed_item_lowers_recall_below_one():
    annotated = ["task a", "task b", "task c"]
    extracted = ["task a", "task b"]
    report = evaluate_extraction(extracted, annotated, similarity=_exact, threshold=1.0)

    assert report.recall == pytest.approx(2 / 3)
    assert report.recall < 1.0
    assert report.precision == 1.0
    assert report.missed == ["task c"]
    assert report.false_positives == []


def test_empty_extracted_does_not_divide_by_zero():
    report = evaluate_extraction([], ["task a"], similarity=_exact, threshold=1.0)

    assert report.precision == 0.0
    assert report.recall == 0.0
    assert report.f1 == 0.0
    assert report.missed == ["task a"]
    assert report.matched == []


def test_empty_annotated_does_not_divide_by_zero():
    report = evaluate_extraction(["task a"], [], similarity=_exact, threshold=1.0)

    assert report.precision == 0.0
    assert report.recall == 1.0
    assert report.f1 == 0.0
    assert report.false_positives == ["task a"]


def test_both_sides_empty_is_vacuously_perfect():
    report = evaluate_extraction([], [], similarity=_exact, threshold=1.0)

    assert report.precision == 1.0
    assert report.recall == 1.0
    assert report.f1 == 1.0
    assert report.matched == []


# --- the hand-built fixture ------------------------------------------------------


def test_constructed_fixture_matches_hand_computed_numbers():
    report = evaluate_extraction(
        FIXTURE_EXTRACTED,
        FIXTURE_ANNOTATED,
        similarity=default_similarity,
        threshold=FIXTURE_THRESHOLD,
    )

    assert report.precision == pytest.approx(FIXTURE_EXPECTED_PRECISION)
    assert report.recall == pytest.approx(FIXTURE_EXPECTED_RECALL)
    assert report.f1 == pytest.approx(FIXTURE_EXPECTED_F1)
    assert report.true_positives == 3
    assert report.false_positives == ["Team to decide on the TV remote button layout"]
    assert report.missed == [
        "The User Interface Designer will present the user interface concept."
    ]


# --- the matching rule --------------------------------------------------------------


def test_paraphrase_matches_below_similarity_one():
    # Jaccard of the normalized token sets is 6/8 = 0.75 — a genuine fuzzy match,
    # not an exact one.
    report = evaluate_extraction(
        ["Industrial Designer to present the components concept"],
        ["The Industrial Designer will present the components concept."],
        similarity=default_similarity,
        threshold=0.5,
    )

    assert report.true_positives == 1
    assert report.matched[0].similarity == pytest.approx(0.75)


def test_greedy_matching_is_one_to_one():
    # One extracted item is similar to both annotations; one-to-one must score it
    # as 1 TP + 1 FN, never 2 TP.
    report = evaluate_extraction(
        ["seating preferences"],
        ["collect seating preferences", "collate seating preferences"],
        similarity=_constant,
        threshold=0.5,
    )

    assert report.true_positives == 1
    assert len(report.missed) == 1
    assert report.false_positives == []
    assert report.recall == pytest.approx(0.5)


def test_tie_break_is_deterministic():
    # Every pair scores equally; the tie-break (annotation index, then extracted
    # index) must pair e0-a0 and e1-a1, the same way on every run.
    args = (["e0", "e1"], ["a0", "a1"])
    kwargs = {"similarity": _constant, "threshold": 0.5}
    first = evaluate_extraction(*args, **kwargs)
    second = evaluate_extraction(*args, **kwargs)

    pairs = [(p.extracted, p.annotated) for p in first.matched]
    assert pairs == [("e0", "a0"), ("e1", "a1")]
    assert pairs == [(p.extracted, p.annotated) for p in second.matched]


def test_threshold_is_a_real_knob():
    extracted = ["Industrial Designer to present the components concept"]  # Jaccard 0.75
    annotated = ["The Industrial Designer will present the components concept."]

    below = evaluate_extraction(
        extracted, annotated, similarity=default_similarity, threshold=0.7
    )
    above = evaluate_extraction(
        extracted, annotated, similarity=default_similarity, threshold=0.8
    )

    assert below.true_positives == 1
    assert above.true_positives == 0
    assert above.false_positives == extracted
    assert above.missed == annotated


def test_similarity_and_threshold_are_required():
    with pytest.raises(TypeError):
        evaluate_extraction(["a"], ["a"])  # type: ignore[call-arg]


# --- normalization ----------------------------------------------------------------


def test_normalization_ignores_case_whitespace_and_trailing_period():
    assert _normalize("  The   Task  Is   Done. ") == "the task is done"


def test_matching_is_insensitive_to_formatting():
    report = evaluate_extraction(
        ["  the TASK is   done.  "],
        ["The task is done"],
        similarity=_exact,
        threshold=1.0,
    )

    assert report.true_positives == 1
