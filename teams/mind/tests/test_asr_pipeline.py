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
    assert all(0 < s.confidence <= 1 for s in result.segments)
    assert result.mean_confidence is not None
    assert result.duration_s == result.duration_available_s == pytest.approx(4.1, abs=0.2)
    assert result.truncated is False


@_needs_weights
def test_every_call_that_reaches_the_model_is_recorded():
    transcribe(_CLEAN_RECORDING.read_bytes())
    calls = get_recent_calls()
    assert [c.model_name for c in calls] == ["whisper-base.en-int8-local"]
    assert calls[0].duration_ms > 0
    assert calls[0].cost == 0.0  # local compute has no price yet (#479)


@_needs_weights
@pytest.mark.parametrize(
    ("content", "filename"),
    [
        (b"this is a text file, not audio" * 20, "notes.txt"),
        (png_bytes(), "scan.png"),  # a real file, just not audio
    ],
)
def test_an_unsupported_format_is_unreadable_format(content: bytes, filename: str):
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


@_needs_weights
def test_a_silent_recording_is_no_speech_not_a_made_up_transcript():
    result = transcribe(wav(seconds=5))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)
    assert result.text == ""
    assert result.retryable is False


@_needs_weights
def test_a_wav_with_no_samples_is_no_speech():
    result = transcribe(wav([]))
    assert (result.success, result.reason) == (False, ASRFailureReason.NO_SPEECH_DETECTED)


@_needs_weights
def test_a_recording_over_the_cap_is_truncated_and_says_so():
    result = transcribe(_CLEAN_RECORDING.read_bytes(), max_duration_s=2)
    assert result.success, result.error
    assert result.duration_s == 2.0
    assert result.duration_available_s == pytest.approx(4.1, abs=0.2)
    assert result.truncated is True
    assert "finance" not in result.text.lower()  # said after the 2 s mark


# --- decoding, no model ------------------------------------------------------------


def test_decode_resamples_to_16k_mono_and_reports_the_real_length():
    audio, available = asr._decode(tone(1.5, rate=44_100), max_duration_s=60)
    assert len(audio) == pytest.approx(1.5 * asr.SAMPLE_RATE, abs=200)
    assert available == pytest.approx(1.5, abs=0.02)
    assert audio.dtype.name == "float32"
    assert 0 < abs(audio).max() <= 1


def test_decode_keeps_only_the_capped_samples_but_counts_them_all():
    audio, available = asr._decode(tone(3.0), max_duration_s=1)
    assert len(audio) == asr.SAMPLE_RATE  # one second kept
    assert available == pytest.approx(3.0, abs=0.02)  # three seconds counted


def test_max_duration_below_or_at_zero_is_a_programming_error():
    with pytest.raises(ValueError, match="max_duration_s"):
        transcribe(b"x", max_duration_s=0)


# --- with the model replaced ---------------------------------------------------------


def fake_segment(text: str, start: float, end: float, avg_logprob: float = -0.2):
    return SimpleNamespace(
        text=text, start=start, end=end, avg_logprob=avg_logprob, no_speech_prob=0.01
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
