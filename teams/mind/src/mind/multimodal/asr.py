"""Local ASR: audio / meeting recordings -> transcript + a per-segment confidence signal.

Uses Whisper ``base.en`` via faster-whisper (int8, CPU) -- the model decision made
in ``benchmarks/ocr-asr-benchmark.md`` "ASR -- final decision" (#135), not reopened
here.

The result has the same envelope as OCR (``MediaTextResult``, see result.py):
R2's ``bridge.raw.landed`` consumer (#349) calls OCR or ASR by ``modality`` and
both feed the same extraction pipeline, so ``success`` / ``reason`` / ``error`` /
``retryable`` mean the same thing in both. Where OCR has ``pages``, ASR has
``segments``; where OCR flags ``low_confidence_words``, ASR flags
``low_confidence_segments``.

Confidence is Whisper's own, passed through, not a score invented on top of it
(#478): each segment's ``avg_logprob`` -- the mean log-probability of the tokens
Whisper decoded for it -- is reported as ``confidence = exp(avg_logprob)``, the
same number out of log space (the geometric-mean token probability, 0-1).

Signature mirrors OCR's ``extract_text`` (agreed with R2 on PR #509): raw ``bytes``
in, never raises, a typed failure ``reason``, and a cap that truncates rather than
refuses. Whether a raw object is audio is decided by the caller
(``RawObjectEvent.modality``), not guessed here. See this package's README.
"""

from __future__ import annotations

import io
import logging
import math
import threading
import time
from enum import Enum
from typing import TYPE_CHECKING, Any

from pydantic import BaseModel, Field, computed_field

from mind.config import ASRSettings
from mind.multimodal.result import MediaTextResult
from mind.observability.tracking import record_call

if TYPE_CHECKING:
    import numpy as np

logger = logging.getLogger(__name__)

# How the model is run -- the configuration #135 measured (benchmarks/asr_benchmark.py
# keeps its own copies; tests/test_asr_settings_sync.py fails if the two differ).
COMPUTE_TYPE = "int8"  # CTranslate2's CPU quantization
BEAM_SIZE = 5  # faster-whisper's default, stated so runs are reproducible
# Set explicitly: left at 0, faster-whisper honors OMP_NUM_THREADS, which another
# library in the process can lower to 1 (PaddleOCR did, in the benchmark) and
# silently run Whisper at ~4x the latency.
CPU_THREADS = 4

# Whisper's input rate; every recording is resampled to 16 kHz mono first.
SAMPLE_RATE = 16_000

# How much of a recording transcribe() reads by default. Longer audio is truncated,
# not refused (as OCR does with MAX_PAGES), and the result says so (`truncated`,
# `duration_available_s`): R2's extraction can still get tasks out of the first ten
# minutes of a meeting, scored as a partial transcript. ASR runs synchronously inside
# the caller's Kafka handler, so the cap is what keeps a call under librdkafka's
# default max.poll.interval.ms of 300 s (teams/mind sets none): #135 measured
# base.en at RTF ~0.1 on a quiet dev laptop (4 threads) and up to ~0.3 under load,
# i.e. ~60 s typical and ~180 s worst-measured for 600 s of audio. Those numbers are
# from a laptop, not the cluster -- on fewer cores RTF grows, which is what
# TRANSCRIBE_TIMEOUT_S below is for.
MAX_DURATION_S = 600

# Longest the decoding loop may run before transcribe() stops and returns what it
# has so far (truncated, see ASRResult) -- the bound a consumer sizes its handler
# against, like OCR's MAX_PAGES * TESSERACT_TIMEOUT_S. Checked between segments
# (Whisper yields one every ~30 s of audio at most, a few seconds of compute), so a
# call can overrun it by about one segment. 240 s leaves ~60 s of the 300 s poll
# interval for decoding the audio and, on a first call, loading the model.
TRANSCRIBE_TIMEOUT_S = 240

# A segment whose confidence is below this is flagged as low confidence. Not a
# cut-off of ours: it is Whisper's own quality gate, faster-whisper's default
# `log_prob_threshold=-1.0` (a segment with avg_logprob below -1.0 is the one
# Whisper itself re-decodes at a higher temperature), moved out of log space so it
# compares with `confidence`. exp(-1.0) ~= 0.368. Like OCR's threshold, a starting
# point to calibrate against real extraction impact, not a validated detector --
# `mean_confidence` and every segment's own `confidence` let a caller choose its
# own cut-off later without a new ASR run.
WHISPER_LOG_PROB_THRESHOLD = -1.0
LOW_CONFIDENCE_THRESHOLD = math.exp(WHISPER_LOG_PROB_THRESHOLD)


class ASRFailureReason(str, Enum):
    """Why a transcription failed, so the caller can react differently to each.

    Same three groups as OCRFailureReason, with the same names where the meaning
    is the same.

    Data failures -- the recording itself is the problem; retrying gives the same
    result, so the caller can drop it and move on:
      EMPTY_INPUT          no bytes at all
      UNREADABLE_FORMAT    not decodable audio (corrupt, truncated, or not audio at
                           all), decided by the audio decoder before Whisper runs
      NO_SPEECH_DETECTED   valid audio with nothing to transcribe (silence, or too
                           short / too noisy for Whisper to find speech in)

    Environment failures -- nothing is wrong with the recording; the caller must
    not treat it as processed (e.g. commit a Kafka offset past it):
      ASR_UNAVAILABLE      the model weights or faster-whisper are not installed
      ASR_FAILED           Whisper failed on audio already proven decodable. "May
                           succeed later", not "will": bound the retries

    A bug in our own code -- not retryable; logged with the traceback, and the
    recording should be parked (dead-lettered) rather than silently dropped:
      INTERNAL_ERROR       an unexpected exception inside transcribe()
    """

    EMPTY_INPUT = "EMPTY_INPUT"
    UNREADABLE_FORMAT = "UNREADABLE_FORMAT"
    NO_SPEECH_DETECTED = "NO_SPEECH_DETECTED"
    ASR_UNAVAILABLE = "ASR_UNAVAILABLE"
    ASR_FAILED = "ASR_FAILED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class TranscriptSegment(BaseModel):
    """One segment Whisper decoded: its text, where it is, and how sure Whisper was.

    ``start`` / ``end`` are seconds from the start of the recording.
    ``confidence`` is ``exp(avg_logprob)`` (see the module docstring), 0-1.
    ``no_speech_prob`` is Whisper's own probability that the segment is not
    speech at all -- passed through as well, since a confident transcript of
    background noise is the ASR version of OCR's "confident wrong word".
    """

    text: str
    start: float = Field(ge=0)
    end: float = Field(ge=0)
    confidence: float = Field(ge=0, le=1)
    no_speech_prob: float = Field(ge=0, le=1)

    @classmethod
    def from_whisper(
        cls, *, text: str, start: float, end: float, avg_logprob: float, no_speech_prob: float
    ) -> TranscriptSegment:
        """Build from a faster-whisper Segment's fields, converting avg_logprob to 0-1."""
        return cls(
            text=text.strip(),
            start=start,
            end=end,
            confidence=min(1.0, math.exp(avg_logprob)),
            no_speech_prob=no_speech_prob,
        )

    @property
    def is_low_confidence(self) -> bool:
        return self.confidence < LOW_CONFIDENCE_THRESHOLD


class ASRResult(MediaTextResult[ASRFailureReason]):
    """Result of transcribe(). Always returned, never raised past this module.

    On success: ``text`` is every segment's text joined with a newline (the
    convenience view, one line per segment); ``segments`` holds each segment
    with its timestamps and its own ``confidence``, in order.
    ``mean_confidence`` is over all segments, ``low_confidence_segments`` the
    ones below LOW_CONFIDENCE_THRESHOLD, with ``low_confidence_count`` their
    number -- ``len(segments)`` is the denominator.

    A recording longer than the duration cap is truncated, not refused (as OCR
    does with pages): only the first ``duration_s`` seconds are transcribed,
    ``duration_available_s`` is the recording's real length, and ``truncated`` is
    True -- a signal that the transcript is a partial recording.

    ``success`` / ``reason`` / ``error`` / ``retryable`` come from
    MediaTextResult, the envelope OCR and ASR share (see result.py).
    """

    RETRYABLE_REASONS = frozenset({ASRFailureReason.ASR_UNAVAILABLE, ASRFailureReason.ASR_FAILED})

    segments: list[TranscriptSegment] = Field(default_factory=list)
    duration_s: float = 0.0
    duration_available_s: float = 0.0

    @computed_field  # type: ignore[prop-decorator]
    @property
    def mean_confidence(self) -> float | None:
        if not self.segments:
            return None
        return round(sum(s.confidence for s in self.segments) / len(self.segments), 4)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def low_confidence_segments(self) -> list[TranscriptSegment]:
        return [s for s in self.segments if s.is_low_confidence]

    @computed_field  # type: ignore[prop-decorator]
    @property
    def low_confidence_count(self) -> int:
        return len(self.low_confidence_segments)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def truncated(self) -> bool:
        return self.duration_available_s > self.duration_s


def _failure(reason: ASRFailureReason, error: str) -> ASRResult:
    return ASRResult(success=False, reason=reason, error=error)


class _ASRUnavailable(Exception):
    """The model weights or faster-whisper are missing: the environment."""


class _NotAudio(Exception):
    """The bytes are not decodable audio: the recording."""


_model_lock = threading.Lock()
_model: tuple[str, Any] | None = None  # (model name for record_call, WhisperModel)


def _load_model() -> tuple[str, Any]:
    """The Whisper model, loaded once per process from a local folder.

    Never downloads (see ASRSettings). A failed load is not cached, so a fixed
    environment (weights mounted after start-up) works without a restart.
    """
    global _model
    with _model_lock:
        if _model is None:
            settings = ASRSettings()
            path = settings.model_dir
            if not (path / "model.bin").exists():
                raise _ASRUnavailable(
                    f"Whisper weights not found at {path} (expected model.bin there) -- "
                    "see models/README.md"
                )
            try:
                from faster_whisper import WhisperModel
            except ImportError as exc:
                raise _ASRUnavailable(f"faster-whisper is not installed: {exc}") from exc
            try:
                model = WhisperModel(
                    str(path), device="cpu", compute_type=COMPUTE_TYPE, cpu_threads=CPU_THREADS
                )
            except (RuntimeError, OSError, ValueError) as exc:
                # CTranslate2 refusing the folder: missing/corrupt files, wrong format
                raise _ASRUnavailable(f"could not load Whisper from {path}: {exc}") from exc
            _model = (f"whisper-{settings.whisper_model}-{COMPUTE_TYPE}-local", model)
        return _model


def _decode(content: bytes, max_duration_s: float) -> tuple[np.ndarray, float]:
    """The first `max_duration_s` seconds as 16 kHz mono float32, and the real length.

    Done before Whisper runs, so a bad file is UNREADABLE_FORMAT and a later
    failure inside Whisper can only be the environment (the same split as OCR's
    _checked_image). Samples past the cap are decoded only to count them -- the
    real length goes in `duration_available_s` -- and are not kept, so a two-hour
    recording does not sit in memory. Raises _NotAudio on anything PyAV cannot
    read as audio.
    """
    import av
    import numpy as np

    max_samples = int(max_duration_s * SAMPLE_RATE)
    kept: list[np.ndarray] = []
    kept_samples = 0
    total_samples = 0
    try:
        with av.open(io.BytesIO(content), mode="r") as container:
            if not container.streams.audio:
                raise _NotAudio("no audio stream in the file")
            resampler = av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)
            frames = container.decode(container.streams.audio[0])
            # a None frame at the end flushes what the resampler still buffers
            for frame in [*frames, None]:
                for out in resampler.resample(frame):
                    total_samples += out.samples
                    if kept_samples < max_samples:
                        chunk = out.to_ndarray().reshape(-1)[: max_samples - kept_samples]
                        kept.append(chunk)
                        kept_samples += len(chunk)
    except av.error.FFmpegError as exc:
        raise _NotAudio(str(exc)) from exc
    audio = np.concatenate(kept) if kept else np.zeros(0, dtype=np.int16)
    return audio.astype(np.float32) / 32768.0, total_samples / SAMPLE_RATE


def transcribe(
    content: bytes, *, filename: str = "", max_duration_s: float = MAX_DURATION_S
) -> ASRResult:
    """Transcribe a recording with the local Whisper model (see the module docstring).

    Args:
        content: Raw file bytes (e.g. as landed by Bridge) -- not a path. Any
            format FFmpeg reads (wav, mp3, m4a, ogg, webm, mp4 audio...); the
            format is detected from the bytes themselves.
        filename: Original filename, if known. Only used in error messages;
            safe to omit.
        max_duration_s: How many seconds of audio to transcribe (more than 0). A
            longer recording is truncated, not refused: the result carries
            ``truncated=True`` and ``duration_available_s``.

    Raises ValueError if ``max_duration_s <= 0`` (a programming error, not a
    recording failure). Otherwise it always returns an ASRResult and never
    raises: empty, undecodable or silent audio, or missing/failing weights, come
    back as ``success=False`` with a classified ``reason`` (see ASRFailureReason).

    Every call that reaches the model is recorded through
    ``observability.record_call()`` (latency; cost 0.0 until MIND has a cost model
    for local compute, #479), whether it succeeded or not.
    """
    if max_duration_s <= 0:
        raise ValueError(f"max_duration_s must be more than 0, got {max_duration_s}")
    try:
        return _transcribe(content, filename, max_duration_s)
    except Exception as exc:
        # Everything expected is classified inside _transcribe(); reaching here
        # means a bug in this module. Not retryable, and logged with the traceback.
        logger.exception("unexpected error in transcribe")
        return _failure(ASRFailureReason.INTERNAL_ERROR, f"unexpected {type(exc).__name__}: {exc}")


def _transcribe(content: bytes, filename: str, max_duration_s: float) -> ASRResult:
    if not content:
        return _failure(ASRFailureReason.EMPTY_INPUT, "empty input")

    try:
        model_name, model = _load_model()
    except _ASRUnavailable as exc:
        return _failure(ASRFailureReason.ASR_UNAVAILABLE, str(exc))

    try:
        audio, duration_available_s = _decode(content, max_duration_s)
    except _NotAudio as exc:
        name = f" {filename!r}" if filename else ""
        return _failure(
            ASRFailureReason.UNREADABLE_FORMAT, f"could not read input{name} as audio: {exc}"
        )
    if len(audio) == 0:
        return _failure(ASRFailureReason.NO_SPEECH_DETECTED, "the recording has no audio samples")

    segments: list[TranscriptSegment] = []
    timed_out = False
    start = time.perf_counter()
    cpu_start = time.process_time()
    try:
        # vad_filter: Whisper's own voice-activity filter (Silero, bundled with
        # faster-whisper) drops silence before decoding. Without it Whisper tends
        # to "hear" a stock phrase in silence or noise -- a confident, made-up
        # transcript, which is worse than NO_SPEECH_DETECTED.
        found, _info = model.transcribe(audio, language="en", beam_size=BEAM_SIZE, vad_filter=True)
        for seg in found:  # lazy: the decoding happens while iterating
            if seg.text.strip():
                segments.append(
                    TranscriptSegment.from_whisper(
                        text=seg.text,
                        start=seg.start,
                        end=seg.end,
                        avg_logprob=seg.avg_logprob,
                        no_speech_prob=seg.no_speech_prob,
                    )
                )
            if time.perf_counter() - start > TRANSCRIBE_TIMEOUT_S:
                timed_out = True
                break
    except (RuntimeError, MemoryError, OSError) as exc:
        # The audio was already decoded, so this is CTranslate2/onnxruntime
        # failing: the environment (out of memory, a broken install).
        return _failure(ASRFailureReason.ASR_FAILED, f"Whisper failed: {exc}")
    finally:
        duration_ms = (time.perf_counter() - start) * 1000
        record_call(model_name, duration_ms, cost=0.0)
        audio_s = len(audio) / SAMPLE_RATE
        logger.info(
            "asr_call model=%s audio_s=%.1f rtf=%.3f cpu_s=%.1f segments=%d timed_out=%s",
            model_name,
            audio_s,
            duration_ms / 1000 / audio_s,
            time.process_time() - cpu_start,
            len(segments),
            timed_out,
        )

    if not segments:
        if timed_out:
            return _failure(
                ASRFailureReason.ASR_FAILED, f"no segment decoded within {TRANSCRIBE_TIMEOUT_S} s"
            )
        return _failure(
            ASRFailureReason.NO_SPEECH_DETECTED,
            "no speech recognized (the recording may be silent or only noise)",
        )

    # Cut short by the timeout: the transcript covers up to the last decoded
    # segment, so `truncated` reports it the same way the duration cap does.
    duration_s = segments[-1].end if timed_out else len(audio) / SAMPLE_RATE
    return ASRResult(
        success=True,
        text="\n".join(s.text for s in segments),
        segments=segments,
        duration_s=round(duration_s, 3),
        duration_available_s=round(duration_available_s, 3),
    )
