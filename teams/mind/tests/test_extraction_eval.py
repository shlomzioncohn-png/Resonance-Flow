"""Tests for mind.evaluation.extraction_eval.

Covers the cases named in the issue — perfect match, a false positive, a missed
item, empty input on either side (no division by zero) — plus the hand-built
fixture, the greedy one-to-one rule, deterministic tie-breaking, normalization,
and that `threshold` is a real knob.

Most tests use an exact-match similarity (`_exact`, 1.0 iff the normalized
strings are equal) with `threshold=1.0`, so the expected counts are unambiguous.
The fixture test and the paraphrase test use the real `placeholder_similarity`.
"""

import itertools
import math

import pytest
from pydantic import ValidationError

from mind.evaluation.extraction_eval import (
    _normalize,
    evaluate_extraction,
    placeholder_similarity,
)
from mind.evaluation.extraction_fixture import (
    FIXTURE_ANNOTATED,
    FIXTURE_EXPECTED_F1,
    FIXTURE_EXPECTED_PRECISION,
    FIXTURE_EXPECTED_RECALL,
    FIXTURE_EXTRACTED,
    FIXTURE_THRESHOLD,
)
from mind.evaluation.run_extraction_eval import _load_input
from mind.evaluation.similarity import LEXICAL_THRESHOLD, lexical_similarity


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
        similarity=placeholder_similarity,
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
        similarity=placeholder_similarity,
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
        extracted, annotated, similarity=placeholder_similarity, threshold=0.7
    )
    above = evaluate_extraction(
        extracted, annotated, similarity=placeholder_similarity, threshold=0.8
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


def test_non_breaking_space_is_collapsed_like_a_regular_space():
    assert _normalize("The team will\xa0meet next\xa0\xa0Tuesday.") == "the team will meet next tuesday"


def test_zero_width_space_is_not_treated_as_whitespace():
    # Documented in the README: `str.split()` does not split on U+200B, so it survives.
    assert _normalize("a\u200bb") == "a\u200bb"


def test_matching_is_insensitive_to_formatting():
    report = evaluate_extraction(
        ["  the TASK is   done.  "],
        ["The task is done"],
        similarity=_exact,
        threshold=1.0,
    )

    assert report.true_positives == 1


# --- a bad similarity or threshold must fail loudly, never score ----------------------

_BAD_VALUES = [
    pytest.param(float("nan"), id="nan"),
    pytest.param(math.inf, id="inf"),
    pytest.param(-math.inf, id="-inf"),
    pytest.param(1.5, id="above-1"),
    pytest.param(-0.1, id="below-0"),
    pytest.param(None, id="none"),
    pytest.param("0.9", id="string"),
    pytest.param(True, id="bool"),
]


@pytest.mark.parametrize("bad", _BAD_VALUES)
def test_invalid_similarity_score_is_rejected(bad):
    # Regression for the NaN case: this used to return matched=1, precision=recall=1.0.
    with pytest.raises(ValueError, match=r"similarity\('task a', 'task a'\) must be"):
        evaluate_extraction(
            ["task a"], ["task a"], similarity=lambda _a, _b: bad, threshold=0.6
        )


@pytest.mark.parametrize("bad", _BAD_VALUES)
def test_invalid_threshold_is_rejected(bad):
    with pytest.raises(ValueError, match="threshold must be"):
        evaluate_extraction(["a"], ["a"], similarity=_exact, threshold=bad)


def test_one_bad_score_among_good_ones_still_fails():
    # Only one pair misbehaves; the run must not quietly score around it.
    def flaky(a: str, b: str) -> float:
        return float("nan") if (a, b) == ("x y z", "q r s") else _exact(a, b)

    with pytest.raises(ValueError, match="x y z"):
        evaluate_extraction(
            ["a b c", "x y z"], ["a b c", "q r s"], similarity=flaky, threshold=0.6
        )


def test_boundary_scores_and_integer_scores_are_accepted():
    for score in (0, 1, 0.0, 1.0):
        evaluate_extraction(
            ["a"], ["b"], similarity=lambda _a, _b, s=score: s, threshold=0.5
        )
    for threshold in (0, 1, 0.0, 1.0):
        evaluate_extraction(["a"], ["a"], similarity=_exact, threshold=threshold)


@pytest.mark.parametrize("shape", [(1, 1), (1, 2), (2, 1), (2, 2), (2, 3)])
def test_every_valid_score_grid_satisfies_the_matching_invariants(shape):
    """Exhaustive over small shapes: every grid of valid scores yields a coherent report."""
    n_extracted, n_annotated = shape
    extracted = [f"e{i}" for i in range(n_extracted)]
    annotated = [f"a{j}" for j in range(n_annotated)]
    cells = [(e, a) for e in extracted for a in annotated]

    for scores in itertools.product([0.0, 0.3, 0.6, 1.0], repeat=len(cells)):
        grid = dict(zip(cells, scores, strict=True))
        for threshold in (0.0, 0.6, 1.0):
            report = evaluate_extraction(
                extracted,
                annotated,
                similarity=lambda a, b, grid=grid: grid[(a, b)],
                threshold=threshold,
            )
            tp = report.true_positives
            assert tp <= min(n_extracted, n_annotated)
            assert 0.0 <= report.precision <= 1.0
            assert 0.0 <= report.recall <= 1.0
            assert 0.0 <= report.f1 <= 1.0
            assert all(pair.similarity >= threshold for pair in report.matched)
            # one-to-one, and every item is accounted for exactly once
            assert len({pair.extracted for pair in report.matched}) == tp
            assert len({pair.annotated for pair in report.matched}) == tp
            assert tp + len(report.false_positives) == n_extracted
            assert tp + len(report.missed) == n_annotated


def test_fixture_scores_the_same_with_the_clis_lexical_backend():
    # The CLI runs the fixture with lexical_similarity at its locked tau, not the
    # Jaccard placeholder the fixture's arithmetic was worked out for.
    report = evaluate_extraction(
        FIXTURE_EXTRACTED,
        FIXTURE_ANNOTATED,
        similarity=lexical_similarity,
        threshold=LEXICAL_THRESHOLD,
    )

    assert report.true_positives == 3
    assert (len(report.false_positives), len(report.missed)) == (1, 1)
    assert report.precision == pytest.approx(FIXTURE_EXPECTED_PRECISION)
    assert report.recall == pytest.approx(FIXTURE_EXPECTED_RECALL)


# --- the report is a pydantic model ---------------------------------------------------


def test_report_dump_includes_counts_and_computed_true_positives():
    report = evaluate_extraction(
        FIXTURE_EXTRACTED,
        FIXTURE_ANNOTATED,
        similarity=placeholder_similarity,
        threshold=FIXTURE_THRESHOLD,
    )
    dumped = report.model_dump()

    assert dumped["true_positives"] == 3
    assert dumped["n_extracted"] == len(FIXTURE_EXTRACTED)
    assert dumped["n_annotated"] == len(FIXTURE_ANNOTATED)
    assert dumped["matched"][0].keys() == {"extracted", "annotated", "similarity"}
    assert {"precision", "recall", "f1", "missed", "false_positives"} <= dumped.keys()


def test_report_counts_are_the_denominators_of_precision_and_recall():
    report = evaluate_extraction(["a", "b", "c"], ["a"], similarity=_exact, threshold=1.0)

    assert (report.n_extracted, report.n_annotated) == (3, 1)
    assert report.precision == pytest.approx(report.true_positives / report.n_extracted)
    assert report.recall == pytest.approx(report.true_positives / report.n_annotated)


def test_report_is_immutable():
    report = evaluate_extraction(["a"], ["a"], similarity=_exact, threshold=1.0)

    with pytest.raises(ValidationError):
        report.precision = 0.0  # type: ignore[misc]


# --- the CLI's --input file ---------------------------------------------------------


def test_input_file_loads_two_string_lists(tmp_path):
    path = tmp_path / "pair.json"
    path.write_text('{"extracted": ["a"], "annotated": ["b", "c"]}', encoding="utf-8")

    loaded = _load_input(path)

    assert loaded.extracted == ["a"]
    assert loaded.annotated == ["b", "c"]


@pytest.mark.parametrize(
    "payload, expected_fragment",
    [
        ('{"extracted": ["a"]}', "annotated"),  # missing key
        ('{"extracted": ["a", 2], "annotated": []}', "extracted.1"),  # non-string item
        ('{"extracted": [], "annotated": [], "extra": 1}', "extra"),  # unknown key
        ('{"extracted": "a", "annotated": []}', "extracted"),  # not a list
        ("not json", "json"),
        ("[]", "should be an object"),  # wrong top-level shape
    ],
)
def test_bad_input_file_names_the_problem(tmp_path, payload, expected_fragment):
    path = tmp_path / "pair.json"
    path.write_text(payload, encoding="utf-8")

    with pytest.raises(ValueError, match=expected_fragment):
        _load_input(path)
