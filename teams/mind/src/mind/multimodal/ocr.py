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

NOTE: ``extract_text``'s signature is a working draft. Issue #449 calls for
agreeing it with R2 before her ``bridge.raw.landed`` consumer (#349) is
built against it -- treat it as a starting point for that conversation, not
a settled contract.
"""

from __future__ import annotations

import io
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

import pypdfium2 as pdfium
from pydantic import BaseModel

from mind.api.instrumentation import instrumented_call

# Confidence below which a recognized word is flagged as "low confidence"
# instead of trusted outright. Tuned against benchmarks/analyze_confidence.py's
# output on the 2 FUNSD documents in local-data/funsd/: correctly-recalled
# words average confidence 80.4; every miss category scores below that, with
# the dangerous one -- Tesseract confidently producing a real but *wrong*
# word (`different_word`) -- lowest at 46.3, followed by not_detected (47.2)
# and char_misread (53.0). 60 sits comfortably above all three of those with
# margin, while staying below digit_merge (61.9) and segmentation (63.8) --
# both of which the error analysis found don't need flagging anyway, since
# Tesseract's text was already correct there (a boundary/merge issue, not a
# wrong reading). See benchmarks/ocr-asr-benchmark.md "per-word confidence"
# for the full table this is drawn from.
LOW_CONFIDENCE_THRESHOLD = 60.0

_WINDOWS_DEFAULT_INSTALL = r"C:\Program Files\Tesseract-OCR\tesseract.exe"

# Render scale for PDF pages: ~200 DPI (200/72), well above what a scanned
# form needs for Tesseract to read it, without producing an unreasonably
# large intermediate image.
_PDF_RENDER_SCALE = 200 / 72


class LowConfidenceWord(BaseModel):
    text: str
    confidence: float


class OCRResult(BaseModel):
    """Result of extract_text(). Always returned, never raised past this module.

    ``success=False`` means "could not extract" -- corrupted input, an
    empty/blank page, or a scan Tesseract couldn't read -- as distinct from
    a successful extraction that happens to contain low-confidence words
    (``success=True``, ``low_confidence_words`` non-empty).
    """

    success: bool
    text: str = ""
    low_confidence_words: list[LowConfidenceWord] = []
    error: str | None = None


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


def _run_tesseract_tsv(tesseract_cmd: str, image_bytes: bytes) -> str:
    """Run tesseract --psm 11 in TSV mode on one page image, return raw TSV."""
    fd, tmp_path = tempfile.mkstemp(suffix=".png")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(image_bytes)
        cmd = [tesseract_cmd, tmp_path, "stdout", "--psm", "11", "tsv"]
        proc = subprocess.run(cmd, capture_output=True, timeout=60, check=False)
    finally:
        os.unlink(tmp_path)
    if proc.returncode != 0:
        stderr = proc.stderr.decode("utf-8", errors="replace").strip()
        raise ValueError(stderr or f"tesseract exited {proc.returncode}")
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


def extract_text(content: bytes, *, filename: str = "") -> OCRResult:
    """Extract text from a scanned document (image or PDF) via tesseract --psm 11.

    Args:
        content: Raw file bytes (e.g. as landed by Bridge) -- not a path.
        filename: Original filename, if known. Used only to help tell a PDF
            from an image when the content itself doesn't start with the
            ``%PDF-`` magic bytes; safe to omit.

    Always returns an OCRResult, never raises: a corrupted file, empty
    input, or unreadable scan comes back as ``success=False`` with
    ``error`` set, not an exception and not garbage text.

    Every successful result carries ``low_confidence_words`` -- words
    Tesseract itself scored below LOW_CONFIDENCE_THRESHOLD -- so a caller
    (extraction) can flag or discount them instead of trusting all OCR
    output equally.
    """
    if not content:
        return OCRResult(success=False, error="empty input")

    try:
        tesseract_cmd = _find_tesseract()
    except RuntimeError as exc:
        return OCRResult(success=False, error=str(exc))

    try:
        # Deliberately broad: pypdfium2/PIL can raise several different
        # internal error types for a malformed file, and the contract here
        # is "never raise, always return a result" -- see module docstring.
        page_images = _pdf_to_page_images(content) if _is_pdf(content, filename) else [content]
    except Exception as exc:  # noqa: BLE001
        return OCRResult(success=False, error=f"could not read input as image or PDF: {exc}")

    if not page_images:
        return OCRResult(success=False, error="PDF has no pages")

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
    except Exception as exc:  # noqa: BLE001 -- boundary: never raise, see module docstring
        return OCRResult(success=False, error=f"tesseract failed: {exc}")

    text = "\n".join(page_texts)
    if not text:
        return OCRResult(success=False, error="no text recognized (scan may be unreadable or blank)")

    return OCRResult(success=True, text=text, low_confidence_words=all_low_confidence)
