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
low-confidence list has a denominator, text per page, and a page cap (a longer PDF
is truncated and says so, not refused). Whether a
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
import time
from collections.abc import Mapping
from enum import Enum
from pathlib import Path
from typing import ClassVar

import pypdfium2 as pdfium
from PIL import Image
from pydantic import BaseModel, Field, computed_field

from mind.multimodal.failures import FailureCategory
from mind.multimodal.result import MediaTextResult
from mind.observability.tracking import CallKind, CallStatus, record_call

logger = logging.getLogger(__name__)

# Tesseract page-segmentation args -- the OCR decision (psm 11, sparse text; see
# benchmarks/ocr-asr-benchmark.md). Defined here, not imported from benchmarks/
# (service code doesn't depend on the benchmark scripts); the benchmark's own
# CHOSEN_PSM_ARGS is a separate copy, and tests/test_ocr_psm_sync.py fails if
# the two ever differ, so a future PSM change can't leave one of them behind.
OCR_PSM_ARGS = ["--psm", "11"]

# The name every OCR call is recorded under (observability.record_call).
OCR_MODEL_NAME = "tesseract-psm11-ocr"

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

# How many pages of a PDF extract_text() OCRs by default. Later pages are not
# rendered or read, and the result says so (`truncated`, `pages_available`)
# instead of the call failing: the extraction that consumes this text needs
# title / description / source_ref, almost always in the first pages, and a task
# inferred from a partial document should be scored as such, not be invisible.
# OCR runs synchronously inside the caller's Kafka handler, so its cost is time
# the consumer is blocked. Measured on the dev machine, a US-Letter page scanned
# at 200 DPI (1700x2200 px) takes about 2 s: ~0.5 s to rasterize plus ~1.5 s of
# tesseract. Tesseract's time grows with the pixel count, which is why it is far
# above the ~0.7 s of the 0.75 MP bare FUNSD image in the benchmark; dense pages
# cost more, sparse ones less. So 10 pages is about 20 s. A guess at a sensible
# default, not a measured limit -- callers can pass their own `max_pages`.
MAX_PAGES = 10

# Longest one tesseract run (one page) may take before it is killed and the call
# fails with OCR_FAILED. With MAX_PAGES it fixes the worst case of a call, which is
# what the caller has to size its handler against, not the ~20 s typical case:
# MAX_PAGES * TESSERACT_TIMEOUT_S = 200 s, under librdkafka's default
# max.poll.interval.ms of 300 s (teams/mind sets none). A consumer that runs OCR
# inside its Kafka handler for longer than that is dropped from the group
# mid-handler, its commit fails after the rebalance, and the message is
# reprocessed -- duplicate OCR work on exactly the document that was already too
# slow. 20 s is about 10x the ~2 s a Letter page takes. The bound covers the
# tesseract runs only: rasterizing a PDF page (~0.5 s each, not capped) comes on top.
TESSERACT_TIMEOUT_S = 20

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
    OCR_UNAVAILABLE = "OCR_UNAVAILABLE"
    OCR_FAILED = "OCR_FAILED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class OCRResult(MediaTextResult[OCRFailureReason]):
    """Result of extract_text(). Always returned, never raised past this module.

    On success: ``text`` is all pages joined with a newline (the convenience
    view); ``pages`` holds the text of each page, so the index is the page
    number (0-based), with ``""`` for a page where nothing was recognised;
    ``word_count`` / ``mean_confidence`` cover every recognised word, and
    ``low_confidence_words`` the ones below LOW_CONFIDENCE_THRESHOLD, with
    ``low_confidence_count`` their number -- the list alone has no denominator.
    A PDF longer than ``max_pages`` is truncated, not refused: ``pages`` holds the
    first ``max_pages`` pages, ``pages_available`` the document's real page count
    and ``truncated`` is True -- a signal that the text is a partial document.

    ``success`` / ``reason`` / ``error`` / ``failure_category`` / ``retryable``
    come from MediaTextResult, the envelope OCR and ASR share (see result.py and
    failures.py): on failure ``reason`` says why, ``error`` is the message for
    logs, ``failure_category`` how to treat the document (drop / retry later /
    park), and ``retryable`` is True for the environment failures.
    """

    FAILURE_CATEGORIES: ClassVar[Mapping[Enum, FailureCategory]] = {
        OCRFailureReason.EMPTY_INPUT: FailureCategory.DATA,
        OCRFailureReason.UNREADABLE_FORMAT: FailureCategory.DATA,
        OCRFailureReason.NO_TEXT_RECOGNIZED: FailureCategory.DATA,
        OCRFailureReason.OCR_UNAVAILABLE: FailureCategory.ENVIRONMENT,
        OCRFailureReason.OCR_FAILED: FailureCategory.ENVIRONMENT,
        OCRFailureReason.INTERNAL_ERROR: FailureCategory.BUG,
    }

    pages: list[str] = Field(default_factory=list)
    pages_available: int = 0
    word_count: int = 0
    mean_confidence: float | None = None
    low_confidence_words: list[LowConfidenceWord] = Field(default_factory=list)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def low_confidence_count(self) -> int:
        return len(self.low_confidence_words)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def truncated(self) -> bool:
        return self.pages_available > len(self.pages)


def _failure(reason: OCRFailureReason, error: str) -> OCRResult:
    return OCRResult(success=False, reason=reason, error=error)


class _PageOCR(BaseModel):
    """What one page's TSV yielded."""

    text: str
    word_count: int
    confidence_sum: float
    low_confidence: list[LowConfidenceWord]


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


def _pdf_to_page_images(content: bytes, max_pages: int) -> tuple[list[bytes], int]:
    """The first `max_pages` pages as PNG bytes (at a resolution Tesseract can
    read), and the PDF's real page count.

    Only those pages are rendered -- rendering is part of the cost `max_pages`
    exists to bound. The page count comes from the document itself, so a PDF
    that cannot be opened (and so cannot be counted) raises, and is reported
    as UNREADABLE_FORMAT.
    """
    pdf = pdfium.PdfDocument(io.BytesIO(content))
    total = len(pdf)
    page_images = []
    for index in range(min(total, max_pages)):
        bitmap = pdf[index].render(scale=_PDF_RENDER_SCALE)
        buf = io.BytesIO()
        bitmap.to_pil().save(buf, format="PNG")
        page_images.append(buf.getvalue())
    return page_images, total


def _run_tesseract_tsv(tesseract_cmd: str, image_bytes: bytes) -> str:
    """Run tesseract --psm 11 in TSV mode on one page image, return raw TSV."""
    fd, tmp_path = tempfile.mkstemp(suffix=".png")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(image_bytes)
        cmd = [tesseract_cmd, tmp_path, "stdout", *OCR_PSM_ARGS, "tsv"]
        proc = subprocess.run(cmd, capture_output=True, timeout=TESSERACT_TIMEOUT_S, check=False)
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
        max_pages: How many pages of a PDF to OCR (at least 1). A longer PDF is
            truncated, not refused: only the first `max_pages` pages are
            rendered and read, and the result carries ``truncated=True`` and
            ``pages_available`` (the real page count). A single image is one page.

    Raises ValueError if ``max_pages < 1`` (a programming error, not a document
    failure). Otherwise it always returns an OCRResult and never raises: empty
    or corrupted input, an unreadable scan, or a missing/failing tesseract comes back
    as ``success=False`` with a classified ``reason`` (see OCRFailureReason),
    an ``error`` message and a ``retryable`` flag -- not an exception and not
    garbage text.

    Every successful result carries the low-confidence signal -- words
    Tesseract itself scored below LOW_CONFIDENCE_THRESHOLD, plus the counts to
    read it against -- so a caller (extraction) can flag or discount them
    instead of trusting all OCR output equally.
    """
    if max_pages < 1:
        raise ValueError(f"max_pages must be at least 1, got {max_pages}")
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
        if _is_pdf(content, filename):
            page_images, pages_available = _pdf_to_page_images(content, max_pages)
        else:
            page_images, pages_available = [_checked_image(content)], 1
    except _UNREADABLE_FILE_ERRORS as exc:
        return _failure(
            OCRFailureReason.UNREADABLE_FORMAT, f"could not read input as image or PDF: {exc}"
        )

    if not page_images:
        return _failure(OCRFailureReason.UNREADABLE_FORMAT, "PDF has no pages")

    parsed: list[_PageOCR] = []
    start = time.perf_counter()
    # ERROR until the loop completes, so anything that leaves it early --
    # including a bug, which is not caught below -- is recorded as a failure.
    status = CallStatus.ERROR
    try:
        for image_bytes in page_images:
            parsed.append(_parse_tsv(_run_tesseract_tsv(tesseract_cmd, image_bytes)))
        status = CallStatus.SUCCESS
    except (_TesseractFailed, subprocess.TimeoutExpired, OSError) as exc:
        # Every page image was proven valid above, so these are the
        # environment: tesseract exiting non-zero (missing language pack...), a
        # timeout, or being unable to run it (binary gone, disk full). Anything
        # else -- e.g. a bug while parsing the output -- is not caught here.
        return _failure(OCRFailureReason.OCR_FAILED, f"tesseract failed: {exc}")
    finally:
        # cost None: local compute has no rate yet (#479).
        record_call(
            OCR_MODEL_NAME,
            (time.perf_counter() - start) * 1000,
            cost=None,
            kind=CallKind.OCR,
            status=status,
        )

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
        pages_available=pages_available,
        word_count=word_count,
        mean_confidence=round(sum(page.confidence_sum for page in parsed) / word_count, 2),
        low_confidence_words=[word for page in parsed for word in page.low_confidence],
    )
