"""Tests for benchmarks/aligned_metrics.py -- position-aligned CER.

The old char_similarity_score failed one property: appending garbage to the
OCR output *raised* its score (more output, higher score; no zero point).
The tests below pin the properties a real error metric must have -- a zero
point, a worst case, a penalty for extra output, and independence from
reading order -- so that failure can't come back unnoticed.
"""

import random
import string

import pytest
from benchmarks.aligned_metrics import WordBox, aligned_score, iou, levenshtein, match_boxes


def box(text: str, x: float, y: float = 0.0, w: float = 40.0, h: float = 10.0) -> WordBox:
    return WordBox(text=text, x0=x, y0=y, x1=x + w, y1=y + h)


def line_of_words() -> list[WordBox]:
    """A small 'document': 6 words in a row, 100px apart (no overlaps)."""
    words = ["invoice", "number", "5087", "street", "portland", "total"]
    return [box(w, x=i * 100) for i, w in enumerate(words)]


def far_away_garbage(n: int) -> list[WordBox]:
    rng = random.Random(0)
    return [
        box("".join(rng.choices(string.ascii_lowercase, k=6)), x=i * 100, y=500)
        for i in range(n)
    ]


def test_levenshtein_known_values():
    assert levenshtein("kitten", "sitting") == 3
    assert levenshtein("", "abc") == 3
    assert levenshtein("abc", "") == 3
    assert levenshtein("abc", "abc") == 0


def test_iou_identical_disjoint_and_partial():
    a = box("x", 0)
    assert iou(a, box("x", 0)) == 1.0
    assert iou(a, box("x", 1000)) == 0.0
    # half the width overlaps: inter = 20*10, union = 400 + 400 - 200
    assert iou(a, box("x", 20)) == pytest.approx(200 / 600)


def test_perfect_ocr_scores_zero_cer():
    gt = line_of_words()
    score = aligned_score(gt, gt)
    assert score.cer == 0.0
    assert score.word_precision == 1.0
    assert score.word_recall == 1.0
    assert (score.deletions, score.insertions) == (0, 0)


def test_empty_ocr_is_the_worst_case_of_exactly_one():
    gt = line_of_words()
    score = aligned_score(gt, [])
    assert score.cer == 1.0
    assert score.word_recall == 0.0
    assert score.deletions == len(gt)


def test_one_wrong_character_costs_one_edit():
    # "sireet" for "street" is a real Tesseract misread from the error analysis
    score = aligned_score([box("street", 0)], [box("sireet", 0)])
    assert score.char_edits == 1
    assert score.cer == pytest.approx(1 / 6)
    assert score.exact == 0  # not an exact word match...
    assert score.matched == 1  # ...but it was still paired by position


def test_reading_order_does_not_change_the_score():
    gt = line_of_words()
    ocr = [box("invoice", 0), box("nuvber", 100), box("5087", 200), box("street", 300)]
    shuffled = list(reversed(ocr))
    assert aligned_score(gt, ocr).cer == aligned_score(gt, shuffled).cer


def test_case_and_punctuation_are_ignored():
    score = aligned_score([box("Street,", 0)], [box("street", 0)])
    assert score.cer == 0.0


def test_extra_garbage_makes_cer_worse_never_better():
    # The property char_similarity_score violated: more output, higher score.
    gt = line_of_words()
    clean = aligned_score(gt, gt).cer
    some = aligned_score(gt, gt + far_away_garbage(5)).cer
    lots = aligned_score(gt, gt + far_away_garbage(50)).cer
    assert clean < some < lots


def test_pure_garbage_is_worse_than_saying_nothing():
    # Empty OCR scores exactly 1.0; garbage that matches nothing must score
    # above 1.0 (it adds insertions on top of the same deletions), and more
    # garbage must score higher -- the opposite of the 42% -> 48% -> 53% climb.
    gt = line_of_words()
    empty = aligned_score(gt, []).cer
    garbage_200 = aligned_score(gt, far_away_garbage(200)).cer
    garbage_1000 = aligned_score(gt, far_away_garbage(1000)).cer
    assert empty == 1.0
    assert garbage_200 > empty
    assert garbage_1000 > garbage_200


def test_slightly_shifted_box_still_matches():
    score = aligned_score([box("street", 0)], [box("street", 2)])
    assert score.matched == 1
    assert score.cer == 0.0


def test_far_shifted_box_is_a_deletion_plus_an_insertion():
    score = aligned_score([box("street", 0)], [box("street", 500)])
    assert score.matched == 0
    assert (score.deletions, score.insertions) == (1, 1)
    # 6 chars deleted + 6 inserted, over 6 ground-truth chars
    assert score.cer == pytest.approx(12 / 6)


def test_matching_is_one_to_one():
    # One wide OCR box overlaps two GT boxes equally. It may pair with only
    # one of them; the other must count as a deletion, not match twice.
    gt = [box("ab", 0), box("cd", 30)]
    ocr = [box("abcd", 0, w=70)]
    assert match_boxes(gt, ocr, 0.5) == [(0, 0)]
    score = aligned_score(gt, ocr)
    assert score.matched == 1
    assert score.deletions == 1


def test_empty_text_boxes_are_ignored():
    # FUNSD has annotation entries with empty text; they must not affect CER.
    gt = line_of_words()
    with_empty = gt + [box("", 700)]
    assert aligned_score(with_empty, gt).cer == 0.0


def test_empty_ground_truth_is_an_error_not_a_silent_zero():
    with pytest.raises(ValueError):
        aligned_score([box("", 0)], [box("x", 0)])
