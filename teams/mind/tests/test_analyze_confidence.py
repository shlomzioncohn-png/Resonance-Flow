"""Tests for benchmarks/analyze_confidence.py -- how misses are tallied.

The per-category counts in the OCR decision doc come from collect_confidences().
It used to key the misses by ground-truth word, so a word missed twice on a page
overwrote itself and a row was silently lost (11 of 98 on the FUNSD slice).
"""

from benchmarks.analyze_confidence import collect_confidences
from benchmarks.analyze_word_misses import MissRow


def miss(gt_word: str, ocr_brought: str, category: str = "char_misread") -> MissRow:
    return MissRow(
        doc="d", gt_word=gt_word, ocr_brought=ocr_brought, similarity=0.8, category=category  # type: ignore[arg-type]
    )


def test_a_word_missed_twice_keeps_both_rows():
    # form documents repeat words ("name", "5", ...); both misses must count
    misses = [miss("NAME", "me"), miss("NAME", "me")]
    _, by_category = collect_confidences(
        [("NAME", "name"), ("NAME", "name")], misses, {"me": [40.0]}
    )
    assert by_category["char_misread"] == [40.0, 40.0]


def test_each_miss_row_is_counted_in_its_own_category():
    misses = [miss("5", "15", "digit_merge"), miss("5", "12", "different_word")]
    _, by_category = collect_confidences(
        [("5", "5")], misses, {"15": [50.0], "12": [70.0]}
    )
    assert by_category == {"digit_merge": [50.0], "different_word": [70.0]}


def test_hits_exclude_words_that_were_missed_and_include_the_rest():
    gt = [("street", "street"), ("name", "name")]
    hits, _ = collect_confidences(gt, [miss("name", "me")], {"street": [90.0], "name": [10.0]})
    assert hits == [90.0]  # "name" is a miss, so its confidence is not a hit's


def test_a_miss_whose_brought_token_has_no_confidence_is_skipped_not_an_error():
    _, by_category = collect_confidences([("x", "x")], [miss("x", "zzz")], {"other": [1.0]})
    assert by_category == {}
