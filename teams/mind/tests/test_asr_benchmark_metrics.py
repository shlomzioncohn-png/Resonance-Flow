"""Tests for the ASR accuracy proxy in benchmarks/asr_benchmark.py (#135).

The rough word diff and the reference-transcript builder decide which Whisper
size wins, so they're tested directly rather than trusted from the printed
benchmark output. Neither needs faster-whisper or the AMI audio, so these run
in CI.
"""

from pathlib import Path

import pytest
from benchmarks.asr_benchmark import WordDiff, reference_words, word_diff


def test_identical_transcripts_have_zero_rough_wer():
    words = ["so", "hi", "everyone", "thanks", "for", "coming"]
    diff = word_diff(words, words)
    assert (diff.rough_wer, diff.word_recall, diff.word_precision) == (0.0, 1.0, 1.0)


def test_each_edit_type_is_counted():
    ref = ["the", "remote", "should", "be", "small", "and", "cheap"]
    hyp = ["the", "remote", "could", "be", "small", "cheap", "today"]
    diff = word_diff(ref, hyp)
    # should->could (S), "and" dropped (D), "today" added (I)
    assert (diff.substituted, diff.deleted, diff.inserted) == (1, 1, 1)
    assert diff.matched == 5
    assert diff.rough_wer == pytest.approx(3 / 7)


def test_unequal_replace_block_splits_into_substitutions_and_deletions():
    diff = word_diff(["a", "b", "c", "d"], ["a", "x", "d"])
    assert (diff.substituted, diff.deleted, diff.inserted) == (1, 1, 0)


def test_common_words_still_align_on_long_sequences():
    # With difflib's default autojunk, a word over 1% of a 200+ word sequence is
    # junk and never matched -- this transcript would score near-total error.
    ref = ("the cat and the dog and the bird " * 50).split()
    assert word_diff(ref, ref).matched == len(ref)


def test_precision_of_empty_hypothesis_is_zero_not_a_crash():
    diff = WordDiff(ref_words=3, hyp_words=0, matched=0, substituted=0, deleted=3, inserted=0)
    assert diff.word_precision == 0.0
    assert diff.rough_wer == 1.0


def _words_file(path: Path, speaker: str, words: list[tuple[float, str, bool]]) -> Path:
    rows = "\n".join(
        f'<w nite:id="M.{speaker}.words{i}" starttime="{t}" endtime="{t}"'
        f'{" punc=\"true\"" if punc else ""}>{text}</w>'
        for i, (t, text, punc) in enumerate(words)
    )
    path.write_text(
        '<?xml version="1.0" encoding="ISO-8859-1"?>\n'
        '<nite:root xmlns:nite="http://nite.sourceforge.net/">\n'
        f'{rows}\n<vocalsound nite:id="v1" starttime="0.5" endtime="0.6" type="laugh"/>\n'
        "</nite:root>\n",
        encoding="iso-8859-1",
    )
    return path


def test_reference_interleaves_speakers_by_start_time(tmp_path: Path):
    a = _words_file(tmp_path / "A.xml", "A", [(1.0, "Okay", False), (3.0, "great", False)])
    b = _words_file(tmp_path / "B.xml", "B", [(2.0, "Hello", False)])
    assert reference_words([a, b]) == ["okay", "hello", "great"]


def test_reference_drops_punctuation_tokens_and_non_word_markers(tmp_path: Path):
    a = _words_file(
        tmp_path / "A.xml",
        "A",
        [(1.0, "Um", False), (1.0, ",", True), (1.1, "we&#39;re", False), (1.2, ".", True)],
    )
    # the <vocalsound> in every file is not a <w>, so it never shows up
    assert reference_words([a]) == ["um", "were"]
