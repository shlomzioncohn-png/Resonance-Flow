"""Local OCR: scanned documents (image or PDF) -> text + a per-word low-confidence signal.

Uses ``tesseract --psm 11`` (sparse text) -- the model decision made in
``benchmarks/ocr-asr-benchmark.md`` and ``benchmarks/ocr_asr_benchmark.py``,
not reopened here. Runs Tesseract in TSV output mode instead of plain text
so each recognized word's own confidence score (0-100) is available: text
Tesseract is unsure about is reported alongside the text instead of being
indistinguishable from a confident (and possibly wrong) read -- see
``benchmarks/ocr-asr-benchmark.md`` "per-word confidence" for why this
matters (a confident wrong word is the case a corrupted-file check can't
catch).

NOTE: ``extract_text``'s signature is being agreed with R2 (issue #449), who
calls it from her ``bridge.raw.landed`` consumer (#349). Agreed so far: it takes
a file path, and a failure carries a classified ``reason``. Still open: the
shape of the low-confidence signal, per-page text, and how a scanned document
is recognised (``source_type``, #188) -- see this package's README.
"""

from __future__ import annotations

import io
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Literal

import pypdfium2 as pdfium
from pydantic import BaseModel, model_validator

from mind.api.instrumentation import instrumented_call

# Tesseract page-segmentation args -- the OCR decision (psm 11, sparse text; see
# benchmarks/ocr-asr-benchmark.md). Defined here, not imported from benchmarks/
# (service code doesn't depend on the benchmark scripts); the benchmark's own
# CHOSEN_PSM_ARGS is a separate copy, and tests/test_ocr_pipeline.py fails if
# the two ever differ, so a future PSM change can't leave one of them behind.
OCR_PSM_ARGS = ["--psm", "11"]

# Confidence below which a recognized word is flagged as "low confidence"
# instead of trusted outright. A starting point, not a validated detector:
# from benchmarks/analyze_confidence.py on the 2 FUNSD documents in
# local-data/funsd/, correctly-read words average 80.4 and the miss groups
# 45-63 (different_word 45.4, not_detected 47.2, char_misread 52.4,
# digit_merge 59.1, segmentation 62.8) -- but the distributions overlap
# heavily. At 60, 12% of correctly-read words are flagged and about 55% of
# the real errors (char_misread + different_word + not_detected) are caught;
# no cut-off does both (80 catches 81% of errors but flags 39% of correct
# words). digit_merge and segmentation misses (correct text, wrong word
# boundaries) sit on either side of 60 and are flagged only partly. Choose
# the final cut-off against real downstream impact once extraction exists.
# See benchmarks/ocr-asr-benchmark.md "per-word confidence" for the tables.
LOW_CONFIDENCE_THRESHOLD = 60.0

_WINDOWS_DEFAULT_INSTALL = r"C:\Program Files\Tesseract-OCR\tesseract.exe"

# Render scale for PDF pages: ~200 DPI (200/72), well above what a scanned
# form needs for Tesseract to read it, without producing an unreasonably
# large intermediate image.
_PDF_RENDER_SCALE = 200 / 72


class LowConfidenceWord(BaseModel):
    text: str
    confidence: float


# Why an extraction failed, so the caller can react differently to each:
#   file_not_found  -- the path does not exist (a pipeline bug, not a bad document)
#   file_unreadable -- the path exists but could not be read (a directory, permissions)
#   empty_input     -- the file is empty
#   corrupt_input   -- not a readable image or PDF
#   unreadable_scan -- a valid image/PDF in which no text was recognised (blank or too poor)
#   ocr_unavailable -- the tesseract binary is not installed (an environment problem)
#   ocr_failed      -- tesseract ran and failed or timed out for another reason
FailureReason = Literal[
    "file_not_found",
    "file_unreadable",
    "empty_input",
    "corrupt_input",
    "unreadable_scan",
    "ocr_unavailable",
    "ocr_failed",
]


class OCRResult(BaseModel):
    """Result of extract_text(). Always returned, never raised past this module.

    ``success=False`` means "could not extract" -- ``reason`` says why (a
    classified code to branch on) and ``error`` is the human-readable message --
    as distinct from a successful extraction that happens to contain
    low-confidence words (``success=True``, ``low_confidence_words`` non-empty).
    ``reason`` is set exactly when ``success`` is False.
    """

    success: bool
    text: str = ""
    low_confidence_words: list[LowConfidenceWord] = []
    reason: FailureReason | None = None
    error: str | None = None

    @model_validator(mode="after")
    def _reason_iff_failure(self) -> OCRResult:
        if self.success and (self.reason is not None or self.error is not None):
            raise ValueError("a successful result carries no reason or error")
        if not self.success and (self.reason is None or not self.error):
            raise ValueError("a failed result needs both a reason and an error message")
        return self


def _failure(reason: FailureReason, error: str) -> OCRResult:
    return OCRResult(success=False, reason=reason, error=error)


def _find_tesseract() -> str:
    found = shutil.which("tesseract")
    if found:
        return found
    if Path(_WINDOWS_DEFAULT_INSTALL).exists():
        return _WINDOWS_DEFAULT_INSTALL
    raise RuntimeError("tesseract not found on PATH or at the default Windows install path")


def _is_pdf(content: bytes, filename: str) -> bool:
    return content[:5] == b"%PDF-" or filename.lower().endswith(".pdf")


def _pdf_to_page_images(content: bytes) -> list[bytes]:
    """Render each PDF page to PNG bytes, at a resolution Tesseract can read."""
    pdf = pdfium.PdfDocument(io.BytesIO(content))
    page_images = []
    for page in pdf:
        bitmap = page.render(scale=_PDF_RENDER_SCALE)
        buf = io.BytesIO()
        bitmap.to_pil().save(buf, format="PNG")
        page_images.append(buf.getvalue())
    return page_images


class _TesseractRejectedImage(Exception):
    """Tesseract exited non-zero on an image: it could not read it."""


def _run_tesseract_tsv(tesseract_cmd: str, image_bytes: bytes) -> str:
    """Run tesseract --psm 11 in TSV mode on one page image, return raw TSV."""
    fd, tmp_path = tempfile.mkstemp(suffix=".png")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(image_bytes)
        cmd = [tesseract_cmd, tmp_path, "stdout", *OCR_PSM_ARGS, "tsv"]
        proc = subprocess.run(cmd, capture_output=True, timeout=60, check=False)
    finally:
        os.unlink(tmp_path)
    if proc.returncode != 0:
        stderr = proc.stderr.decode("utf-8", errors="replace").strip()
        raise _TesseractRejectedImage(stderr or f"tesseract exited {proc.returncode}")
    return proc.stdout.decode("utf-8", errors="replace")


def _parse_tsv(tsv: str) -> tuple[str, list[LowConfidenceWord]]:
    """-> (extracted text, words scoring below LOW_CONFIDENCE_THRESHOLD).

    TSV level 5 = an individual recognized word (see
    benchmarks/analyze_confidence.py, which established this parsing).
    """
    words: list[str] = []
    low_confidence: list[LowConfidenceWord] = []
    for line in tsv.splitlines()[1:]:  # skip header row
        fields = line.split("\t")
        if len(fields) != 12:
            continue
        level, conf_str, text = fields[0], fields[10], fields[11]
        if level != "5" or not text.strip():
            continue
        conf = float(conf_str)
        if conf < 0:  # Tesseract's own "not applicable" sentinel
            continue
        words.append(text)
        if conf < LOW_CONFIDENCE_THRESHOLD:
            low_confidence.append(LowConfidenceWord(text=text, confidence=conf))
    return " ".join(words), low_confidence


def extract_text(path: str | os.PathLike[str]) -> OCRResult:
    """Extract text from a scanned document (image or PDF) via tesseract --psm 11.

    Args:
        path: Path to the file on local disk (an image, or a PDF -- told apart
            by its ``%PDF-`` magic bytes, or failing that its ``.pdf`` suffix).

    Always returns an OCRResult, never raises: a missing, unreadable, empty or
    corrupted file, or an unreadable scan, comes back as ``success=False`` with
    a classified ``reason`` (see FailureReason) and an ``error`` message --
    not an exception and not garbage text.

    Every successful result carries ``low_confidence_words`` -- words
    Tesseract itself scored below LOW_CONFIDENCE_THRESHOLD -- so a caller
    (extraction) can flag or discount them instead of trusting all OCR
    output equally.
    """
    file_path = Path(path)
    try:
        content = file_path.read_bytes()
    except FileNotFoundError:
        return _failure("file_not_found", f"no such file: {file_path}")
    except OSError as exc:
        return _failure("file_unreadable", f"could not read {file_path}: {exc}")
    return _extract_from_bytes(content, filename=file_path.name)


def _extract_from_bytes(content: bytes, *, filename: str) -> OCRResult:
    if not content:
        return _failure("empty_input", "empty input")

    try:
        tesseract_cmd = _find_tesseract()
    except RuntimeError as exc:
        return _failure("ocr_unavailable", str(exc))

    try:
        # Deliberately broad: pypdfium2/PIL can raise several different
        # internal error types for a malformed file, and the contract here
        # is "never raise, always return a result" -- see module docstring.
        page_images = _pdf_to_page_images(content) if _is_pdf(content, filename) else [content]
    except Exception as exc:  # noqa: BLE001
        return _failure("corrupt_input", f"could not read input as image or PDF: {exc}")

    if not page_images:
        return _failure("corrupt_input", "PDF has no pages")

    page_texts: list[str] = []
    all_low_confidence: list[LowConfidenceWord] = []
    try:
        with instrumented_call("tesseract-psm11-ocr"):
            for image_bytes in page_images:
                tsv = _run_tesseract_tsv(tesseract_cmd, image_bytes)
                text, low_confidence = _parse_tsv(tsv)
                if text:
                    page_texts.append(text)
                all_low_confidence.extend(low_confidence)
    except _TesseractRejectedImage as exc:
        return _failure("corrupt_input", f"tesseract could not read the image: {exc}")
    except Exception as exc:  # noqa: BLE001 -- boundary: never raise, see module docstring
        return _failure("ocr_failed", f"tesseract failed: {exc}")

    text = "\n".join(page_texts)
    if not text:
        return _failure("unreadable_scan", "no text recognized (scan may be unreadable or blank)")

    return OCRResult(success=True, text=text, low_confidence_words=all_low_confidence)
