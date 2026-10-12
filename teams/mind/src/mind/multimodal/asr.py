"""Local ASR: audio / meeting recordings -> transcript + a per-segment confidence signal.

Uses Whisper ``base.en`` via faster-whisper (int8, CPU) -- the model decision made
in ``benchmarks/ocr-asr-benchmark.md`` "ASR -- final decision" (#135), not reopened
here.

The result has the same envelope as OCR (``MediaTextResult``, see result.py, and
the shared failure vocabulary in failures.py): R2's ``bridge.raw.landed`` consumer
(#349) calls OCR or ASR by ``modality`` and both feed the same extraction pipeline,
so ``success`` / ``reason`` / ``error`` / ``failure_category`` / ``retryable`` mean
the same thing in both. Where OCR has ``pages``, ASR has ``segments``; where OCR
flags ``low_confidence_words``, ASR flags ``low_confidence_segments``.

Confidence is Whisper's own, passed through, not a score invented on top of it
(#478): every segment carries ``avg_logprob``, ``compression_ratio`` and
``no_speech_prob`` as Whisper computed them, plus ``confidence = exp(avg_logprob)``,
the same number out of log space (0-1). See TranscriptSegment for the scales and
for why these are per decoding window rather than per segment.

Signature mirrors OCR's ``extract_text`` (agreed with R2 on PR #509): raw ``bytes``
in, never raises, a typed failure ``reason``, and a cap that truncates rather than
refuses. Whether a raw object is audio is decided by the caller
(``RawObjectEvent.modality``), not guessed here. See this package's README.
"""

from __future__ import annotations

import hashlib
import io
import logging
import math
import threading
import time
from collections.abc import Iterator, Mapping
from enum import Enum
from itertools import pairwise
from pathlib import Path
from typing import TYPE_CHECKING, Any, ClassVar, NamedTuple

from pydantic import BaseModel, Field, computed_field

from mind.config import ASRSettings
from mind.multimodal.failures import FailureCategory
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


class PinnedModel(NamedTuple):
    repo: str
    revision: str
    model_sha256: str  # of model.bin, as Hugging Face publishes it for that revision


# The exact weights a pod may run, per size: the Dockerfile bakes this revision
# and checks this hash at build time (tests/test_asr_settings_sync.py keeps the two
# in step), and _verified() checks it again on every load. The hash is the one the
# #135 benchmark's weights were verified against.
PINNED_WHISPER_MODELS = {
    "base.en": PinnedModel(
        repo="Systran/faster-whisper-base.en",
        revision="3d3d5dee26484f91867d81cb899cfcf72b96be6c",
        model_sha256="2a166925539a16005f14ff328359f9b9adb9dc4fb631bb3b227526862e93e2ef",
    ),
}

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

# The recording is transcribed in chunks of at most this many seconds, each cut in a
# silence, not as one piece. Memory: faster-whisper computes its features over the
# whole input at once, so peak memory grew ~0.9 MiB per second of audio (~790 MiB
# for 600 s); in chunks it is bounded by the chunk, ~440-550 MiB on the same audio,
# whatever the recording's length. Accuracy: cut in silences, rough WER against
# AMI's human transcript matched unchunked within noise (mean 0.261 vs 0.258 on the
# first 600 s of the two #135 meetings, two runs each); fixed 60 s cuts were worse
# (0.267, and up to +4 points on one earlier run), since they split words. See
# benchmarks/ocr-asr-benchmark.md "ASR -- chunking" and benchmarks/asr_chunking.py.
CHUNK_S = 60
# A chunk is cut at the latest silence in its window, but not before this point: a
# silence in the first seconds would give a tiny chunk and many more of them. With
# no silence after it, the chunk is hard-cut at CHUNK_S.
MIN_CHUNK_S = 30

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
    """One segment Whisper decoded: its text, where it is, and Whisper's own scores for it.

    ``start`` / ``end``: seconds from the start of the recording (not of the chunk)
    -- the audio counterpart of OCR's page number, for extraction provenance.

    Whisper's scores, passed through unchanged (#478, R2):

    - ``avg_logprob``: the mean log-probability of the tokens Whisper decoded,
      **natural log, <= 0** (0 = every token certain; -1.0 = a geometric-mean token
      probability of ~0.37). Not on Tesseract's 0-100 scale, so OCR's cut-off of 60
      has no counterpart here.
    - ``compression_ratio``: how much the text compresses (gzip). Whisper's
      characteristic failure is a confident loop -- the same phrase repeated on a
      quiet or hard stretch -- which avg_logprob can miss and this catches: Whisper
      itself treats a value above 2.4 as a failed decode and retries it.
    - ``no_speech_prob``: Whisper's probability (0-1) that there was no speech --
      a confident transcript of background noise is the ASR version of OCR's
      "confident wrong word".

    **Granularity:** faster-whisper computes all three once per ~30 s decoding
    window and gives every segment of that window the same values -- they are not
    per segment. An aggregate over segments should weight by duration (as
    ``ASRResult.mean_confidence`` does), or several short segments of one window
    count it several times.

    ``confidence`` is ``exp(avg_logprob)``: the same number out of log space, 0-1,
    derived here rather than stored, so the two cannot disagree.
    """

    text: str
    start: float = Field(ge=0)
    end: float = Field(ge=0)
    avg_logprob: float
    compression_ratio: float = Field(ge=0)
    no_speech_prob: float = Field(ge=0, le=1)

    @classmethod
    def from_whisper(cls, seg: Any, *, offset_s: float = 0.0) -> TranscriptSegment:
        """Build from a faster-whisper Segment, from `offset_s` into the recording."""
        return cls(
            text=seg.text.strip(),
            start=offset_s + seg.start,
            end=offset_s + seg.end,
            avg_logprob=seg.avg_logprob,
            compression_ratio=seg.compression_ratio,
            no_speech_prob=seg.no_speech_prob,
        )

    @computed_field  # type: ignore[prop-decorator]
    @property
    def confidence(self) -> float:
        return min(1.0, math.exp(self.avg_logprob))

    @property
    def is_low_confidence(self) -> bool:
        return self.confidence < LOW_CONFIDENCE_THRESHOLD


class ASRResult(MediaTextResult[ASRFailureReason]):
    """Result of transcribe(). Always returned, never raised past this module.

    On success: ``text`` is every segment's text joined with a newline (the
    convenience view, one line per segment); ``segments`` holds each segment
    with its timestamps and Whisper's scores, in order.
    ``mean_confidence`` is the duration-weighted mean of the segments'
    ``confidence`` (see TranscriptSegment on why weighted), ``low_confidence_segments``
    the ones below LOW_CONFIDENCE_THRESHOLD, with ``low_confidence_count`` their
    number -- ``len(segments)`` is the denominator.

    A recording longer than the duration cap is truncated, not refused (as OCR
    does with pages): only the first ``duration_s`` seconds are transcribed,
    ``duration_available_s`` is the recording's real length, and ``truncated`` is
    True -- a signal that the transcript is a partial recording.

    ``success`` / ``reason`` / ``error`` / ``failure_category`` / ``retryable``
    come from MediaTextResult, the envelope OCR and ASR share (see result.py and
    failures.py).
    """

    FAILURE_CATEGORIES: ClassVar[Mapping[Enum, FailureCategory]] = {
        ASRFailureReason.EMPTY_INPUT: FailureCategory.DATA,
        ASRFailureReason.UNREADABLE_FORMAT: FailureCategory.DATA,
        ASRFailureReason.NO_SPEECH_DETECTED: FailureCategory.DATA,
        ASRFailureReason.ASR_UNAVAILABLE: FailureCategory.ENVIRONMENT,
        ASRFailureReason.ASR_FAILED: FailureCategory.ENVIRONMENT,
        ASRFailureReason.INTERNAL_ERROR: FailureCategory.BUG,
    }

    segments: list[TranscriptSegment] = Field(default_factory=list)
    duration_s: float = 0.0
    duration_available_s: float = 0.0

    @computed_field  # type: ignore[prop-decorator]
    @property
    def mean_confidence(self) -> float | None:
        if not self.segments:
            return None
        weights = [s.end - s.start for s in self.segments]
        if sum(weights) <= 0:  # only zero-length segments: fall back to a plain mean
            weights = [1.0] * len(self.segments)
        weighted = sum(w * s.confidence for w, s in zip(weights, self.segments, strict=True))
        return round(weighted / sum(weights), 4)

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


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _verified(path: Path, size: str) -> Path:
    """`path`, after checking its model.bin is the pinned one (when `size` is pinned).

    Runs on every load, whichever way the weights got there -- baked into the
    image, placed by hand, or downloaded just now -- so a pod can only ever run
    the model #135 measured (team-lead review on #478). Once per process: ~0.5 s
    for base.en's 145 MB.
    """
    pinned = PINNED_WHISPER_MODELS.get(size)
    if pinned is None:
        # Not a model we pinned (e.g. tiny.en / small.en from the benchmark):
        # nothing to check against. Loaded from a local folder only, never
        # downloaded -- see _model_path().
        logger.warning("Whisper %s is not pinned; loading %s without a sha256 check", size, path)
        return path
    actual = _sha256(path / "model.bin")
    if actual != pinned.model_sha256:
        raise _ASRUnavailable(
            f"{path / 'model.bin'} has sha256 {actual}, expected {pinned.model_sha256} "
            f"({pinned.repo}@{pinned.revision}) -- corrupt or a different model"
        )
    return path


def _model_path(settings: ASRSettings) -> Path:
    """Where to load the weights from, downloading them only as a local-dev fallback.

    1. ``WHISPER_MODELS_DIR/<size>/``: the copy baked into the image (Dockerfile).
    2. ``MODEL_CACHE_DIR/whisper/<size>/``: local dev, placed by hand (models/README.md).
    3. Neither has model.bin: download the pinned revision into (2), like the
       embedding model does. Never reached in the image, which has (1); and
       never for an unpinned size, which has no revision or checksum to trust.
    """
    size = settings.whisper_model
    candidates = [settings.download_dir]
    if settings.whisper_models_dir:
        candidates.insert(0, Path(settings.whisper_models_dir) / size)
    for path in candidates:
        if (path / "model.bin").exists():
            return path

    pinned = PINNED_WHISPER_MODELS.get(size)
    if pinned is None:
        raise _ASRUnavailable(
            f"Whisper weights not found at {' or '.join(map(str, candidates))}, and {size} "
            "is not a pinned model, so it is not downloaded -- see models/README.md"
        )
    target = settings.download_dir
    logger.info("Whisper %s not found locally; downloading %s to %s", size, pinned.repo, target)
    try:
        from faster_whisper import download_model

        download_model(pinned.repo, output_dir=str(target), revision=pinned.revision)
    except ImportError as exc:
        raise _ASRUnavailable(f"faster-whisper is not installed: {exc}") from exc
    except Exception as exc:
        # The network layer under huggingface_hub raises its own, version-dependent
        # errors (HTTP, connection, a proxy such as NetFree answering 418, a full
        # disk); all of them are the environment, not the recording.
        raise _ASRUnavailable(
            f"Whisper weights not found locally and the download of {pinned.repo} failed: "
            f"{type(exc).__name__}: {exc} -- see models/README.md to place them by hand"
        ) from exc
    return target


def _load_model() -> tuple[str, Any]:
    """The Whisper model, loaded once per process and checked against its pinned sha256.

    See _model_path() for where it comes from. A failed load is not cached, so a
    fixed environment (weights mounted after start-up) works without a restart.
    """
    global _model
    with _model_lock:
        if _model is None:
            settings = ASRSettings()
            path = _verified(_model_path(settings), settings.whisper_model)
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


def _speech_regions(window: np.ndarray) -> list[tuple[int, int]]:
    """Where `window` has speech, as (start, end) sample offsets into it.

    Whisper's own VAD (Silero, bundled with faster-whisper), the same one
    vad_filter=True runs. Called on one CHUNK_S window at a time, never on the
    whole recording: it scores the whole input in one pass, so its memory grows
    with the input like Whisper's own does. Each region is already padded with
    silence on both sides (VadOptions.speech_pad_ms), so a cut in a gap between
    regions is clear of the words around it.
    """
    from faster_whisper.vad import get_speech_timestamps

    return [(s["start"], s["end"]) for s in get_speech_timestamps(window)]


def _chunks(audio: np.ndarray) -> Iterator[tuple[int, int, bool]]:
    """(start, end, has_speech) sample ranges covering `audio`, each cut in a silence.

    Each window of CHUNK_S is cut in the middle of its latest silence gap that
    lies past MIN_CHUNK_S -- between two speech regions, or after the last one --
    else hard-cut at CHUNK_S. A chunk with no speech at all is still yielded
    (has_speech=False) so the caller can skip it: silence never reaches Whisper,
    which is where it would invent text.
    """
    window_len = CHUNK_S * SAMPLE_RATE
    min_cut = MIN_CHUNK_S * SAMPLE_RATE
    start = 0
    while start < len(audio):
        end = min(start + window_len, len(audio))
        speech = _speech_regions(audio[start:end])
        if end < len(audio) and speech:
            gaps = [(a_end, b_start) for (_, a_end), (b_start, _) in pairwise(speech)]
            gaps.append((speech[-1][1], end - start))  # after the last speech region
            cuts = [(g0 + g1) // 2 for g0, g1 in gaps if g1 > g0 and (g0 + g1) // 2 >= min_cut]
            if cuts:
                end = start + max(cuts)
        yield start, end, bool(speech)
        start = end


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

    Every call whose audio decodes is recorded through
    ``observability.record_call()`` -- the latency of the VAD and Whisper stage, cost
    0.0 until MIND has a cost model for local compute (#479) -- whether it
    succeeded or not, and even when the VAD found no speech for Whisper to run on.
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
        for chunk_start, chunk_end, has_speech in _chunks(audio):
            if time.perf_counter() - start > TRANSCRIBE_TIMEOUT_S:
                timed_out = True
                break
            if not has_speech:
                continue
            offset_s = chunk_start / SAMPLE_RATE
            # vad_filter: Whisper's own voice-activity filter drops the silences
            # inside the chunk too. Without it Whisper tends to "hear" a stock
            # phrase in silence or noise -- a confident, made-up transcript, which
            # is worse than NO_SPEECH_DETECTED.
            found, _info = model.transcribe(
                audio[chunk_start:chunk_end], language="en", beam_size=BEAM_SIZE, vad_filter=True
            )
            for seg in found:  # lazy: the decoding happens while iterating
                if seg.text.strip():
                    segments.append(
                        # timestamps from the start of the recording, not the chunk
                        TranscriptSegment.from_whisper(seg, offset_s=offset_s)
                    )
                if time.perf_counter() - start > TRANSCRIBE_TIMEOUT_S:
                    timed_out = True
                    break
            if timed_out:
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
