"""Run the real OCR and ASR on the demo's binary artifacts, on their own.

    python -m demo_flow.check_media

Not the pipeline: this calls mind.multimodal directly, to show before a run
that each artifact reads back with the words the expected answer depends on.
A phrase missing here will be missing for the consumer too. Needs the
tesseract binary and the Whisper base.en weights (models/README.md).
"""

import sys
from pathlib import Path

from demo_flow import common
from mind.multimodal.asr import transcribe
from mind.multimodal.ocr import extract_text

# The words expected.json's tasks hang on, per artifact. Matched ignoring case.
OCR_PHRASES = [
    "northwind traders",
    "supplier registration form",
    "authorized signature",
    "return the completed and signed form",
    "procurement@northwind.example",
]
ASR_PHRASES = [
    "northwind",
    "price quote",
    "thursday",
    "cost sheet",
    "wednesday",
    "half a day",
    "dana",
    "omer",
]


def missing_phrases(text: str, phrases: list[str]) -> list[str]:
    lowered = " ".join(text.lower().split())
    return [phrase for phrase in phrases if phrase not in lowered]


def _report(name: str, path: Path, text: str, confidence: float | None, phrases: list[str]) -> bool:
    print(f"\n=== {name}: {path.name} (mean confidence {confidence}) ===")
    print(text)
    missing = missing_phrases(text, phrases)
    print(f"--- {len(phrases) - len(missing)}/{len(phrases)} expected phrases found", end="")
    print(f"; missing: {missing}" if missing else "")
    return not missing


def main() -> int:
    for path in (common.FORM_IMAGE, common.MEETING_AUDIO):
        if not path.exists():
            print(f"{path} does not exist -- run `python -m demo_flow.build_artifacts` first")
            return 1

    ocr = extract_text(common.FORM_IMAGE.read_bytes(), filename=common.FORM_IMAGE.name)
    if not ocr.success:
        print(f"OCR failed: {ocr.reason} {ocr.error}")
        return 1
    ocr_ok = _report("OCR", common.FORM_IMAGE, ocr.text, ocr.mean_confidence, OCR_PHRASES)

    asr = transcribe(common.MEETING_AUDIO.read_bytes(), filename=common.MEETING_AUDIO.name)
    if not asr.success:
        print(f"ASR failed: {asr.reason} {asr.error}")
        return 1
    asr_ok = _report("ASR", common.MEETING_AUDIO, asr.text, asr.mean_confidence, ASR_PHRASES)

    return 0 if ocr_ok and asr_ok else 1


if __name__ == "__main__":
    sys.exit(main())
