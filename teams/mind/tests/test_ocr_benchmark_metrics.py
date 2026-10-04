"""Tests for the unaligned word-recall math in benchmarks/ocr_asr_benchmark.py.

word_recall (64.7% for tesseract --psm 11, in benchmarks/ocr-asr-benchmark.md)
is a recall-only secondary number -- aligned CER is the primary one, tested in
test_aligned_metrics.py. Direct unit tests on the calculation itself, not just
eyeballing the benchmark script's printed output, per review feedback on PR #345.
"""

from benchmarks.ocr_asr_benchmark import _normalize, word_recall


def test_normalize_strips_punctuation_and_lowercases():
    assert _normalize("Hello!") == "hello"
    assert _normalize("U.S.A.") == "usa"


def test_normalize_of_pure_punctuation_is_empty():
    assert _normalize("---") == ""


def test_word_recall_is_case_and_punctuation_insensitive():
    assert word_recall("Hello, World!", ["hello", "world"]) == 1.0


def test_word_recall_counts_the_fraction_of_gt_words_found():
    assert word_recall("hello", ["hello", "world"]) == 0.5


def test_word_recall_ignores_ocr_output_that_does_not_match_any_gt_word():
    assert word_recall("completely unrelated text", ["hello"]) == 0.0


def test_word_recall_of_empty_ground_truth_is_zero_not_a_crash():
    # guards the `if not gt_set: return 0.0` branch -- a real edge case
    # for a FUNSD document with no annotated words
    assert word_recall("hello world", []) == 0.0


def test_word_recall_does_not_double_count_duplicate_gt_words():
    # gt_words is a list (one entry per word position in the document);
    # word_recall dedupes both sides via set(), so the same GT word
    # appearing twice is one denominator entry, not two
    assert word_recall("hello", ["hello", "hello"]) == 1.0
