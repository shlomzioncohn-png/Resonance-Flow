"""Tests for the ASR pipeline (src/mind/multimodal/asr.py), #478.

Two halves:
- With the real Whisper base.en: a clean short recording, an unsupported format,
  empty and silent audio, and the duration cap. The weights are not in git (see
  models/README.md), so these skip on a machine without models/whisper/base.en/
  -- including CI, whose test job does not build the image.
- With the model replaced by a fake: timeout, environment failures, a bug of
  ours, and record_call(). These need no weights, so they run in CI too.

The recording in tests/fixtures/asr/ was generated with the Windows speech
synthesizer (offline TTS, 16 kHz mono): "Please send the quarterly report to the
finance team by Friday."
"""

from __future__ import annotations

import hashlib
import io
import math
import struct
import wave
from itertools import pairwise
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest
from PIL import Image

from mind.multimodal import asr
from mind.multimodal.asr import ASRFailureReason, transcribe
from mind.multimodal.failures import FailureCategory
from mind.observability.tracking import _reset_for_tests, get_recent_calls

_ROOT = Path(__file__).resolve().parent.parent
_CLEAN_RECORDING = _ROOT / "tests" / "fixtures" / "asr" / "short_meeting_note.wav"
_MODEL_DIR = _ROOT / "models" / "whisper" / "base.en"

_needs_weights = pytest.mark.skipif(
    not (_MODEL_DIR / "model.bin").exists(),
    reason="Whisper base.en weights not present locally (see models/README.md)",
)


def wav(samples: list[int] | None = None, *, seconds: float = 0.0, rate: int = 16_000) -> bytes:
    """A 16-bit mono WAV of `samples`, or of `seconds` of silence."""
    if samples is None:
        samples = [0] * int(seconds * rate)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))
    return buf.getvalue()


def tone(seconds: float, rate: int = 16_000) -> bytes:
    """A 440 Hz tone: valid, non-silent audio (decoding tests only, no speech)."""
    n = int(seconds * rate)
    return wav([int(8000 * math.sin(2 * math.pi * 440 * i / rate)) for i in range(n)], rate=rate)


def png_bytes() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (20, 20), "white").save(buf, format="PNG")
    return buf.getvalue()


@pytest.fixture(autouse=True)
def _fresh_state(monkeypatch: pytest.MonkeyPatch):
    # The model is a per-process singleton and record_call() a module-level
    # ledger: reset both so no test sees another's. The model name is pinned to
    # the decision rather than read from a local .env that may predate #135.
    monkeypatch.setattr(asr, "_model", None)
    monkeypatch.setenv("WHISPER_MODEL", "base.en")
    monkeypatch.delenv("WHISPER_MODELS_DIR", raising=False)
    monkeypatch.setenv("MODEL_CACHE_DIR", str(_ROOT / "models"))
    _reset_for_tests()


# --- with the real model -----------------------------------------------------------


@_needs_weights
def test_a_clean_short_recording_is_transcribed_with_confidence():
    result = transcribe(_CLEAN_RECORDING.read_bytes(), filename="short_meeting_note.wav")

    assert result.success, result.error
    text = result.text.lower()
    for word in ("quarterly report", "finance", "friday"):
        assert word in text
    assert result.segments
    for s in result.segments:  # Whisper's real scores, on the scales TranscriptSegment documents
        assert s.avg_logprob <= 0
        assert s.confidence == pytest.approx(math.exp(s.avg_logprob))
        assert 0 < s.compression_ratio < 2.4  # under Whisper's own "failed decode" gate
        assert 0 <= s.no_speech_prob <= 1
    assert result.mean_confidence is not None
    assert result.failure_category is None
    assert result.duration_s == result.duration_available_s == pytest.approx(4.1, abs=0.2)
    assert result.truncated is False


@_needs_weights
def test_every_call_that_reaches_the_model_is_recorded():
    transcribe(_CLEAN_RECORDING.read_bytes())
    calls = get_recent_calls()
    assert [c.model_name for c in calls] == ["whisper-base.en-int8-local"]
    assert calls[0].duration_ms > 0
    assert calls[0].cost is None  # a local model costs CPU seconds, not dollars (#479)


def model_must_not_load(monkeypatch: pytest.MonkeyPatch) -> None:
    """Fail the test if transcribe() touches the Whisper model at all.

    The input checks run before the model is needed (team-lead review on the PR), so
    the tests below need no weights and run in CI. Replacing the loader -- rather than
    relying on the weights being absent -- proves it on a machine that has them too.
    """

    def must_not_load():
        raise AssertionError("the Whisper model was loaded for an input check")

    monkeypatch.setattr(asr, "_load_model", must_not_load)


@pytest.mark.parametrize(
    ("content", "filename"),
    [
        (b"this is a text file, not audio" * 20, "notes.txt"),
        (png_bytes(), "scan.png"),  # a real file, just not audio
    ],
)
def test_an_unsupported_format_is_unreadable_format(
    monkeypatch: pytest.MonkeyPatch, content: bytes, filename: str
):
    model_must_not_load(monkeypatch)
    result = transcribe(content, filename=filename)
    assert (result.success, result.reason) == (False, ASRFailureReason.UNREADABLE_FORMAT)
    assert result.retryable is False
    assert filename in (result.error or "")
    assert get_recent_calls() == []  # rejected before Whisper runs


@_needs_weights
def test_a_truncated_audio_file_does_not_crash():
    content = _CLEAN_RECORDING.read_bytes()
    result = transcribe(content[: len(content) // 3])
    # Whatever survived is transcribed, or it is a classified failure -- never a crash
    assert result.success or result.reason in {
        ASRFailureReason.UNREADABLE_FORMAT,
        ASRFailureReason.NO_SPEECH_DETECTED,
    }


def test_empty_input_is_a_data_failure():
    result = transcribe(b"")
    assert (result.success, result.reason) == (False, ASRFailureReason.EMPTY_INPUT)
    assert result.retryable is False


def test_a_silent_recording_is_no_speech_not_a_made_up_transcript(
    monkeypatch: pytest.MonkeyPatch,
):
    # the real VAD finds no speech, so the model is never needed
    model_must_not_load(monkeypatch)
    result = transcribe(wav(seconds=5))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)
    assert result.text == ""
    assert result.retryable is False


def test_a_wav_with_no_samples_is_no_speech(monkeypatch: pytest.MonkeyPatch):
    model_must_not_load(monkeypatch)
    result = transcribe(wav([]))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)


def test_a_junk_file_without_the_model_is_unreadable_format_not_asr_unavailable(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
):
    # The reviewer's case: a text file on a host without the weights. The file is the
    # problem (DATA, drop it); ASR_UNAVAILABLE would make the consumer retry it forever.
    monkeypatch.setenv("WHISPER_MODELS_DIR", str(tmp_path))  # no weights anywhere
    monkeypatch.setenv("MODEL_CACHE_DIR", str(tmp_path))
    monkeypatch.delitem(asr.PINNED_WHISPER_MODELS, "base.en")  # and no download either
    result = transcribe(b"this is a text file, not audio" * 20, filename="notes.txt")
    assert (result.success, result.reason) == (False, ASRFailureReason.UNREADABLE_FORMAT)
    assert result.failure_category is FailureCategory.DATA
    assert result.retryable is False


def test_speech_without_the_model_is_still_asr_unavailable(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
):
    # The other side: decodable audio with speech in it does need the model, and its
    # absence is the environment -- retryable, as before.
    monkeypatch.setenv("WHISPER_MODELS_DIR", str(tmp_path))
    monkeypatch.setenv("MODEL_CACHE_DIR", str(tmp_path))
    monkeypatch.delitem(asr.PINNED_WHISPER_MODELS, "base.en")  # no download either
    monkeypatch.setattr(asr, "_speech_regions", lambda window: [(0, len(window))])
    result = transcribe(tone(1.0))
    assert (result.success, result.reason) == (False, ASRFailureReason.ASR_UNAVAILABLE)
    assert result.retryable is True
    # Not a model call: Whisper never ran (#479) -- the host lacks it, which the
    # reason says. Recording it would add a 1 ms "error" to Whisper's figures.
    assert get_recent_calls() == []


@_needs_weights
def test_a_recording_over_the_cap_is_truncated_and_says_so():
    result = transcribe(_CLEAN_RECORDING.read_bytes(), max_duration_s=2)
    assert result.success, result.error
    assert result.duration_s == 2.0
    assert result.duration_available_s == pytest.approx(4.1, abs=0.2)
    assert result.truncated is True
    assert "finance" not in result.text.lower()  # said after the 2 s mark


# --- decoding, no model ------------------------------------------------------------


def test_decode_resamples_to_16k_mono_and_reads_to_the_end():
    decoded = asr._decode(tone(1.5, rate=44_100), max_duration_s=60)
    assert len(decoded.audio) == pytest.approx(1.5 * asr.SAMPLE_RATE, abs=200)
    assert decoded.audio.dtype.name == "float32"
    assert 0 < abs(decoded.audio).max() <= 1
    assert (decoded.reached_end, decoded.broke) == (True, False)
    assert decoded.declared_s == pytest.approx(1.5, abs=0.02)  # from the RIFF header


def test_decode_stops_at_the_cap_and_takes_the_rest_from_the_header():
    decoded = asr._decode(tone(3.0), max_duration_s=1)
    assert len(decoded.audio) == asr.SAMPLE_RATE  # one second kept
    assert decoded.reached_end is False
    assert decoded.present_s == pytest.approx(3.0, abs=0.02)  # WAV: from the data size


class _CountingContainer:
    """An av container whose decode() counts the frames it hands out."""

    def __init__(self, container, counter: list[int]):
        self._container, self._counter = container, counter

    def __enter__(self):
        self._container.__enter__()
        return self

    def __exit__(self, *exc):
        return self._container.__exit__(*exc)

    def __getattr__(self, name):
        return getattr(self._container, name)

    def decode(self, *args, **kwargs):
        for frame in self._container.decode(*args, **kwargs):
            self._counter[0] += 1
            yield frame


def test_nothing_past_the_cap_is_decoded(monkeypatch: pytest.MonkeyPatch):
    # R2 on #478: max_duration_s must bound the decode, not only the transcription --
    # a three-hour compressed recording took ~35 s just to decode in full.
    import av

    real_open = av.open

    def frames_decoded(content: bytes, cap_s: float) -> int:
        counter = [0]
        monkeypatch.setattr(
            av, "open", lambda *a, **k: _CountingContainer(real_open(*a, **k), counter)
        )
        asr._decode(content, cap_s)
        monkeypatch.setattr(av, "open", real_open)
        return counter[0]

    long_file = mp3_fixture() if _has_encoder("libmp3lame") else tone(60.0)
    whole = frames_decoded(long_file, 3600)
    capped = frames_decoded(long_file, 1)
    assert capped < whole / 2  # stopped near the cap, plus the one frame that checks for more


def test_max_duration_below_or_at_zero_is_a_programming_error():
    with pytest.raises(ValueError, match="max_duration_s"):
        transcribe(b"x", max_duration_s=0)


# --- with the model replaced ---------------------------------------------------------


def fake_segment(text: str, start: float, end: float, avg_logprob: float = -0.2):
    return SimpleNamespace(
        text=text,
        start=start,
        end=end,
        avg_logprob=avg_logprob,
        compression_ratio=1.5,
        no_speech_prob=0.01,
    )


class FakeWhisper:
    def __init__(self, segments=(), error: Exception | None = None, on_segment=None):
        self._segments = list(segments)
        self._error = error
        self._on_segment = on_segment
        self.kwargs: dict = {}

    def transcribe(self, audio, **kwargs):
        self.kwargs = kwargs
        if self._error:
            raise self._error

        def lazy():
            for seg in self._segments:
                if self._on_segment:
                    self._on_segment()
                yield seg

        return lazy(), None


def use_model(monkeypatch: pytest.MonkeyPatch, model: FakeWhisper) -> None:
    """Replace Whisper with `model`, and the VAD with one that hears speech everywhere
    (a test tone is not speech to the real one, which would skip it)."""
    monkeypatch.setattr(asr, "_load_model", lambda: ("whisper-fake-local", model))
    monkeypatch.setattr(asr, "_speech_regions", lambda window: [(0, len(window))])


# --- chunking: cut in silences, timestamps from the start of the recording -----------

SR = asr.SAMPLE_RATE


def fake_vad(monkeypatch: pytest.MonkeyPatch, speech_s: list[tuple[float, float]]) -> None:
    """A VAD that hears speech at these absolute times (seconds) of the recording.

    _chunks() calls it per window, so it has to know where each window starts: the
    windows are consecutive, each starting where the last chunk ended."""
    position = [0]

    def regions(window):
        start = position[0]
        found = []
        for a, b in speech_s:
            a, b = int(a * SR) - start, int(b * SR) - start
            if b > 0 and a < len(window):
                found.append((max(a, 0), min(b, len(window))))
        return found

    original = asr._chunks

    def tracking_chunks(audio):
        for chunk_start, chunk_end, has_speech in original(audio):
            yield chunk_start, chunk_end, has_speech
            position[0] = chunk_end

    monkeypatch.setattr(asr, "_speech_regions", regions)
    monkeypatch.setattr(asr, "_chunks", tracking_chunks)


def chunk_bounds_s(monkeypatch, seconds: float, speech_s) -> list[tuple[float, float, bool]]:
    fake_vad(monkeypatch, speech_s)
    audio = np.zeros(int(seconds * SR), dtype=np.float32)
    return [(a / SR, b / SR, s) for a, b, s in asr._chunks(audio)]


def test_a_recording_shorter_than_a_chunk_is_one_chunk(monkeypatch: pytest.MonkeyPatch):
    assert chunk_bounds_s(monkeypatch, 45, [(1, 44)]) == [(0, 45, True)]


def test_a_chunk_is_cut_in_the_middle_of_its_latest_silence(monkeypatch: pytest.MonkeyPatch):
    # speech 0-40 s and 50-70 s, then 75-100: the window 0-60 s has its silence at
    # 40-50 s, so the first chunk ends at 45 s, not at 60 s in the middle of a word
    bounds = chunk_bounds_s(monkeypatch, 100, [(0, 40), (50, 70), (75, 100)])
    assert bounds[0] == (0, 45, True)
    assert bounds[1][0] == 45


def test_a_silence_before_the_minimum_is_not_used_as_the_cut(monkeypatch: pytest.MonkeyPatch):
    # the only silence in 0-60 s is at 5-10 s, before MIN_CHUNK_S: hard cut at 60 s
    bounds = chunk_bounds_s(monkeypatch, 120, [(0, 5), (10, 120)])
    assert bounds[0] == (0, 60, True)


def test_continuous_speech_is_hard_cut_at_the_chunk_length(monkeypatch: pytest.MonkeyPatch):
    bounds = chunk_bounds_s(monkeypatch, 150, [(0, 150)])
    assert bounds == [(0, 60, True), (60, 120, True), (120, 150, True)]


def test_a_window_with_no_speech_is_marked_for_skipping(monkeypatch: pytest.MonkeyPatch):
    bounds = chunk_bounds_s(monkeypatch, 130, [(70, 130)])
    assert bounds[0] == (0, 60, False)
    assert all(has_speech for _, _, has_speech in bounds[1:])


def test_chunks_cover_the_whole_recording_without_overlap(monkeypatch: pytest.MonkeyPatch):
    bounds = chunk_bounds_s(monkeypatch, 600, [(i * 13, i * 13 + 10) for i in range(47)])
    assert bounds[0][0] == 0 and bounds[-1][1] == 600
    assert all(a[1] == b[0] for a, b in pairwise(bounds))
    assert all(asr.MIN_CHUNK_S <= b - a <= asr.CHUNK_S for a, b, _ in bounds[:-1])


def test_segment_timestamps_are_from_the_start_of_the_recording(
    monkeypatch: pytest.MonkeyPatch,
):
    # Whisper sees each chunk on its own and times segments from the chunk's start
    model = FakeWhisper([fake_segment(" Hi.", 1.0, 2.0)])
    use_model(monkeypatch, model)
    fake_vad(monkeypatch, [(0, 150)])  # continuous speech: chunks at 0, 60, 120 s
    result = transcribe(wav(seconds=150))
    assert [(s.start, s.end) for s in result.segments] == [(1, 2), (61, 62), (121, 122)]


def test_a_silent_chunk_never_reaches_whisper(monkeypatch: pytest.MonkeyPatch):
    calls: list[int] = []

    class CountingWhisper(FakeWhisper):
        def transcribe(self, audio, **kwargs):
            calls.append(len(audio))
            return super().transcribe(audio, **kwargs)

    use_model(monkeypatch, CountingWhisper([fake_segment(" Hi.", 0.0, 1.0)]))
    fake_vad(monkeypatch, [(70, 130)])  # 0-60 s is silence
    assert transcribe(wav(seconds=130)).success
    assert len(calls) == 2  # the chunks after the silent first one
    assert get_recent_calls()  # still recorded


def test_a_recording_the_vad_finds_no_speech_in_is_no_speech(monkeypatch: pytest.MonkeyPatch):
    use_model(monkeypatch, FakeWhisper([fake_segment(" made up", 0.0, 1.0)]))
    fake_vad(monkeypatch, [])
    result = transcribe(wav(seconds=90))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)


def test_whisper_is_run_with_the_measured_settings_and_the_vad_filter(
    monkeypatch: pytest.MonkeyPatch,
):
    model = FakeWhisper([fake_segment(" Hello.", 0.0, 1.0)])
    use_model(monkeypatch, model)
    assert transcribe(tone(1.0)).success
    assert model.kwargs == {"language": "en", "beam_size": asr.BEAM_SIZE, "vad_filter": True}


def test_segments_text_and_confidence_come_from_whisper(monkeypatch: pytest.MonkeyPatch):
    use_model(
        monkeypatch,
        FakeWhisper(
            [
                fake_segment(" Ship it Monday.", 0.0, 1.0, avg_logprob=-0.1),
                fake_segment("   ", 1.0, 1.2),  # whitespace-only: dropped
                fake_segment(" Uh, the budget?", 1.2, 2.0, avg_logprob=-1.5),
            ]
        ),
    )
    result = transcribe(tone(2.0))
    assert result.success
    assert result.text == "Ship it Monday.\nUh, the budget?"
    assert [s.confidence for s in result.segments] == [
        pytest.approx(math.exp(-0.1)),
        pytest.approx(math.exp(-1.5)),
    ]
    assert [s.text for s in result.low_confidence_segments] == ["Uh, the budget?"]


def test_whisper_finding_nothing_is_no_speech(monkeypatch: pytest.MonkeyPatch):
    use_model(monkeypatch, FakeWhisper([]))
    result = transcribe(tone(1.0))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)


def test_whisper_failing_on_decoded_audio_is_an_environment_failure(
    monkeypatch: pytest.MonkeyPatch,
):
    use_model(monkeypatch, FakeWhisper(error=RuntimeError("CTranslate2: out of memory")))
    result = transcribe(tone(1.0))
    assert (result.success, result.reason) == (False, ASRFailureReason.ASR_FAILED)
    assert result.retryable is True
    assert [c.model_name for c in get_recent_calls()] == ["whisper-fake-local"]  # still recorded


def test_a_bug_of_ours_is_internal_error_not_retryable(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    use_model(monkeypatch, FakeWhisper(error=KeyError("a bug in our code")))
    with caplog.at_level("ERROR", logger=asr.logger.name):
        result = transcribe(tone(1.0))
    assert (result.success, result.reason) == (False, ASRFailureReason.INTERNAL_ERROR)
    assert result.retryable is False
    assert "unexpected error in transcribe" in caplog.text  # not silent


def test_a_call_past_the_timeout_returns_what_it_has_as_truncated(
    monkeypatch: pytest.MonkeyPatch,
):
    clock = [0.0]
    monkeypatch.setattr(asr.time, "perf_counter", lambda: clock[0])

    def slow():
        clock[0] += asr.TRANSCRIBE_TIMEOUT_S * 0.6  # the 2nd segment crosses the limit

    use_model(
        monkeypatch,
        FakeWhisper(
            [fake_segment(f" Part {i}.", i * 10.0, i * 10.0 + 10) for i in range(5)],
            on_segment=slow,
        ),
    )
    result = transcribe(wav(seconds=50))  # long enough for all five segments
    assert result.success
    assert result.text == "Part 0.\nPart 1."  # the rest was never decoded
    assert result.duration_s == 20.0  # up to the last decoded segment
    assert result.truncated is True


# --- where the weights come from (team-lead decision on #478) --------------------------
# Baked into the image, checked by sha256 on every load; downloaded only as a local-dev
# fallback, and checked the same way. Real faster-whisper loading and downloading are
# replaced, so these need neither weights nor network.

FAKE_WEIGHTS = b"pretend these are 145 MB of Whisper"
FAKE_SHA256 = hashlib.sha256(FAKE_WEIGHTS).hexdigest()


def place_weights(folder: Path, content: bytes = FAKE_WEIGHTS) -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "model.bin").write_bytes(content)
    return folder


@pytest.fixture
def fake_faster_whisper(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    """Pins base.en to FAKE_WEIGHTS, points both model dirs into tmp_path, and
    records which folder WhisperModel loads and what download_model is asked for."""
    import faster_whisper

    seen: dict = {"loaded": None, "downloads": []}

    class FakeWhisperModel:
        def __init__(self, path: str, **kwargs):
            seen["loaded"] = Path(path)

    def fake_download(repo: str, output_dir: str, revision: str):
        seen["downloads"].append((repo, revision))
        place_weights(Path(output_dir))
        return output_dir

    monkeypatch.setattr(faster_whisper, "WhisperModel", FakeWhisperModel)
    monkeypatch.setattr(faster_whisper, "download_model", fake_download)
    monkeypatch.setitem(
        asr.PINNED_WHISPER_MODELS,
        "base.en",
        asr.PinnedModel(
            repo="Systran/faster-whisper-base.en", revision="abc", model_sha256=FAKE_SHA256
        ),
    )
    monkeypatch.setenv("WHISPER_MODELS_DIR", str(tmp_path / "image"))
    monkeypatch.setenv("MODEL_CACHE_DIR", str(tmp_path / "cache"))
    # The model loads only for a chunk with speech in it; a test tone is not speech
    # to the real VAD, so hear speech everywhere for the loading paths to run.
    monkeypatch.setattr(asr, "_speech_regions", lambda window: [(0, len(window))])
    return seen


def test_the_baked_model_is_loaded_first_and_checked(fake_faster_whisper, tmp_path: Path):
    baked = place_weights(tmp_path / "image" / "base.en")
    place_weights(tmp_path / "cache" / "whisper" / "base.en")
    name, _ = asr._load_model()
    assert fake_faster_whisper["loaded"] == baked
    assert fake_faster_whisper["downloads"] == []
    assert name == "whisper-base.en-int8-local"


def test_a_baked_model_with_the_wrong_hash_is_not_loaded(fake_faster_whisper, tmp_path: Path):
    place_weights(tmp_path / "image" / "base.en", content=b"some other model")
    result = transcribe(tone(1.0))
    assert (result.success, result.reason) == (False, ASRFailureReason.ASR_UNAVAILABLE)
    assert "sha256" in (result.error or "")
    assert fake_faster_whisper["loaded"] is None


def test_without_local_weights_the_pinned_revision_is_downloaded_and_checked(
    fake_faster_whisper, tmp_path: Path
):
    asr._load_model()
    assert fake_faster_whisper["downloads"] == [("Systran/faster-whisper-base.en", "abc")]
    assert fake_faster_whisper["loaded"] == tmp_path / "cache" / "whisper" / "base.en"


def test_a_failed_download_is_an_environment_failure(
    fake_faster_whisper, monkeypatch: pytest.MonkeyPatch
):
    import faster_whisper

    def blocked(*args, **kwargs):
        raise ConnectionError("418 Blocked by NetFree")

    monkeypatch.setattr(faster_whisper, "download_model", blocked)
    result = transcribe(tone(1.0))
    assert (result.success, result.reason) == (False, ASRFailureReason.ASR_UNAVAILABLE)
    assert result.retryable is True
    assert "418 Blocked by NetFree" in (result.error or "")


def test_an_unpinned_size_is_never_downloaded(fake_faster_whisper, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("WHISPER_MODEL", "small.en")
    result = transcribe(tone(1.0))
    assert (result.success, result.reason) == (False, ASRFailureReason.ASR_UNAVAILABLE)
    assert "not a pinned model" in (result.error or "")
    assert fake_faster_whisper["downloads"] == []


# --- a file cut off mid-way: partial, and never presented as complete -----------------
# (change 3 of R2's review on #478). What the decoder did before it, probed with PyAV
# 18.1 on the speech fixture (2026-10-12) -- these tests were written against it first:
# - A WAV cut anywhere decoded with no error, and FFmpeg recomputes its length from
#   the file size: nothing in the decode or its metadata said the file was short. Only
#   the RIFF header's data-chunk size, which still holds the full length, does.
# - An MP3 cut anywhere decoded up to the cut; its Xing header still says the full
#   length.
# - An MP3 with garbage in the middle failed as a whole, losing the audio before it.
# - An M4A whose index (moov) is at the end -- what Zoom/Teams-style writers produce
#   -- cannot even be opened once cut: nothing to transcribe, so it stays a DATA
#   failure, never an empty success.

_FULL_S = 4.135  # the speech fixture's length


def _has_encoder(codec: str) -> bool:
    import av
    from av.codec.codec import UnknownCodecError

    try:
        av.codec.Codec(codec, "w")
    except UnknownCodecError:
        return False
    return True


def encoded(fmt: str, codec: str) -> bytes:
    """The speech fixture re-encoded into another container/codec, with PyAV."""
    import av

    if not _has_encoder(codec):
        pytest.skip(f"no {codec} encoder in this PyAV build")
    with av.open(io.BytesIO(_CLEAN_RECORDING.read_bytes())) as src:
        frames = list(src.decode(audio=0))
    buf = io.BytesIO()
    with av.open(buf, mode="w", format=fmt) as out:
        stream = out.add_stream(codec, rate=SR, layout="mono")
        for frame in frames:
            frame.pts = None
            for packet in stream.encode(frame):
                out.mux(packet)
        for packet in stream.encode(None):
            out.mux(packet)
    return buf.getvalue()


def cut(content: bytes, fraction: float) -> bytes:
    return content[: int(len(content) * fraction)]


def wav_fixture() -> bytes:
    return _CLEAN_RECORDING.read_bytes()


def mp3_fixture() -> bytes:
    return encoded("mp3", "libmp3lame")


def with_garbage_in_the_middle(content: bytes, size: int = 2048) -> bytes:
    middle = len(content) // 2
    return (
        content[:middle]
        + bytes((i * 37 + 11) % 256 for i in range(size))
        + content[middle + size :]
    )


def whisper_before_and_at_the_break() -> FakeWhisper:
    """Two segments for a file that breaks at ~2 s: one well before the break, and
    one ending inside BREAK_MARGIN_S of it -- the one a cut sentence would be."""
    return FakeWhisper([fake_segment(" Please", 0.0, 0.5), fake_segment(" send the", 0.6, 1.9)])


@pytest.mark.parametrize("make", [wav_fixture, mp3_fixture], ids=["wav", "mp3"])
def test_a_complete_file_is_not_marked_incomplete(monkeypatch: pytest.MonkeyPatch, make):
    use_model(monkeypatch, FakeWhisper([fake_segment(" Hi.", 0.0, 1.0)]))
    result = transcribe(make())
    assert result.success
    assert (result.incomplete_file, result.truncated) == (False, False)
    assert result.duration_available_exact is True  # read to the end
    assert result.text == "Hi."  # nothing dropped: there is no break


def test_a_wav_cut_mid_way_is_transcribed_and_marked_incomplete(monkeypatch: pytest.MonkeyPatch):
    # The case that used to be silent: decoded cleanly, passed as a complete recording.
    use_model(monkeypatch, whisper_before_and_at_the_break())
    result = transcribe(cut(wav_fixture(), 0.5))
    assert result.success
    assert result.incomplete_file is True
    assert result.truncated is False  # not cut by us: the cap was not reached
    assert result.duration_available_s == pytest.approx(_FULL_S / 2, abs=0.05)  # what is there
    assert result.duration_available_exact is True  # decoded to where the file ends
    assert result.text == "Please"  # the segment at the break is dropped


def test_an_mp3_cut_mid_way_is_marked_incomplete_whatever_its_header_says(
    monkeypatch: pytest.MonkeyPatch,
):
    use_model(monkeypatch, whisper_before_and_at_the_break())
    result = transcribe(cut(mp3_fixture(), 0.5))
    assert result.success
    assert result.incomplete_file is True
    # the length actually decoded, not the full length the MP3's header still claims
    assert result.duration_available_s < _FULL_S * 0.75
    assert result.text == "Please"


def test_garbage_in_the_middle_keeps_the_audio_before_it(monkeypatch: pytest.MonkeyPatch):
    use_model(monkeypatch, whisper_before_and_at_the_break())
    result = transcribe(with_garbage_in_the_middle(mp3_fixture()))
    assert result.success
    assert result.incomplete_file is True
    assert 0 < result.duration_available_s < _FULL_S
    assert result.text == "Please"


def test_the_cap_and_a_broken_file_are_told_apart(monkeypatch: pytest.MonkeyPatch):
    # Two independent flags: a file both broken and capped returns both (R2 on #478).
    use_model(monkeypatch, FakeWhisper([fake_segment(" Please", 0.0, 0.5)]))
    capped_complete = transcribe(wav_fixture(), max_duration_s=1)
    capped_broken = transcribe(cut(wav_fixture(), 0.5), max_duration_s=1)
    assert (capped_complete.truncated, capped_complete.incomplete_file) == (True, False)
    assert (capped_broken.truncated, capped_broken.incomplete_file) == (True, True)


def test_a_capped_file_reports_its_declared_length_as_not_exact(monkeypatch: pytest.MonkeyPatch):
    use_model(monkeypatch, FakeWhisper([fake_segment(" Please", 0.0, 0.5)]))
    for make in (wav_fixture, mp3_fixture):
        result = transcribe(make(), max_duration_s=1)
        assert result.success
        assert result.duration_s == 1.0
        # the header's length (RIFF data size, MP3 Xing), not the 1 s that was read --
        # and flagged as not verified by decoding
        assert result.duration_available_s == pytest.approx(_FULL_S, abs=0.05)
        assert result.duration_available_exact is False


def test_a_sentence_ending_well_before_the_break_is_kept(monkeypatch: pytest.MonkeyPatch):
    # only segments inside BREAK_MARGIN_S of the break go, not simply "the last one"
    use_model(monkeypatch, FakeWhisper([fake_segment(" Please", 0.0, 0.5)]))
    result = transcribe(cut(wav_fixture(), 0.5))  # breaks at ~2.07 s
    assert result.text == "Please"
    assert result.incomplete_file is True


def test_a_broken_file_with_nothing_complete_before_the_break_is_no_speech(
    monkeypatch: pytest.MonkeyPatch,
):
    use_model(monkeypatch, FakeWhisper([fake_segment(" Please send the", 0.0, 1.9)]))
    result = transcribe(cut(wav_fixture(), 0.5))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)
    assert "breaks at" in (result.error or "")
    assert result.failure_category is FailureCategory.DATA


def timeout_on_every_segment_after_the_first(monkeypatch: pytest.MonkeyPatch) -> None:
    """A clock that crosses TRANSCRIBE_TIMEOUT_S while the 2nd segment decodes."""
    clock = [0.0]
    monkeypatch.setattr(asr.time, "perf_counter", lambda: clock[0])
    return lambda: clock.__setitem__(0, clock[0] + asr.TRANSCRIBE_TIMEOUT_S * 0.6)


def test_the_segment_at_the_break_is_dropped_even_when_the_timeout_hits_on_it(
    monkeypatch: pytest.MonkeyPatch,
):
    # Team-lead review on the PR: the drop used to be skipped on a timeout, so a
    # file cut off mid-way whose timeout hit on its last segment passed the half
    # sentence at the break on to extraction. A WAV whose header says 40 s but which
    # holds only 20 s, the timeout hitting on its last segment:
    slow = timeout_on_every_segment_after_the_first(monkeypatch)
    use_model(
        monkeypatch,
        FakeWhisper(
            [
                fake_segment(" First sentence.", 15.0, 17.0),
                fake_segment(" Second sentence cut mid-wo", 18.5, 19.8),  # ends at the break
            ],
            on_segment=slow,
        ),
    )
    result = transcribe(cut(wav(seconds=40), 0.5))
    assert (result.incomplete_file, result.truncated) == (True, True)
    assert result.text == "First sentence."
    assert "mid-wo" not in result.text


def test_a_timeout_with_every_segment_at_the_break_is_no_speech_not_an_environment_failure(
    monkeypatch: pytest.MonkeyPatch,
):
    # With the drop now running on a timeout too, nothing may be left -- that is the
    # broken file (DATA: drop it), not a slow environment (ENVIRONMENT would retry
    # the same broken file forever).
    slow = timeout_on_every_segment_after_the_first(monkeypatch)
    use_model(
        monkeypatch,
        FakeWhisper(
            [fake_segment(" Cut", 18.5, 19.2), fake_segment(" mid-wo", 19.3, 19.8)],
            on_segment=slow,
        ),
    )
    result = transcribe(cut(wav(seconds=40), 0.5))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)
    assert result.failure_category is FailureCategory.DATA
    assert result.retryable is False


def test_an_incomplete_file_whose_timeout_decoded_nothing_is_still_an_environment_failure(
    monkeypatch: pytest.MonkeyPatch,
):
    # The other side of the test above: only segments actually dropped at the break
    # make an empty result the file's fault. Here the file is incomplete (its RIFF
    # header says 40 s, it holds 20 s) but the time ran out before any segment came
    # out -- that is the environment, and stays retryable.
    # Time has already run out at the first check: a budget below zero, rather
    # than a fake clock whose first reading is assumed to be the timeout's start
    # -- the call's own window (#479) reads the clock before that.
    monkeypatch.setattr(asr, "TRANSCRIBE_TIMEOUT_S", -1.0)
    use_model(monkeypatch, FakeWhisper([fake_segment(" never reached", 0.0, 1.0)]))
    result = transcribe(cut(wav(seconds=40), 0.5), max_duration_s=5)
    assert (result.success, result.reason) == (False, ASRFailureReason.ASR_FAILED)
    assert result.retryable is True


def test_incomplete_file_is_serialized_for_the_consumer(monkeypatch: pytest.MonkeyPatch):
    use_model(monkeypatch, whisper_before_and_at_the_break())
    dumped = transcribe(cut(wav_fixture(), 0.5)).model_dump()
    assert (dumped["incomplete_file"], dumped["truncated"]) == (True, False)
    assert dumped["duration_available_exact"] is True


@pytest.mark.parametrize(
    ("header", "expected"),
    [
        (b"RIFF\x00\x00\x00\x00WAVE", None),  # no fmt/data chunk
        (b"not a riff file at all", None),
    ],
)
def test_a_wav_header_without_a_usable_size_declares_nothing(header: bytes, expected):
    assert asr._wav_extent(header) is expected


def test_a_streaming_wav_with_an_unknown_size_is_not_called_incomplete():
    # streaming writers put 0xFFFFFFFF in the data size until they know the length
    data = bytearray(wav_fixture())
    data_offset = data.find(b"data")
    data[data_offset + 4 : data_offset + 8] = (0xFFFFFFFF).to_bytes(4, "little")
    assert asr._wav_extent(bytes(data)) is None


@pytest.mark.parametrize(
    "make",
    [
        lambda: wav_fixture()[:200],  # a header and a few samples, no speech
        lambda: mp3_fixture()[:200],  # not one full MP3 frame
        lambda: cut(encoded("ipod", "aac"), 0.5),  # an M4A with its index cut off
    ],
    ids=["wav-header-only", "mp3-under-one-frame", "m4a-index-cut-off"],
)
def test_a_file_cut_before_any_usable_audio_is_a_data_failure_not_an_empty_success(
    monkeypatch: pytest.MonkeyPatch, make
):
    # Whisper is replaced but the real VAD runs, as in production.
    monkeypatch.setattr(
        asr,
        "_load_model",
        lambda: ("whisper-fake-local", FakeWhisper([fake_segment(" made up", 0, 1)])),
    )
    result = transcribe(make())
    assert result.success is False
    assert result.reason in {
        ASRFailureReason.UNREADABLE_FORMAT,
        ASRFailureReason.NO_SPEECH_DETECTED,
    }
    assert result.failure_category is FailureCategory.DATA
    assert result.text == ""


def three_sentences_cut_in_the_third() -> tuple[bytes, float]:
    """The fixture's sentence three times, 1 s of silence between them, cut in the
    middle of the third -- and where the cut falls, in seconds."""
    with wave.open(io.BytesIO(wav_fixture())) as w:  # 16 kHz mono 16-bit
        pcm = w.readframes(w.getnframes())
    sentence = list(struct.unpack(f"<{len(pcm) // 2}h", pcm))
    # wav() writes a 44-byte header whose data size covers all three sentences
    full = wav(sentence + [0] * SR + sentence + [0] * SR + sentence)
    cut_sample = 2 * len(sentence) + 2 * SR + len(sentence) // 2
    return full[: 44 + 2 * cut_sample], cut_sample / SR


@_needs_weights
def test_a_cut_recording_keeps_what_came_before_and_says_it_is_incomplete():
    # With the real Whisper: the sentences before the cut come back, the one the cut
    # falls in is dropped rather than half-transcribed, and the result says so
    # instead of passing as the whole recording.
    content, break_s = three_sentences_cut_in_the_third()
    result = transcribe(content)
    assert result.success, result.error
    assert result.incomplete_file is True
    assert result.text.lower().count("quarterly report") == 2  # the two complete ones
    assert result.segments[-1].end < break_s - asr.BREAK_MARGIN_S


# --- warm_up(): the model load moves out of the first message's handler ------------


def test_warm_up_loads_the_model_once_so_the_first_call_does_not(
    fake_faster_whisper, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    place_weights(tmp_path / "image" / "base.en")
    loads: list[int] = []
    real_load = asr._load_model

    def counting_load():
        if asr._model is None:
            loads.append(1)
        return real_load()

    monkeypatch.setattr(asr, "_load_model", counting_load)
    assert asr.warm_up() is True
    assert asr._model is not None
    asr._load_model()  # what transcribe() calls first
    assert loads == [1]  # loaded by warm_up(), reused afterwards


def test_warm_up_never_raises_and_says_asr_is_not_ready(
    fake_faster_whisper, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    # no weights anywhere under tmp_path, and the download fails
    import faster_whisper

    def blocked(*args, **kwargs):
        raise ConnectionError("418 Blocked by NetFree")

    monkeypatch.setattr(faster_whisper, "download_model", blocked)
    with caplog.at_level("WARNING", logger=asr.logger.name):
        assert asr.warm_up() is False
    assert "ASR not ready" in caplog.text
    assert asr._model is None  # not cached: transcribe() will try again


def test_a_bug_while_warming_up_is_logged_not_raised(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    def buggy():
        raise KeyError("a bug in our code")

    monkeypatch.setattr(asr, "_load_model", buggy)
    with caplog.at_level("ERROR", logger=asr.logger.name):
        assert asr.warm_up() is False
    assert "unexpected error warming up ASR" in caplog.text


@pytest.mark.parametrize(
    "corrupt",
    [
        lambda d: bytes((i * 91 + 7) % 256 for i in range(44)) + d[44:],  # garbage header
        lambda d: d[:12] + b"\x00" * 26 + d[38:],  # RIFF/WAVE kept, fmt chunk zeroed
    ],
    ids=["garbage-header", "zeroed-fmt-chunk"],
)
def test_a_corrupt_header_is_unreadable_format_not_a_crash(
    monkeypatch: pytest.MonkeyPatch, corrupt
):
    # R2 on #478: a malformed header must be UNREADABLE_FORMAT, not a crash. The
    # fixture's fmt chunk is 18 bytes at offset 12, its data chunk header at 38.
    use_model(monkeypatch, FakeWhisper([fake_segment(" made up", 0, 1)]))
    result = transcribe(corrupt(wav_fixture()))
    assert (result.success, result.reason) == (False, ASRFailureReason.UNREADABLE_FORMAT)
    assert result.failure_category is FailureCategory.DATA


# --- decoding memory: one buffer, no copies (the memory work before the PR, #478) ---


def test_decoding_holds_about_one_copy_of_the_audio():
    # It peaked at ~3x the audio (112 MiB for 37 MiB at the 600 s cap): int16
    # chunks, concatenated, converted, then scaled into yet another array.
    import tracemalloc

    content = tone(20.0)
    asr._decode(content, 600)  # imports and first-use allocations outside the window
    tracemalloc.start()
    try:
        decoded = asr._decode(content, 600)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert peak < 1.3 * decoded.audio.nbytes


class _NoDurationContainer(_CountingContainer):
    """A container that declares no length, as some streams don't."""

    duration = None


def test_without_a_declared_length_the_buffer_grows_and_keeps_every_sample(
    monkeypatch: pytest.MonkeyPatch,
):
    import av

    real_open = av.open
    content = tone(5.0)
    expected = asr._decode(content, 600).audio
    monkeypatch.setattr(av, "open", lambda *a, **k: _NoDurationContainer(real_open(*a, **k), [0]))
    grown = asr._decode(content, 600).audio  # starts at 1 s and doubles past it
    assert len(grown) == len(expected)
    assert np.array_equal(grown, expected)


# --- ASR settings come from config.yaml (#481 review) ----------------------


def test_asr_settings_are_read_once_per_process():
    from mind.config import get_asr_settings

    assert get_asr_settings() is get_asr_settings()


def test_missing_config_is_asr_unavailable_not_internal_error(monkeypatch: pytest.MonkeyPatch):
    """A config problem is the host's, not a bug in this module: retryable."""
    monkeypatch.delenv("CONFIG_FILE_PATH", raising=False)

    result = transcribe(tone(1.0))

    assert (result.success, result.reason) == (False, ASRFailureReason.ASR_UNAVAILABLE)
    assert result.failure_category == FailureCategory.ENVIRONMENT
    assert result.retryable is True
    assert "CONFIG_FILE_PATH" in (result.error or "")


def test_a_fixed_config_is_picked_up_without_a_restart(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    """A failed read isn't cached: once config.yaml is there, ASR uses it."""
    monkeypatch.delenv("CONFIG_FILE_PATH", raising=False)
    assert transcribe(tone(1.0)).reason == ASRFailureReason.ASR_UNAVAILABLE

    (tmp_path / "config.yaml").write_text("asr:\n  whisper_model: base.en\n", encoding="utf-8")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "config.yaml"))

    assert asr._model_name() == "whisper-base.en-int8-local"


def test_warm_up_without_config_reports_not_ready(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("CONFIG_FILE_PATH", raising=False)

    assert asr.warm_up() is False
