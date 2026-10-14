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

# A header-declared length longer than what decoded by more than this marks the
# file incomplete: max(0.5 s, 5%). Not zero, because encoders pad (an MP3 of the
# speech fixture decodes 5 ms short of its header; its M4A 20 ms long), and an MP3
# without a Xing header only has a bitrate-based estimate of its length.
DECLARED_LENGTH_TOLERANCE = (0.5, 0.05)  # (seconds, fraction of the declared length)

# Segments ending this close to a break in the file are dropped (R2 on #478): the
# sentence at the break is cut mid-word, which is where Whisper invents an ending
# or loops on it. A margin rather than "the last segment", because the break can
# fall in a silence after a complete sentence: one that ended more than
# BREAK_MARGIN_S before the break is kept.
BREAK_MARGIN_S = 1.0

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

    How much of the recording the transcript covers -- two independent flags,
    stored rather than derived from the durations (R2 on #478: she weights them
    differently, a break being evidence about the data and a cut only saying the
    end was not read). Both can be True at once.

    - ``truncated``: **we** stopped before the end -- the ``max_duration_s`` cap,
      or TRANSCRIBE_TIMEOUT_S. Set from what transcribe() did, never from a length
      comparison, so a broken file can never look like a deliberate cut.
    - ``incomplete_file``: the **file** is shorter than it says it is -- a decode
      error after valid audio, or a header that declares more audio than the file
      holds (a WAV's RIFF data size; the container's length for MP3/M4A), by more
      than DECLARED_LENGTH_TOLERANCE. What was decoded is transcribed, minus the
      segments that touch the break (BREAK_MARGIN_S): a sentence cut there is where
      Whisper invents or loops. Caveat: decoding stops at the cap, so for MP3/M4A a
      break *past* the cap cannot be seen -- False means "no break found in what
      was read". For WAV the header check needs no decoding, so it covers the
      whole file.

    ``duration_s`` is how much was transcribed. ``duration_available_s`` is the
    recording's length as far as it is known, and ``duration_available_exact``
    says how far that is:

    - True: the file was decoded to its end, so ``duration_available_s`` is
      exactly the audio it holds (for a broken file, what survived).
    - False: decoding stopped at the cap. ``duration_available_s`` is the length
      the file's header declares (exact for M4A, close for MP3, from the data
      size for WAV), or, without one, just what was read: "at least this much".
      **Not verified, not necessarily wrong.**

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
    duration_available_exact: bool = False
    truncated: bool = False
    incomplete_file: bool = False

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


def _model_name(settings: ASRSettings | None = None) -> str:
    """The name record_call() files a call under -- known without loading the model,
    since a call can end (silence, a bad file) before the model is ever needed."""
    size = (settings or ASRSettings()).whisper_model
    return f"whisper-{size}-{COMPUTE_TYPE}-local"


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
            _model = (_model_name(settings), model)
        return _model


class _Decoded(NamedTuple):
    """What _decode() read, and what it could tell about the rest of the file."""

    audio: np.ndarray  # 16 kHz mono float32, at most max_duration_s of it
    reached_end: bool  # decoded to the end of the stream (False: stopped at the cap)
    broke: bool  # a decode error after valid audio: everything after it is lost
    declared_s: float | None  # the length the file's header claims, if it claims one
    present_s: float | None  # WAV only: the length its data bytes actually hold


def _wav_extent(content: bytes) -> tuple[float, float] | None:
    """(declared, present) seconds of a PCM WAV, from its RIFF header and its size.

    FFmpeg recomputes a WAV's length from the file size, so a WAV cut short --
    the recorder crashed, the copy stopped -- decodes cleanly and reports the
    shorter length as if it were the whole recording. Only the header's data-chunk
    size still says how long it was meant to be. O(1): no decoding. None when the
    bytes are not a RIFF/WAVE file, or the header has no usable size (0 or
    0xFFFFFFFF are what streaming writers put when the length is not known yet).
    """
    if len(content) < 12 or content[:4] != b"RIFF" or content[8:12] != b"WAVE":
        return None
    offset, byte_rate = 12, 0
    while offset + 8 <= len(content):
        chunk_id = content[offset : offset + 4]
        size = int.from_bytes(content[offset + 4 : offset + 8], "little")
        if chunk_id == b"fmt " and offset + 16 <= len(content):
            byte_rate = int.from_bytes(content[offset + 16 : offset + 20], "little")
        elif chunk_id == b"data":
            if not byte_rate or size in (0, 0xFFFFFFFF):
                return None
            present = min(size, len(content) - (offset + 8))
            return size / byte_rate, present / byte_rate
        offset += 8 + size + (size & 1)  # chunks are padded to an even size
    return None


def _decode(content: bytes, max_duration_s: float) -> _Decoded:
    """The first `max_duration_s` seconds as 16 kHz mono float32 -- and no more.

    Done before Whisper runs, so a bad file is UNREADABLE_FORMAT and a later
    failure inside Whisper can only be the environment (the same split as OCR's
    _checked_image). Decoding stops at the cap: past it nothing is decoded, not
    even to count it -- a three-hour MP3 costs ~35 s to decode in full on the dev
    laptop, time TRANSCRIBE_TIMEOUT_S does not cover. The length of the rest
    comes from the file's header instead (see _Decoded, ASRResult).

    A decode error after valid audio keeps the audio before it (``broke``)
    rather than losing the whole recording; an error before any audio is
    _NotAudio, like anything PyAV cannot read as audio at all.
    """
    import av
    import numpy as np

    max_samples = int(max_duration_s * SAMPLE_RATE)
    # One float32 buffer, written in place as frames decode -- not a list of chunks
    # concatenated, converted and scaled afterwards, which peaked at ~3x the audio
    # (112 MiB for 37 MiB of it at the 600 s cap). Sized by the header's length
    # when it has one (most files), grown by doubling when it is short or missing.
    audio = np.empty(0, dtype=np.float32)
    kept_samples = 0
    reached_end = True
    broke = False
    declared_s: float | None = None

    def append(chunk: np.ndarray) -> None:
        nonlocal audio, kept_samples
        end = kept_samples + len(chunk)
        if end > len(audio):
            grown = np.empty(min(max_samples, max(end, 2 * len(audio))), dtype=np.float32)
            grown[:kept_samples] = audio[:kept_samples]
            audio = grown
        np.multiply(chunk, 1 / 32768, out=audio[kept_samples:end], casting="unsafe")
        kept_samples = end

    try:
        with av.open(io.BytesIO(content), mode="r") as container:
            if not container.streams.audio:
                raise _NotAudio("no audio stream in the file")
            if container.duration:
                declared_s = container.duration / 1_000_000  # AV_TIME_BASE: microseconds
            expected = int((declared_s or 0) * SAMPLE_RATE) + SAMPLE_RATE  # + 1 s of slack
            audio = np.empty(min(max_samples, expected), dtype=np.float32)
            resampler = av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)
            frames = container.decode(container.streams.audio[0])
            try:
                while kept_samples < max_samples:
                    frame = next(frames, None)
                    # a None frame at the end flushes what the resampler still buffers
                    for out in resampler.resample(frame):
                        append(out.to_ndarray().reshape(-1)[: max_samples - kept_samples])
                    if frame is None:
                        break
                else:
                    # At the cap: is there more? One more frame answers it (an error
                    # here is a break right at the cap; either way, stop decoding).
                    reached_end = next(frames, None) is None
            except av.error.FFmpegError as exc:
                if not kept_samples:
                    raise
                logger.warning(
                    "audio decode broke after %.1f s: %s", kept_samples / SAMPLE_RATE, exc
                )
                broke = True
    except av.error.FFmpegError as exc:
        raise _NotAudio(str(exc)) from exc

    wav = _wav_extent(content)
    if wav is not None:
        declared_s = wav[0]  # the RIFF header, not FFmpeg's size-based recomputation
    # A slice is a view, so a buffer sized for a much longer file (a broken one,
    # whose header overstates it) would stay allocated behind it: copy that case.
    used = audio[:kept_samples]
    if kept_samples < len(audio) // 2:
        used = used.copy()
    return _Decoded(
        audio=used,
        reached_end=reached_end or broke,
        broke=broke,
        declared_s=declared_s,
        present_s=None if wav is None else wav[1],
    )


class _Extent(NamedTuple):
    incomplete_file: bool
    duration_available_s: float
    duration_available_exact: bool
    break_s: float | None  # where in the decoded audio the file breaks, if it does


def _extent(decoded: _Decoded) -> _Extent:
    """How long the recording is, and whether the file is shorter than it claims.

    The rules ASRResult documents for ``incomplete_file`` and
    ``duration_available_s`` / ``duration_available_exact``, in one place.
    """
    decoded_s = len(decoded.audio) / SAMPLE_RATE
    seconds, fraction = DECLARED_LENGTH_TOLERANCE

    def short_of_declared(actual_s: float) -> bool:
        declared = decoded.declared_s
        return declared is not None and declared - actual_s > max(seconds, fraction * declared)

    if decoded.reached_end:
        incomplete = decoded.broke or short_of_declared(decoded_s)
        return _Extent(incomplete, decoded_s, True, decoded_s if incomplete else None)

    # Stopped at the cap: the rest was not decoded, so its length is the header's.
    if decoded.present_s is not None:  # WAV: what its data bytes hold, no decoding needed
        return _Extent(
            short_of_declared(decoded.present_s), max(decoded_s, decoded.present_s), False, None
        )
    return _Extent(False, max(decoded_s, decoded.declared_s or 0.0), False, None)


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


def warm_up() -> bool:
    """Load the Whisper model (sha256-checked) and the VAD now, not on the first call.

    For the consumer to call once at start-up (R2 on #478): otherwise the first
    audio message pays ~1.5 s of loading inside its Kafka handler, on top of the
    transcription. Never raises, like transcribe(): ASR is a fallback path, and a
    broken ASR environment must not stop a consumer that also serves OCR and
    text-native sources. Returns whether ASR is ready; on False the reason is
    logged, and transcribe() tries the load again (a failed load is not cached),
    reporting ASR_UNAVAILABLE if it still fails.
    """
    try:
        _load_model()
        from faster_whisper.vad import get_vad_model

        get_vad_model()  # cached by faster-whisper; loads the Silero ONNX session
    except _ASRUnavailable as exc:
        logger.warning("ASR not ready at start-up: %s", exc)
        return False
    except Exception:
        logger.exception("unexpected error warming up ASR")
        return False
    return True


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
        max_duration_s: How many seconds of audio to decode and transcribe (more
            than 0). A longer recording is truncated, not refused: the result
            carries ``truncated=True``, and nothing past the cap is decoded.

    Raises ValueError if ``max_duration_s <= 0`` (a programming error, not a
    recording failure). Otherwise it always returns an ASRResult and never
    raises: empty, undecodable or silent audio, or missing/failing weights, come
    back as ``success=False`` with a classified ``reason`` (see ASRFailureReason).
    A file cut off mid-way is transcribed up to its break and comes back with
    ``incomplete_file=True`` -- never as if it were the whole recording (see
    ASRResult for how ``truncated``, ``incomplete_file`` and the durations are set).

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

    # The input is checked before the model is touched, and the model is loaded
    # only once a chunk with speech needs it (team-lead review on the PR): decoding
    # and the VAD need no Whisper weights. Loading first made a junk file on a host
    # without the weights ASR_UNAVAILABLE -- ENVIRONMENT, retried forever -- when
    # the file itself is the problem (DATA). It also kept every input-check test
    # from running without the weights, i.e. in CI.
    try:
        decoded = _decode(content, max_duration_s)
    except _NotAudio as exc:
        name = f" {filename!r}" if filename else ""
        return _failure(
            ASRFailureReason.UNREADABLE_FORMAT, f"could not read input{name} as audio: {exc}"
        )
    audio = decoded.audio
    if len(audio) == 0:
        return _failure(ASRFailureReason.NO_SPEECH_DETECTED, "the recording has no audio samples")
    extent = _extent(decoded)

    segments: list[TranscriptSegment] = []
    timed_out = False
    model_name, model = _model_name(), None
    start = time.perf_counter()
    cpu_start = time.process_time()
    try:
        for chunk_start, chunk_end, has_speech in _chunks(audio):
            if time.perf_counter() - start > TRANSCRIBE_TIMEOUT_S:
                timed_out = True
                break
            if not has_speech:
                continue
            if model is None:
                model_name, model = _load_model()  # first chunk with speech
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
    except _ASRUnavailable as exc:
        # Decodable audio with speech in it, and no model to transcribe it with.
        return _failure(ASRFailureReason.ASR_UNAVAILABLE, str(exc))
    except (RuntimeError, MemoryError, OSError) as exc:
        # The audio was already decoded, so this is CTranslate2/onnxruntime
        # failing: the environment (out of memory, a broken install).
        return _failure(ASRFailureReason.ASR_FAILED, f"Whisper failed: {exc}")
    finally:
        duration_ms = (time.perf_counter() - start) * 1000
        record_call(model_name, duration_ms, cost=0.0)
        audio_s = len(audio) / SAMPLE_RATE
        logger.info(
            "asr_call model=%s audio_s=%.1f rtf=%.3f cpu_s=%.1f segments=%d timed_out=%s "
            "capped=%s incomplete_file=%s",
            model_name,
            audio_s,
            duration_ms / 1000 / audio_s,
            time.process_time() - cpu_start,
            len(segments),
            timed_out,
            not decoded.reached_end,
            extent.incomplete_file,
        )

    dropped_at_break = 0
    if extent.break_s is not None:
        # The sentence at the break is cut mid-word: drop it rather than pass on
        # whatever Whisper made of it (see BREAK_MARGIN_S) -- whether or not the
        # timeout also hit. It used to be skipped on a timeout, which let the half
        # sentence through exactly when the timeout hit on the segment at the break
        # (team-lead review on the PR); a timeout earlier in the file leaves every
        # segment well before the break, so the filter drops nothing there anyway.
        kept = [s for s in segments if s.end < extent.break_s - BREAK_MARGIN_S]
        dropped_at_break = len(segments) - len(kept)
        if dropped_at_break:
            logger.info(
                "dropped %d segment(s) at the break at %.1f s", dropped_at_break, extent.break_s
            )
        segments = kept

    if not segments:
        no_complete_segment = (
            f"the file breaks at {extent.duration_available_s:.1f} s and no complete "
            "speech segment comes before the break"
        )
        # Before the timeout: if segments were decoded and the break took them all,
        # the time did not run out empty-handed -- the broken file left nothing
        # usable. That is DATA (drop it), not ASR_FAILED, which is ENVIRONMENT and
        # would retry the same broken file.
        if dropped_at_break:
            return _failure(ASRFailureReason.NO_SPEECH_DETECTED, no_complete_segment)
        if timed_out:  # nothing decoded at all in the time: the environment
            return _failure(
                ASRFailureReason.ASR_FAILED, f"no segment decoded within {TRANSCRIBE_TIMEOUT_S} s"
            )
        if extent.incomplete_file:
            return _failure(ASRFailureReason.NO_SPEECH_DETECTED, no_complete_segment)
        return _failure(
            ASRFailureReason.NO_SPEECH_DETECTED,
            "no speech recognized (the recording may be silent or only noise)",
        )

    # Cut short by the timeout: the transcript covers up to the last decoded segment.
    duration_s = segments[-1].end if timed_out else len(audio) / SAMPLE_RATE
    return ASRResult(
        success=True,
        text="\n".join(s.text for s in segments),
        segments=segments,
        duration_s=round(duration_s, 3),
        duration_available_s=round(extent.duration_available_s, 3),
        duration_available_exact=extent.duration_available_exact,
        # what we did, never a length comparison (see ASRResult)
        truncated=timed_out or not decoded.reached_end,
        incomplete_file=extent.incomplete_file,
    )
