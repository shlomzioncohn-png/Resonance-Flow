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
"""

from __future__ import annotations

import math
from enum import Enum

from pydantic import BaseModel, Field, computed_field

from mind.multimodal.result import MediaTextResult

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
