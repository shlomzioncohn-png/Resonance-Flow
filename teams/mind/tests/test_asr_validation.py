"""Tests for the reference parsing and normalization in benchmarks/asr_validation.py (#480).

Every WER the validation reports depends on these two, so each normalization
rule and each reference filter is pinned here. None of it needs faster-whisper
or the AMI audio, so these run in CI.
"""

from pathlib import Path

import pytest
from benchmarks.asr_validation import (
    load_reference,
    normalize,
    number_words,
    reference_text,
    reference_window,
)

# --- normalization ---------------------------------------------------------


def test_case_and_punctuation_do_not_count():
    assert normalize("Okay. So, what's next?") == normalize("okay so what's next")


def test_apostrophes_inside_a_word_are_kept():
    # The #135 rough diff turned "we're" into "were" -- a different word.
    assert normalize("We're here, they're not.") == ["we're", "here", "they're", "not"]
    assert normalize("we're") != normalize("were")


def test_curly_apostrophe_is_a_straight_one():
    assert normalize("it’s") == ["it's"]


def test_ami_letter_by_letter_acronyms_match_whispers():
    assert normalize("T_V_ E_P_F_L_ P_M_s") == normalize("TV EPFL PMs") == ["tv", "epfl", "pms"]


@pytest.mark.parametrize(
    ("whisper", "ami"),
    [
        ("25", "twenty-five"),
        ("6,000", "six thousand"),
        ("150", "one hundred fifty"),
        ("12.5", "twelve point five"),
        ("50%", "fifty percent"),
        ("21st", "twenty-first"),
        ("2nd", "second"),
        ("30th", "thirtieth"),
        ("0", "zero"),
    ],
)
def test_digits_match_amis_spelled_out_numbers(whisper: str, ami: str):
    assert normalize(whisper) == normalize(ami)


def test_number_words():
    assert number_words(0) == ["zero"]
    assert number_words(19) == ["nineteen"]
    assert number_words(40) == ["forty"]
    assert number_words(1_000_001) == ["one", "million", "one"]
    assert number_words(2_350_000) == ["two", "million", "three", "hundred", "fifty", "thousand"]


def test_digits_inside_a_longer_token_are_left_alone():
    assert normalize("1990s mp3 1P2P3P") == ["1990s", "mp3", "1p2p3p"]


def test_whisper_ellipses_and_hyphens_separate_words():
    assert normalize("you...one, so...25 part-time to-") == [
        "you", "one", "so", "twenty", "five", "part", "time", "to",
    ]  # fmt: skip


@pytest.mark.parametrize(
    ("variant", "canonical"),
    [("OK", "okay"), ("ok", "okay"), ("'Kay", "okay"), ("alright", "all right"),
     ("'cause", "because")],
)  # fmt: skip
def test_spelling_variants_map_to_one_form(variant: str, canonical: str):
    assert normalize(variant) == normalize(canonical)


def test_bare_cause_is_not_because():
    # Only the apostrophe form is the conjunction; "cause" may be the noun.
    assert normalize("the cause") == ["the", "cause"]


def test_fillers_and_backchannels_are_removed():
    assert normalize("Uh, um, I mean, hmm. Mm-hmm, uh-huh. Erm, yeah.") == ["i", "mean", "yeah"]


def test_quoting_apostrophes_are_stripped():
    assert normalize("'hello' he said") == ["hello", "he", "said"]


def test_nothing_left_is_an_empty_list():
    assert normalize("Um... uh?") == []
    assert normalize("") == []


# --- reference parsing ---------------------------------------------------------


def _words_file(path: Path, words: list[tuple[float, str, str]]) -> Path:
    """An AMI words file; each word is (start time, text, extra attributes)."""
    rows = "\n".join(
        f'<w nite:id="M.words{i}" starttime="{t}" endtime="{t + 0.3}"{attrs}>{text}</w>'
        for i, (t, text, attrs) in enumerate(words)
    )
    path.write_text(
        '<?xml version="1.0" encoding="ISO-8859-1"?>\n'
        '<nite:root xmlns:nite="http://nite.sourceforge.net/">\n'
        f"{rows}\n"
        '<vocalsound nite:id="v1" starttime="0.5" endtime="0.6" type="laugh"/>\n'
        '<disfmarker nite:id="d1" starttime="0.7" endtime="0.7"/>\n'
        '<gap nite:id="g1" starttime="0.8" endtime="0.9"/>\n'
        "</nite:root>\n",
        encoding="iso-8859-1",
    )
    return path


def test_reference_interleaves_speakers_by_start_time(tmp_path: Path):
    a = _words_file(tmp_path / "IS1008b.A.words.xml", [(1.0, "Okay", ""), (3.0, "great", "")])
    b = _words_file(tmp_path / "IS1008b.B.words.xml", [(2.0, "Hello", "")])
    words = load_reference([a, b])
    assert [(w.text, w.speaker) for w in words] == [("Okay", "A"), ("Hello", "B"), ("great", "A")]
    assert (words[0].start_s, words[0].end_s) == (1.0, 1.3)


def test_reference_keeps_the_text_as_written(tmp_path: Path):
    # Normalization is normalize()'s job, applied to both sides alike.
    a = _words_file(tmp_path / "M.A.words.xml", [(1.0, "T_V_", ""), (1.5, "we&apos;re", "")])
    assert reference_text(load_reference([a])) == "T_V_ we're"


def test_reference_drops_punctuation_truncated_words_and_non_word_markers(tmp_path: Path):
    a = _words_file(
        tmp_path / "M.A.words.xml",
        [
            (1.0, "speech", ""),
            (1.2, ",", ' punc="true"'),
            (1.4, "recognit", ' trunc="true"'),
            (1.6, "recognition", ""),
            (1.8, "opening", ' mispronounced="true"'),  # a real word: kept
        ],
    )
    # <vocalsound>, <disfmarker>, <gap> are in every file and never <w>
    assert reference_text(load_reference([a])) == "speech recognition opening"


def test_equal_start_times_order_by_speaker(tmp_path: Path):
    b = _words_file(tmp_path / "M.B.words.xml", [(1.0, "no", "")])
    a = _words_file(tmp_path / "M.A.words.xml", [(1.0, "yes", "")])
    assert reference_text(load_reference([b, a])) == "yes no"


def test_a_file_that_is_not_an_ami_words_file_is_refused(tmp_path: Path):
    path = _words_file(tmp_path / "transcript.xml", [(1.0, "hi", "")])
    with pytest.raises(ValueError, match="not an AMI words file"):
        load_reference([path])


def test_consecutive_windows_split_the_words_exactly_once(tmp_path: Path):
    a = _words_file(
        tmp_path / "M.A.words.xml",
        [(0.0, "a", ""), (4.9, "b", ""), (5.0, "c", ""), (9.99, "d", ""), (10.0, "e", "")],
    )
    words = load_reference([a])
    first, second = reference_window(words, 0, 5), reference_window(words, 5, 10)
    assert reference_text(first) == "a b"
    assert reference_text(second) == "c d"  # "e" starts at the end: the next window's


def test_an_empty_window_is_a_programming_error(tmp_path: Path):
    with pytest.raises(ValueError, match="empty window"):
        reference_window([], 5, 5)
