"""Tests for benchmarks/asr_validation.py, the ASR validation against AMI (#480).

Every WER the validation reports depends on the reference parsing, the
normalization, the scoring and the fixed window set, so each is pinned here.
The run itself is tested with a stand-in for transcribe(): none of this needs
faster-whisper or the AMI audio, so it runs in CI.
"""

import io
import json
import wave
from pathlib import Path

import pytest
from benchmarks.asr_validation import (
    VALIDATION_WINDOW_S,
    Window,
    WindowResult,
    WordErrors,
    format_size_report,
    load_reference,
    normalize,
    number_words,
    pooled,
    reference_text,
    reference_window,
    score,
    validate_window,
    validation_windows,
    window_wav,
)

from mind.multimodal.asr import ASRFailureReason, ASRResult
from mind.observability.tracking import CallKind, _reset_for_tests, record_call

_MANIFEST = Path(__file__).parent.parent / "eval" / "datasets" / "ami-audio.manifest.json"


@pytest.fixture(autouse=True)
def _fresh_ledger():
    # record_call() keeps a module-level ledger; validate_window reads it.
    _reset_for_tests()


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


# --- scoring ---------------------------------------------------------------------


def test_identical_words_score_zero():
    errors = score(["so", "the", "budget"], ["so", "the", "budget"])
    assert (errors.wer, errors.hits) == (0.0, 3)


@pytest.mark.parametrize(
    ("hypothesis", "s_d_i"),
    [
        (["a", "x", "c"], (1, 0, 0)),
        (["a", "c"], (0, 1, 0)),
        (["a", "b", "x", "c"], (0, 0, 1)),
    ],
)
def test_each_edit_type_is_counted(hypothesis: list[str], s_d_i: tuple[int, int, int]):
    errors = score(["a", "b", "c"], hypothesis)
    assert (errors.substitutions, errors.deletions, errors.insertions) == s_d_i
    assert errors.wer == pytest.approx(1 / 3)


def test_the_wer_is_unique_even_where_the_edit_split_is_not():
    # should->could, "and" dropped, "today" added -- or three substitutions:
    # both cost 3, and which one jiwer reports is its choice.
    errors = score(
        ["the", "remote", "should", "be", "small", "and", "cheap"],
        ["the", "remote", "could", "be", "small", "cheap", "today"],
    )
    assert errors.wer == pytest.approx(3 / 7)
    assert (errors.ref_words, errors.hyp_words) == (7, 7)


def test_an_empty_hypothesis_is_all_deletions():
    errors = score(["a", "b"], [])
    assert (errors.deletions, errors.wer) == (2, 1.0)


def test_an_empty_reference_is_refused():
    with pytest.raises(ValueError, match="empty reference"):
        score([], ["a"])


def test_pooled_wer_weighs_windows_by_their_words_not_equally():
    long_clean = WordErrors(hits=10, substitutions=0, deletions=0, insertions=0)
    short_bad = WordErrors(hits=0, substitutions=2, deletions=0, insertions=0)
    # 2 errors over 12 reference words -- not the mean of 0.0 and 1.0.
    assert pooled([long_clean, short_bad]).wer == pytest.approx(2 / 12)


# --- the fixed set ---------------------------------------------------------------


def test_the_fixed_set_is_pinned():
    # Re-measurements are only comparable on the same windows: changing the
    # positions, the length or the meetings must show up here.
    manifest = json.loads(_MANIFEST.read_text(encoding="utf-8"))
    durations = {a["file"].split(".")[0]: a["duration_s"] for a in manifest["package"]["audio"]}
    labels = [
        w.label
        for record in manifest["records"]
        for w in validation_windows(record["id"], durations[record["id"]])
    ]
    assert labels == [
        "IS1008b@442-682s", "IS1008b@884-1124s", "IS1008b@1326-1566s",
        "IB4003@506-746s", "IB4003@1012-1252s", "IB4003@1517-1757s",
    ]  # fmt: skip


def test_a_meeting_too_short_for_its_last_window_is_refused():
    with pytest.raises(ValueError, match="too short"):
        validation_windows("M", VALIDATION_WINDOW_S)


def _wav(path: Path, seconds: float, rate: int = 16_000) -> Path:
    """A mono 16-bit WAV whose sample i holds i % 30000, so a slice can be located."""
    n = int(seconds * rate)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join((i % 30_000).to_bytes(2, "little") for i in range(n)))
    return path


def test_window_wav_is_a_wav_of_exactly_that_stretch(tmp_path: Path):
    audio = _wav(tmp_path / "m.wav", 1.0, rate=8_000)
    sliced = window_wav(audio, Window(meeting="M", start_s=0.25, end_s=0.5))
    with wave.open(io.BytesIO(sliced), "rb") as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 8_000)
        assert w.getnframes() == 2_000
        first = int.from_bytes(w.readframes(1), "little")
    assert first == 2_000  # sample 0.25 s * 8000 Hz


# --- one window through a stand-in transcribe() -----------------------------------


def _reference(tmp_path: Path) -> list:
    path = _words_file(
        tmp_path / "M.A.words.xml",
        [(0.5, "before", ""), (1.0, "we", ""), (1.5, "need", ""), (2.0, "twenty", ""),
         (2.5, "units", ""), (3.5, "after", "")],
    )  # fmt: skip
    return load_reference([path])


def _fake_transcribe(text: str, *, record: bool = True, model: str = "whisper-base.en-int8-local"):
    def transcribe(content: bytes, *, filename: str = "") -> ASRResult:
        assert content.startswith(b"RIFF") and filename == "M@1-3s.wav"
        if record:
            record_call(model, 500.0, None, kind=CallKind.ASR, cpu_s=1.5)
        return ASRResult(success=True, text=text, duration_s=2.0)

    return transcribe


_WINDOW = Window(meeting="M", start_s=1.0, end_s=3.0)


def test_a_window_is_scored_on_its_reference_words_with_the_pipelines_timing(tmp_path: Path):
    audio = _wav(tmp_path / "m.wav", 4.0)
    row, result = validate_window(
        _fake_transcribe("We need 20\nunits."), "base.en", audio, _reference(tmp_path), _WINDOW
    )
    # Reference in [1, 3): "we need twenty units" -- "before"/"after" are outside.
    assert row.errors.wer == 0.0 and row.errors.ref_words == 4
    assert (row.latency_s, row.cpu_s, row.real_time_factor) == (0.5, 1.5, 0.25)
    assert result.text == "We need 20\nunits."


def test_a_call_without_a_record_call_record_fails_loudly(tmp_path: Path):
    audio = _wav(tmp_path / "m.wav", 4.0)
    with pytest.raises(RuntimeError, match="no record_call"):
        validate_window(
            _fake_transcribe("we need", record=False), "base.en", audio, _reference(tmp_path),
            _WINDOW,
        )  # fmt: skip


def test_a_record_under_another_size_fails_loudly(tmp_path: Path):
    audio = _wav(tmp_path / "m.wav", 4.0)
    wrong = _fake_transcribe("we need", model="whisper-tiny.en-int8-local")
    with pytest.raises(RuntimeError, match="not base.en"):
        validate_window(wrong, "base.en", audio, _reference(tmp_path), _WINDOW)


def test_a_failed_transcription_is_not_scored(tmp_path: Path):
    audio = _wav(tmp_path / "m.wav", 4.0)

    def failing(content: bytes, *, filename: str = "") -> ASRResult:
        return ASRResult(success=False, reason=ASRFailureReason.ASR_UNAVAILABLE, error="no weights")

    with pytest.raises(RuntimeError, match="no weights"):
        validate_window(failing, "base.en", audio, _reference(tmp_path), _WINDOW)


def _row(meeting: str, errors: WordErrors, truncated: bool = False) -> WindowResult:
    return WindowResult(
        size="base.en",
        window=Window(meeting=meeting, start_s=0.0, end_s=240.0),
        errors=errors,
        audio_s=240.0,
        latency_s=24.0,
        cpu_s=96.0,
        truncated=truncated,
    )


def test_report_has_per_window_per_meeting_and_pooled_overall_rows():
    rows = [
        _row("A", WordErrors(hits=9, substitutions=1, deletions=0, insertions=0)),
        _row("B", WordErrors(hits=0, substitutions=0, deletions=2, insertions=0)),
    ]
    report = format_size_report("base.en", rows)
    assert "| A@0-240s | 0.100 |" in report
    assert "| A | 0.100 |" in report and "| B | 1.000 |" in report
    assert "| **overall** | 0.250 |" in report  # 3 errors / 12 words
    assert "WARNING" not in report


def test_report_warns_about_truncated_windows():
    rows = [_row("A", WordErrors(hits=5, substitutions=0, deletions=5, insertions=0), True)]
    report = format_size_report("base.en", rows)
    assert "| YES |" in report and "WARNING: 1 window(s) truncated" in report
