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

The signature was agreed with R2 (Slack, confirmed on PR #509), who calls it from her
``bridge.raw.landed`` consumer (#349): raw ``bytes`` in (her consumer already
reads the object to verify its content hash), a typed failure ``reason`` so a
data failure can be told from an environment one, per-document counts
(``word_count``, ``low_confidence_count``, ``mean_confidence``) so the
low-confidence list has a denominator, text per page, and a page cap. Whether a
document is scanned is decided by the caller (``RawObjectEvent.modality``), not
guessed here -- ``filename`` is only a secondary hint for telling a PDF from an
image. See this package's README.
"""

from __future__ import annotations

import io
import logging
import os
import shutil
import subprocess
import tempfile
from enum import Enum
from pathlib import Path

import pypdfium2 as pdfium
from PIL import Image
from pydantic import BaseModel, computed_field, model_validator

from mind.api.instrumentation import instrumented_call

logger = logging.getLogger(__name__)

# Tesseract page-segmentation args -- the OCR decision (psm 11, sparse text; see
# benchmarks/ocr-asr-benchmark.md). Defined here, not imported from benchmarks/
# (service code doesn't depend on the benchmark scripts); the benchmark's own
# CHOSEN_PSM_ARGS is a separate copy, and tests/test_ocr_psm_sync.py fails if
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
# the final cut-off against real downstream impact once extraction exists;
# OCRResult.mean_confidence lets a caller calibrate later without a new OCR run.
# See benchmarks/ocr-asr-benchmark.md "per-word confidence" for the tables.
LOW_CONFIDENCE_THRESHOLD = 60.0

# Longest PDF extract_text() will OCR by default; more pages fails with
# TOO_MANY_PAGES before any page is rendered. OCR runs synchronously inside the
# caller's Kafka handler, so its cost is time the consumer is blocked: measured
# on the dev machine, about 2.6 s per PDF page (rasterizing at ~200 DPI plus
# Tesseract, a FUNSD-sized form page; a bare image is faster, ~0.5 s), so 10
# pages is about 26 s. A guess at a sensible default, not a measured limit --
# callers can pass their own `max_pages`.
MAX_PAGES = 10

_WINDOWS_DEFAULT_INSTALL = r"C:\Program Files\Tesseract-OCR\tesseract.exe"

# Render scale for PDF pages: ~200 DPI (200/72), well above what a scanned
# form needs for Tesseract to read it, without producing an unreasonably
# large intermediate image.
_PDF_RENDER_SCALE = 200 / 72


class LowConfidenceWord(BaseModel):
    text: str
    confidence: float


class OCRFailureReason(str, Enum):
    """Why an extraction failed, so the caller can react differently to each.

    Data failures -- the document itself is the problem; retrying gives the same
    result, so the caller can drop it and move on:
      EMPTY_INPUT         no bytes at all
      UNREADABLE_FORMAT   not a readable image or PDF (corrupt, truncated, or another
                          format), decided by PIL/pypdfium2 before tesseract runs
      NO_TEXT_RECOGNIZED  a valid image/PDF in which no text was found (blank or too poor)
      TOO_MANY_PAGES      a PDF longer than `max_pages`

    Environment failures -- nothing is wrong with the document; the caller must
    not treat the document as processed (e.g. commit a Kafka offset past it):
      OCR_UNAVAILABLE     the tesseract binary is not installed
      OCR_FAILED          tesseract exited non-zero, timed out, or could not be run on a
                          valid image -- the environment (e.g. a missing language
                          pack), never the document, since the image was already
                          proven readable. "May succeed later", not "will": a caller
                          must bound its retries (a page that always times out
                          fails every time)

    A bug in our own code -- neither the document's fault nor the environment's, so
    not retryable (retrying the same document fails the same way, and would block
    everything behind it); logged with the traceback, and the document should be
    parked (dead-lettered) rather than silently dropped:
      INTERNAL_ERROR      an unexpected exception inside extract_text()
    """

    EMPTY_INPUT = "EMPTY_INPUT"
    UNREADABLE_FORMAT = "UNREADABLE_FORMAT"
    NO_TEXT_RECOGNIZED = "NO_TEXT_RECOGNIZED"
    TOO_MANY_PAGES = "TOO_MANY_PAGES"
    OCR_UNAVAILABLE = "OCR_UNAVAILABLE"
    OCR_FAILED = "OCR_FAILED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


_RETRYABLE_REASONS = frozenset({OCRFailureReason.OCR_UNAVAILABLE, OCRFailureReason.OCR_FAILED})


class OCRResult(BaseModel):
    """Result of extract_text(). Always returned, never raised past this module.

    On success: ``text`` is all pages joined with a newline (the convenience
    view); ``pages`` holds the text of each page, so the index is the page
    number (0-based), with ``""`` for a page where nothing was recognised;
    ``word_count`` / ``mean_confidence`` cover every recognised word, and
    ``low_confidence_words`` the ones below LOW_CONFIDENCE_THRESHOLD, with
    ``low_confidence_count`` their number -- the list alone has no denominator.

    ``success=False`` means "could not extract" -- ``reason`` says why (a
    classified code to branch on), ``error`` is the human-readable message for
    logs, and ``retryable`` is True for environment failures (the document is
    fine; try again once the environment is fixed). ``reason`` is set exactly
    when ``success`` is False.
    """

    success: bool
    text: str = ""
    pages: list[str] = []
    word_count: int = 0
    mean_confidence: float | None = None
    low_confidence_words: list[LowConfidenceWord] = []
    reason: OCRFailureReason | None = None
    error: str | None = None

    @computed_field  # type: ignore[prop-decorator]
    @property
    def low_confidence_count(self) -> int:
        return len(self.low_confidence_words)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def retryable(self) -> bool:
        return self.reason in _RETRYABLE_REASONS

    @model_validator(mode="after")
    def _reason_iff_failure(self) -> OCRResult:
        if self.success and (self.reason is not None or self.error is not None):
            raise ValueError("a successful result carries no reason or error")
        if not self.success and (self.reason is None or not self.error):
            raise ValueError("a failed result needs both a reason and an error message")
        return self


def _failure(reason: OCRFailureReason, error: str) -> OCRResult:
    return OCRResult(success=False, reason=reason, error=error)


class _PageOCR(BaseModel):
    """What one page's TSV yielded."""

    text: str
    word_count: int
    confidence_sum: float
    low_confidence: list[LowConfidenceWord]


class _TooManyPages(Exception):
    def __init__(self, pages: int, max_pages: int) -> None:
        super().__init__(f"PDF has {pages} pages, more than max_pages={max_pages}")


class _TesseractFailed(Exception):
    """Tesseract exited non-zero on an image already proven valid: the environment."""


# What PIL and pypdfium2 raise for a file they cannot read: UnidentifiedImageError
# and a truncated file are OSError, a corrupt PDF is PdfiumError, and
# DecompressionBombError is PIL's oversized-image guard. Listed explicitly, not
# `except Exception`, so a bug of ours in that step is not mislabelled as a bad
# document (and silently dropped) -- it reaches INTERNAL_ERROR instead.
_UNREADABLE_FILE_ERRORS = (
    OSError,
    ValueError,
    SyntaxError,
    Image.DecompressionBombError,
    pdfium.PdfiumError,
)


def _find_tesseract() -> str:
    found = shutil.which("tesseract")
    if found:
        return found
    if Path(_WINDOWS_DEFAULT_INSTALL).exists():
        return _WINDOWS_DEFAULT_INSTALL
    raise RuntimeError("tesseract not found on PATH or at the default Windows install path")


def _is_pdf(content: bytes, filename: str) -> bool:
    return content[:5] == b"%PDF-" or filename.lower().endswith(".pdf")


def _checked_image(content: bytes) -> bytes:
    """`content` itself, after proving it is a real, fully decodable image.

    Done with PIL *before* tesseract runs, so that a later non-zero exit from
    tesseract can only mean the environment is broken (a missing language
    pack, say) -- not "this image is bad". Without this check the two are
    indistinguishable, and a broken install would be reported as a corrupt
    document (UNREADABLE_FORMAT, not retryable), making the caller drop every
    scanned document. `.load()` forces the full decode, so a truncated file
    fails here too. Raises on anything PIL cannot read.
    """
    with Image.open(io.BytesIO(content)) as image:
        image.load()
    return content


def _pdf_to_page_images(content: bytes, max_pages: int) -> list[bytes]:
    """Render each PDF page to PNG bytes, at a resolution Tesseract can read.

    Raises _TooManyPages before rendering anything if the PDF is longer than
    `max_pages` -- rendering is part of the cost the cap exists to bound.
    """
    pdf = pdfium.PdfDocument(io.BytesIO(content))
    if len(pdf) > max_pages:
        raise _TooManyPages(len(pdf), max_pages)
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
        cmd = [tesseract_cmd, tmp_path, "stdout", *OCR_PSM_ARGS, "tsv"]
        proc = subprocess.run(cmd, capture_output=True, timeout=60, check=False)
    finally:
        os.unlink(tmp_path)
    if proc.returncode != 0:
        stderr = proc.stderr.decode("utf-8", errors="replace").strip()
        # The image was already proven valid (_checked_image / our own PDF
        # rendering), so a failure here is the environment, e.g. a missing
        # language pack -- reported as OCR_FAILED by extract_text().
        raise _TesseractFailed(
            f"tesseract exited {proc.returncode}" + (f": {stderr}" if stderr else "")
        )
    return proc.stdout.decode("utf-8", errors="replace")


def _parse_tsv(tsv: str) -> _PageOCR:
    """One page's text, word count, confidence sum, and low-confidence words.

    TSV level 5 = an individual recognized word (see
    benchmarks/analyze_confidence.py, which established this parsing).

    The page text keeps Tesseract's own line breaks: words are grouped by the
    (block_num, par_num, line_num) of their TSV row, spaces within a line and
    a newline between lines. Joining every word with a space instead folds a
    form into one line and mixes the fields of different lines in what the LLM
    extraction later reads (review feedback on PR #509).
    """
    lines: list[list[str]] = []
    line_key: tuple[str, str, str] | None = None
    word_count = 0
    confidence_sum = 0.0
    low_confidence: list[LowConfidenceWord] = []
    for row in tsv.splitlines()[1:]:  # skip header row
        fields = row.split("\t")
        if len(fields) != 12:
            continue
        level, conf_str, text = fields[0], fields[10], fields[11]
        if level != "5" or not text.strip():
            continue
        conf = float(conf_str)
        if conf < 0:  # Tesseract's own "not applicable" sentinel
            continue
        key = (fields[2], fields[3], fields[4])  # block_num, par_num, line_num
        if key != line_key:
            lines.append([])
            line_key = key
        lines[-1].append(text)
        word_count += 1
        confidence_sum += conf
        if conf < LOW_CONFIDENCE_THRESHOLD:
            low_confidence.append(LowConfidenceWord(text=text, confidence=conf))
    return _PageOCR(
        text="\n".join(" ".join(line) for line in lines),
        word_count=word_count,
        confidence_sum=confidence_sum,
        low_confidence=low_confidence,
    )


def extract_text(content: bytes, *, filename: str = "", max_pages: int = MAX_PAGES) -> OCRResult:
    """Extract text from a scanned document (image or PDF) via tesseract --psm 11.

    Args:
        content: Raw file bytes (e.g. as landed by Bridge) -- not a path.
        filename: Original filename, if known. A secondary hint only, used to
            tell a PDF from an image when the content doesn't start with the
            ``%PDF-`` magic bytes; safe to omit. It is not used to decide
            whether the document is scanned -- that is the caller's call.
        max_pages: Longest PDF to OCR; a longer one fails with TOO_MANY_PAGES
            before any page is rendered. A single image counts as one page.

    Always returns an OCRResult, never raises: empty or corrupted input, too
    long a PDF, an unreadable scan, or a missing/failing tesseract comes back
    as ``success=False`` with a classified ``reason`` (see OCRFailureReason),
    an ``error`` message and a ``retryable`` flag -- not an exception and not
    garbage text.

    Every successful result carries the low-confidence signal -- words
    Tesseract itself scored below LOW_CONFIDENCE_THRESHOLD, plus the counts to
    read it against -- so a caller (extraction) can flag or discount them
    instead of trusting all OCR output equally.
    """
    try:
        return _extract(content, filename, max_pages)
    except Exception as exc:
        # Everything expected is classified inside _extract(); reaching here
        # means a bug in this module. Not retryable (see OCRFailureReason), and
        # logged with the traceback so it is not silent.
        logger.exception("unexpected error in extract_text")
        return _failure(OCRFailureReason.INTERNAL_ERROR, f"unexpected {type(exc).__name__}: {exc}")


def _extract(content: bytes, filename: str, max_pages: int) -> OCRResult:
    if not content:
        return _failure(OCRFailureReason.EMPTY_INPUT, "empty input")

    try:
        tesseract_cmd = _find_tesseract()
    except RuntimeError as exc:
        return _failure(OCRFailureReason.OCR_UNAVAILABLE, str(exc))

    try:
        page_images = (
            _pdf_to_page_images(content, max_pages)
            if _is_pdf(content, filename)
            else [_checked_image(content)]
        )
    except _TooManyPages as exc:
        return _failure(OCRFailureReason.TOO_MANY_PAGES, str(exc))
    except _UNREADABLE_FILE_ERRORS as exc:
        return _failure(
            OCRFailureReason.UNREADABLE_FORMAT, f"could not read input as image or PDF: {exc}"
        )

    if not page_images:
        return _failure(OCRFailureReason.UNREADABLE_FORMAT, "PDF has no pages")

    parsed: list[_PageOCR] = []
    try:
        with instrumented_call("tesseract-psm11-ocr"):
            for image_bytes in page_images:
                parsed.append(_parse_tsv(_run_tesseract_tsv(tesseract_cmd, image_bytes)))
    except (_TesseractFailed, subprocess.TimeoutExpired, OSError) as exc:
        # Every page image was proven valid above, so these are the
        # environment: tesseract exiting non-zero (missing language pack...), a
        # timeout, or being unable to run it (binary gone, disk full). Anything
        # else -- e.g. a bug while parsing the output -- is not caught here.
        return _failure(OCRFailureReason.OCR_FAILED, f"tesseract failed: {exc}")

    pages = [page.text for page in parsed]
    text = "\n".join(page for page in pages if page)
    if not text:
        return _failure(
            OCRFailureReason.NO_TEXT_RECOGNIZED,
            "no text recognized (scan may be unreadable or blank)",
        )

    word_count = sum(page.word_count for page in parsed)
    return OCRResult(
        success=True,
        text=text,
        pages=pages,
        word_count=word_count,
        mean_confidence=round(sum(page.confidence_sum for page in parsed) / word_count, 2),
        low_confidence_words=[word for page in parsed for word in page.low_confidence],
    )
