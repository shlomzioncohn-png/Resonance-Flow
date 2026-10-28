"""Tests for benchmarks/asr_error_analysis.py, the #480 failure analysis.

The overlap/deletion comparison is the evidence for the main failure mode in
the ASR write-up, so the overlap test, the per-token attribution and the
alignment it rests on are pinned here. Runs on hand-built words, in CI.
"""

import pytest
from benchmarks.asr_error_analysis import (
    Outcome,
    align,
    bins,
    deletion_runs,
    overlap_flags,
    reference_tokens,
)
from benchmarks.asr_validation import ReferenceWord, Window, normalize, reference_text


def _w(start: float, end: float, speaker: str, text: str = "word") -> ReferenceWord:
    return ReferenceWord(start_s=start, end_s=end, speaker=speaker, text=text)


def test_words_of_two_speakers_at_once_are_both_overlapped():
    words = [_w(1.0, 2.0, "A"), _w(1.5, 2.5, "B"), _w(3.0, 4.0, "A")]
    assert overlap_flags(words) == [True, True, False]


def test_one_speaker_never_overlaps_themselves():
    assert overlap_flags([_w(1.0, 2.0, "A"), _w(1.5, 2.5, "A")]) == [False, False]


def test_touching_words_do_not_overlap():
    assert overlap_flags([_w(1.0, 2.0, "A"), _w(2.0, 3.0, "B")]) == [False, False]


def test_zero_length_words_overlap_nothing():
    assert overlap_flags([_w(1.0, 3.0, "A"), _w(2.0, 2.0, "B")]) == [False, False]


def test_a_long_word_overlaps_a_later_short_one_past_an_unrelated_word():
    # A's long word spans B's word even though another A word starts in between.
    words = [_w(1.0, 5.0, "A"), _w(2.0, 2.5, "A"), _w(4.0, 4.5, "B")]
    assert overlap_flags(words) == [True, False, True]


def test_tokens_keep_their_words_timing_and_match_whole_text_normalization():
    words = [
        _w(1.0, 2.0, "A", "Twenty-five"),
        _w(2.0, 2.2, "B", "Um"),
        _w(2.5, 3.0, "A", "T_V_s"),
        _w(3.0, 3.5, "A", "alright"),
    ]
    tokens = reference_tokens(words, [True, False, False, True])
    assert [(t.text, t.start_s, t.overlapped) for t in tokens] == [
        ("twenty", 1.0, True), ("five", 1.0, True), ("tvs", 2.5, False),
        ("all", 3.0, True), ("right", 3.0, True),
    ]  # fmt: skip
    assert [t.text for t in tokens] == normalize(reference_text(words))


def test_align_gives_one_outcome_per_reference_token_and_anchors_insertions():
    # One minimal alignment only (cost 4): x inserted, c deleted, e>z, y inserted.
    # Examples with several equal-cost alignments would pin jiwer's tie-break.
    outcomes, insertions = align(list("abcdef"), list("xabdzfy"))
    assert [o.kind for o in outcomes] == ["hit", "hit", "del", "hit", "sub", "hit"]
    assert outcomes[4].hyp == "z"
    assert insertions == [(-1, "x"), (5, "y")]


def test_deletion_runs():
    kinds = ["del", "del", "hit", "del", "sub", "del", "del", "del"]
    assert deletion_runs([Outcome(kind=k) for k in kinds]) == [2, 1, 3]


def test_bins_split_by_start_time_and_mark_each_error():
    words = [
        _w(0.0, 0.5, "A", "keep"), _w(5.0, 5.5, "A", "lost"), _w(10.0, 10.5, "A", "this"),
        _w(40.0, 40.5, "A", "two"), _w(45.0, 45.5, "A", "end"),
    ]  # fmt: skip
    tokens = reference_tokens(words, [False] * len(words))
    outcomes, insertions = align([t.text for t in tokens], ["keep", "this", "too", "end", "new"])
    result = bins(Window(meeting="M", start_s=0.0, end_s=60.0), tokens, outcomes, insertions)
    assert [(b.start_s, b.ref_words, b.errors) for b in result] == [(0.0, 3, 1), (30.0, 2, 2)]
    first, second = result
    assert first.marked_reference == "keep [lost] this"
    assert first.hypothesis == "keep this"
    # the insertion after the last token lands in that token's bin
    assert second.marked_reference == "{two>too} end +new"
    assert second.error_rate == pytest.approx(1.0)
