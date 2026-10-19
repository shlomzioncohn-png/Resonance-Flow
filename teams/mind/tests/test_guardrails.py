"""Guardrail eval (#488): deliberately bad input, checked against eval/guardrails/cases.json.

Each case's acceptable outcomes were written into cases.json before the case was
run; these tests only classify what a component returned and check it against
that list. They never fix or tune the component under test: a case that fails
here is a finding for its owner, not something to patch in this file.

The text cases replay a live LLM's recorded answers (eval/guardrails/recorded/,
written by scripts/record_guardrail_responses.py) through ScriptedProvider, so
R2's current parsing and confidence scoring run on what a real model said.

OCR and ASR run the real tesseract / Whisper and skip on a machine without them,
like test_ocr_pipeline.py and test_asr_pipeline.py.

A case whose observed behaviour is a reported finding is marked
xfail(strict=True): it stays visible on every run without blocking the suite,
and it turns into a failure the day the owner's change makes it pass, so the
audit report gets updated rather than silently going stale.
"""

from __future__ import annotations

import io
import json
import wave
from pathlib import Path
from typing import Any

import numpy as np
import pytest
from PIL import Image

from mind.contracts.base_enums import SourceType
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess
from mind.multimodal.asr import ASRResult, transcribe
from mind.multimodal.failures import FailureCategory
from mind.multimodal.ocr import OCRResult, extract_text
from tests.extraction.fakes import ScriptedProvider

_ROOT = Path(__file__).resolve().parent.parent
_GUARDRAILS = _ROOT / "eval" / "guardrails"
_CASES = json.loads((_GUARDRAILS / "cases.json").read_text(encoding="utf-8"))
_CLEAN_RECORDING = _ROOT / "tests" / "fixtures" / "asr" / "short_meeting_note.wav"
_WHISPER_DIR = _ROOT / "models" / "whisper" / "base.en"

try:
    from mind.multimodal.ocr import _find_tesseract

    _find_tesseract()
    _TESSERACT_AVAILABLE = True
except RuntimeError:
    _TESSERACT_AVAILABLE = False

_needs_tesseract = pytest.mark.skipif(
    not _TESSERACT_AVAILABLE, reason="tesseract system binary not installed on this machine"
)
_needs_whisper = pytest.mark.skipif(
    not (_WHISPER_DIR / "model.bin").exists(),
    reason="Whisper base.en weights not present locally (see models/README.md)",
)

# Delay of the overlapping copy in the cross-talk case. Long enough that the two
# copies speak different words at the same moment, short enough that most of the
# recording overlaps (the fixture is ~4.1 s).
_CROSSTALK_DELAY_S = 1.0


@pytest.fixture(autouse=True)
def _whisper_env(monkeypatch: pytest.MonkeyPatch) -> None:
    # Same pinning as test_asr_pipeline.py: the model the decision names, read
    # from the repo's models/ folder rather than a local .env.
    monkeypatch.setenv("WHISPER_MODEL", "base.en")
    monkeypatch.delenv("WHISPER_MODELS_DIR", raising=False)
    monkeypatch.setenv("MODEL_CACHE_DIR", str(_ROOT / "models"))


def _case(case_id: str) -> dict[str, Any]:
    [case] = [c for c in _CASES["cases"] if c["id"] == case_id]
    return case


def _media_outcome(result: OCRResult | ASRResult, threshold: float) -> str:
    """Name what a media pipeline did, in cases.json's outcome vocabulary.

    Only a DATA failure counts as graceful: the input was bad and the pipeline
    said so. An ENVIRONMENT or BUG failure on a bad input is not the pipeline
    recognising the input, so it is reported as a crash.
    """
    if not result.success:
        return "graceful_failure" if result.failure_category is FailureCategory.DATA else "crash"
    assert result.mean_confidence is not None, "a successful result must carry its confidence"
    return "low_confidence" if result.mean_confidence < threshold else "silent"


def _noise_png(seed: int, size: int = 400) -> bytes:
    """Black/white pixel noise with no text in it, identical on every run."""
    rng = np.random.default_rng(seed)
    pixels = (rng.integers(0, 2, size=(size, size)) * 255).astype(np.uint8)
    buf = io.BytesIO()
    Image.fromarray(pixels).save(buf, format="PNG")
    return buf.getvalue()


def _crosstalk_wav(clean: bytes, delay_s: float) -> bytes:
    """The clean recording mixed with a copy of itself delayed by `delay_s`."""
    with wave.open(io.BytesIO(clean), "rb") as src:
        assert src.getnchannels() == 1 and src.getsampwidth() == 2, "expects 16-bit mono"
        rate = src.getframerate()
        samples = np.frombuffer(src.readframes(src.getnframes()), dtype=np.int16)

    delay = int(delay_s * rate)
    mixed = np.zeros(len(samples) + delay, dtype=np.float64)
    mixed[: len(samples)] += samples
    mixed[delay:] += samples
    mixed = np.clip(mixed * 0.5, -32768, 32767).astype(np.int16)

    buf = io.BytesIO()
    with wave.open(buf, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(mixed.tobytes())
    return buf.getvalue()


def _replay_extraction(case_id: str) -> tuple[str, dict[str, Any]]:
    """Run R2's extractor on a case's recorded answers; return (outcome, details)."""
    case = _case(case_id)
    recorded = json.loads((_GUARDRAILS / "recorded" / f"{case_id}.json").read_text(encoding="utf-8"))

    result = BasicExtractor(ScriptedProvider(*recorded["answers"]), sleep=lambda _: None).extract(
        case["input"]["text"], SourceType.MEETING
    )

    if not isinstance(result, ExtractionSuccess):
        return "graceful_failure", {"reason": result.reason}
    details = {"title": result.fields.title, "confidence": result.confidence.value}
    outcome = "low_confidence" if result.confidence.value < case["threshold"] else "silent"
    return outcome, details


# Observed against the live model on the first recording (see the recorded files):
# a task invented from small talk, and a Hebrew task silently translated to English,
# both above the 0.5 audit marker. The extraction confidence scores extraction
# quality, not whether a task exists or is in scope.
_EXTRACTION_FINDING = (
    "finding (#488): extraction has no signal for 'no task here' or 'not English'; "
    "the output carries confidence >= 0.5"
)


@pytest.mark.parametrize(
    "case_id",
    [
        pytest.param(
            "no_action_items",
            marks=pytest.mark.xfail(strict=True, raises=AssertionError, reason=_EXTRACTION_FINDING),
        ),
        pytest.param(
            "non_english_text",
            marks=pytest.mark.xfail(strict=True, raises=AssertionError, reason=_EXTRACTION_FINDING),
        ),
    ],
)
def test_extraction_does_not_return_a_confident_task_from_bad_input(case_id: str) -> None:
    outcome, details = _replay_extraction(case_id)

    assert outcome in _case(case_id)["acceptable"], f"{outcome}: {details}"


@_needs_tesseract
def test_noise_scan_is_not_read_as_confident_text() -> None:
    case = _case("noise_scan")

    result = extract_text(_noise_png(case["input"]["seed"]), filename="noise.png")

    outcome = _media_outcome(result, case["threshold"])
    assert outcome in case["acceptable"], (
        f"{outcome}: reason={result.reason}, mean_confidence={result.mean_confidence}, "
        f"text={result.text[:80]!r}"
    )


@_needs_whisper
def test_crosstalk_lowers_asr_confidence_against_the_clean_recording() -> None:
    case = _case("crosstalk_audio")
    clean_bytes = _CLEAN_RECORDING.read_bytes()

    clean = transcribe(clean_bytes, filename="short_meeting_note.wav")
    mixed = transcribe(_crosstalk_wav(clean_bytes, _CROSSTALK_DELAY_S), filename="crosstalk.wav")

    assert clean.success, f"the clean reference must transcribe: {clean.error}"
    assert clean.mean_confidence is not None

    outcome = _media_outcome(mixed, case["threshold"])
    if outcome == "silent" and mixed.mean_confidence is not None:
        # Above R4's threshold: acceptable only if confidence still reacted.
        outcome = "partial" if mixed.mean_confidence < clean.mean_confidence else "silent"

    assert outcome in case["acceptable"], (
        f"{outcome}: clean={clean.mean_confidence:.3f}, mixed={mixed.mean_confidence}, "
        f"mixed text={mixed.text[:80]!r}"
    )


@_needs_whisper
def test_non_english_audio_is_not_transcribed_as_confident_english() -> None:
    case = _case("non_english_audio")
    recording = _ROOT / case["input"]["path"]
    if not recording.exists():
        pytest.skip(f"{case['input']['path']} not recorded yet")

    result = transcribe(recording.read_bytes(), filename=recording.name)

    outcome = _media_outcome(result, case["threshold"])
    assert outcome in case["acceptable"], (
        f"{outcome}: reason={result.reason}, mean_confidence={result.mean_confidence}, "
        f"text={result.text[:80]!r}"
    )
