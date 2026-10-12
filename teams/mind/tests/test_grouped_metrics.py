"""Tests for grouped_score() in benchmarks/aligned_metrics.py -- many-to-one CER.

Strict one-to-one matching charges a merged or split word as a deletion plus
an insertion even when every character is right. Grouped scoring clusters
overlapping GT/OCR words and scores each cluster's concatenated text, so
tokenization differences are free but wrong, missing and extra characters are
not. These tests pin both halves of that, plus the properties the original
char_similarity_score failed (a zero point, a penalty for extra output).
"""

import random
import string

import pytest
from benchmarks.aligned_metrics import (
    WordBox,
    aligned_score,
    grouped_score,
    overlap_coefficient,
)


def box(text: str, x: float, y: float = 0.0, w: float = 40.0, h: float = 10.0) -> WordBox:
    return WordBox(text=text, x0=x, y0=y, x1=x + w, y1=y + h)


def line_of_words() -> list[WordBox]:
    words = ["invoice", "number", "5087", "street", "portland", "total"]
    return [box(w, x=i * 100) for i, w in enumerate(words)]


def far_away_garbage(n: int) -> list[WordBox]:
    rng = random.Random(0)
    return [
        box("".join(rng.choices(string.ascii_lowercase, k=6)), x=i * 100, y=500)
        for i in range(n)
    ]


def test_overlap_coefficient_is_one_when_a_box_sits_inside_another():
    assert overlap_coefficient(box("x", 0, w=100), box("x", 20, w=30)) == 1.0
    assert overlap_coefficient(box("x", 0), box("x", 1000)) == 0.0


def test_perfect_ocr_scores_zero_with_only_one_to_one_groups():
    gt = line_of_words()
    score = grouped_score(gt, gt)
    assert score.cer == 0.0
    assert score.merged_or_split_groups == 0
    assert (score.unmatched_gt, score.unmatched_ocr) == (0, 0)


def test_empty_ocr_is_the_worst_case_of_exactly_one():
    score = grouped_score(line_of_words(), [])
    assert score.cer == 1.0
    assert score.unmatched_gt == 6


def test_a_merged_word_with_correct_characters_costs_nothing():
    # the real FUNSD case: zip and plus-4 read as one token
    gt = [box("43215", 0), box("9428", 50)]
    ocr = [box("432159428", 0, w=90)]
    score = grouped_score(gt, ocr)
    assert score.cer == 0.0
    assert score.merged_or_split_groups == 1
    # strict one-to-one matching charges that same correct read as errors
    assert aligned_score(gt, ocr).cer > 0.0


def test_a_split_word_with_correct_characters_costs_nothing():
    gt = [box("432159428", 0, w=90)]
    ocr = [box("43215", 0), box("9428", 50)]
    assert grouped_score(gt, ocr).cer == 0.0


def test_a_wrong_character_inside_a_merge_is_still_counted():
    gt = [box("43215", 0), box("9428", 50)]
    ocr = [box("432159x28", 0, w=90)]
    score = grouped_score(gt, ocr)
    assert score.char_edits == 1
    assert score.cer == pytest.approx(1 / 9)


def test_scrambled_text_inside_a_merged_box_is_penalized():
    # grouped ignores word boundaries, not character order
    gt = [box("abc", 0), box("def", 50)]
    assert grouped_score(gt, [box("fedcba", 0, w=90)]).cer > 0.0


def test_missing_words_are_still_deletions():
    gt = line_of_words()
    ocr = gt[:4]
    score = grouped_score(gt, ocr)
    assert score.unmatched_gt == 2
    assert score.deleted_chars == len("portland") + len("total")
    assert score.cer == pytest.approx(score.deleted_chars / score.gt_chars)


def test_a_tall_ocr_box_still_groups_with_a_short_gt_box():
    # FUNSD vs Tesseract box heights differ by up to ~2x with centers aligned
    gt = [box("street", 0, y=20, h=8)]
    ocr = [box("street", 0, y=12, h=24)]
    score = grouped_score(gt, ocr)
    assert score.cer == 0.0
    assert score.one_to_one_groups == 1


def test_neighbouring_lines_do_not_chain_into_one_group():
    gt = [box("alpha", 0, y=0), box("beta", 0, y=14)]
    ocr = [box("alpha", 0, y=0), box("beta", 0, y=14)]
    score = grouped_score(gt, ocr)
    assert score.groups == 2
    assert score.largest_group_words == 2


def test_reading_order_does_not_change_the_score():
    gt = line_of_words()
    ocr = [box("invoice", 0), box("nuvber", 100), box("5087", 200), box("street", 300)]
    assert grouped_score(gt, ocr).cer == grouped_score(list(reversed(gt)), list(reversed(ocr))).cer


def test_extra_garbage_makes_cer_worse_never_better():
    gt = line_of_words()
    clean = grouped_score(gt, gt).cer
    some = grouped_score(gt, gt + far_away_garbage(5)).cer
    lots = grouped_score(gt, gt + far_away_garbage(50)).cer
    assert clean < some < lots


def test_pure_garbage_is_worse_than_saying_nothing():
    gt = line_of_words()
    empty = grouped_score(gt, []).cer
    assert empty == 1.0
    assert grouped_score(gt, far_away_garbage(200)).cer > empty
    assert grouped_score(gt, far_away_garbage(1000)).cer > grouped_score(gt, far_away_garbage(200)).cer


def test_replacing_a_correct_word_with_garbage_never_lowers_cer():
    gt = line_of_words()
    clean = grouped_score(gt, gt).cer
    ocr = list(gt)
    ocr[2] = box("zzzz", 200)
    assert grouped_score(gt, ocr).cer > clean


def test_case_punctuation_and_empty_text_boxes_are_ignored():
    gt = [box("Street,", 0), box("", 100)]
    assert grouped_score(gt, [box("street", 0)]).cer == 0.0


def test_empty_ground_truth_is_an_error_not_a_silent_zero():
    with pytest.raises(ValueError):
        grouped_score([box("", 0)], [box("x", 0)])


def test_list_order_of_words_inside_a_group_does_not_matter_only_position_does():
    # Same boxes, same text; only the order the words are *listed* in differs.
    # Concatenation must follow position (left to right), not list order.
    merged_gt_reversed = [box("9428", 50), box("43215", 0)]
    assert grouped_score(merged_gt_reversed, [box("432159428", 0, w=90)]).cer == 0.0
    split_ocr_reversed = [box("9428", 50), box("43215", 0)]
    assert grouped_score([box("432159428", 0, w=90)], split_ocr_reversed).cer == 0.0


def test_taller_ocr_boxes_do_not_merge_neighbouring_lines_into_one():
    # One group spanning two lines: GT has "ab" on the upper line (right side)
    # and "cd", "ef" on the lower line; OCR reads the upper line as "ab" and
    # the lower line as one wide box "cdef". OCR boxes are 20 tall vs 8 for
    # GT, same centers. The line order must come from one shared, tight
    # tolerance (set by the GT height). A per-side tolerance (0.6 x 20 = 12 >=
    # the 10px line pitch) folds the OCR's two lines into one, sorts by x, and
    # reads "cdefab" against the GT's "abcdef".
    gt = [box("ab", 100, y=0, h=8), box("cd", 0, y=10, h=8), box("ef", 100, y=10, h=8)]
    ocr = [box("ab", 100, y=-6, h=20), box("cdef", 0, y=4, h=20, w=140)]
    score = grouped_score(gt, ocr)
    assert score.groups == 1  # really one cluster, so reading order decides the score
    assert score.cer == 0.0
