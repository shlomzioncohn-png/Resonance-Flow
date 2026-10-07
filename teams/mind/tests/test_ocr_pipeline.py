"""Tests for the OCR pipeline (src/mind/multimodal/ocr.py) against the real tesseract.

Tesseract itself is a system binary (not pip-installable, see
pyproject.toml), so every test that actually invokes it skips cleanly on a
machine without it, rather than failing the suite. The clean-scan and
low-confidence tests additionally need the FUNSD sample already used by
benchmarks/ (local-data/, gitignored) and skip if it isn't present.

The failure classification, counts, pages and page cap are tested without the
binary in test_ocr_result.py.
"""

from __future__ import annotations

import io
from pathlib import Path

import pytest
from PIL import Image

from mind.multimodal.ocr import LOW_CONFIDENCE_THRESHOLD, OCRFailureReason, extract_text

try:
    from mind.multimodal.ocr import _find_tesseract

    _find_tesseract()
    _TESSERACT_AVAILABLE = True
except RuntimeError:
    _TESSERACT_AVAILABLE = False

pytestmark = pytest.mark.skipif(
    not _TESSERACT_AVAILABLE, reason="tesseract system binary not installed on this machine"
)

_FUNSD_IMAGE = (
    Path(__file__).parent.parent
    / "local-data"
    / "funsd"
    / "dataset"
    / "testing_data"
    / "images"
    / "82092117.png"
)
_funsd_available = pytest.mark.skipif(
    not _FUNSD_IMAGE.exists(),
    reason="FUNSD sample not present locally (see benchmarks/ocr-asr-benchmark.md)",
)


def _blank_image_bytes(size: tuple[int, int] = (200, 200)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, "white").save(buf, format="PNG")
    return buf.getvalue()


def _pdf_from_pages(*images: Image.Image) -> bytes:
    pages = [image.convert("RGB") for image in images]
    buf = io.BytesIO()
    pages[0].save(buf, format="PDF", save_all=True, append_images=pages[1:])
    return buf.getvalue()


@_funsd_available
def test_clean_scan_returns_text():
    result = extract_text(_FUNSD_IMAGE.read_bytes(), filename="82092117.png")

    assert result.success
    assert result.reason is None
    assert result.error is None
    assert len(result.text) > 100
    assert result.pages == [result.text]


@_funsd_available
def test_counts_describe_the_same_words_as_the_text():
    result = extract_text(_FUNSD_IMAGE.read_bytes(), filename="82092117.png")

    assert result.word_count == len(result.text.split())
    assert result.mean_confidence is not None and 0 < result.mean_confidence <= 100
    assert result.low_confidence_count == len(result.low_confidence_words)
    assert result.low_confidence_count < result.word_count


def _form_image_bytes(lines: list[str]) -> bytes:
    """A clean rendering of `lines`, one per row, in Pillow's built-in scalable font."""
    from PIL import ImageDraw, ImageFont

    image = Image.new("RGB", (900, 25 + 75 * len(lines)), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=40)
    for row, text in enumerate(lines):
        draw.text((30, 25 + row * 75), text, fill="black", font=font)
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()


def test_a_four_line_form_comes_back_as_four_lines():
    # Review feedback on PR #509: the words used to be joined with spaces, which
    # folded a form into one line and mixed the fields of different lines.
    form = [
        "Invoice number: 4471",
        "Due date: 12 March 2026",
        "Owner: Dana Levi",
        "Action: send the signed contract",
    ]

    result = extract_text(_form_image_bytes(form), filename="form.png")

    assert result.success
    lines = result.text.split("\n")
    assert len(lines) == 4, result.text
    for line, label in zip(lines, ["Invoice", "Due", "Owner", "Action"], strict=True):
        assert line.startswith(label), result.text


@_funsd_available
def test_a_real_scan_keeps_its_lines_and_the_word_count():
    result = extract_text(_FUNSD_IMAGE.read_bytes(), filename="82092117.png")

    assert result.success
    assert result.text.count("\n") > 10  # a form page is many lines, not one
    assert result.word_count == len(result.text.split())  # no word lost to the grouping


@_funsd_available
def test_pdf_input_returns_text_per_page():
    page = Image.open(_FUNSD_IMAGE)
    pdf = _pdf_from_pages(page, page)

    result = extract_text(pdf, filename="scan.pdf")

    assert result.success
    assert len(result.pages) == 2
    assert all(len(p) > 100 for p in result.pages)


@_funsd_available
def test_a_blank_page_in_the_middle_of_a_pdf_keeps_its_slot():
    page = Image.open(_FUNSD_IMAGE)
    blank = Image.new("RGB", page.size, "white")

    result = extract_text(_pdf_from_pages(page, blank, page), filename="scan.pdf")

    assert result.success
    assert len(result.pages) == 3
    assert result.pages[0] and result.pages[2]
    assert result.pages[1] == ""


@_funsd_available
def test_low_confidence_words_are_flagged():
    result = extract_text(_FUNSD_IMAGE.read_bytes(), filename="82092117.png")

    assert result.success
    # This scan is known (benchmarks/analyze_confidence.py) to contain words
    # Tesseract itself is unsure about -- the case the confidence signal
    # exists for.
    assert result.low_confidence_words
    for word in result.low_confidence_words:
        assert word.confidence < LOW_CONFIDENCE_THRESHOLD
        assert word.text


def test_corrupted_file_returns_failure_not_garbage():
    result = extract_text(b"this is not a valid image or pdf file at all", filename="doc.png")

    assert result.success is False
    assert result.reason == OCRFailureReason.UNREADABLE_FORMAT
    assert result.text == ""
    assert result.pages == []
    assert result.error


def test_corrupted_pdf_returns_failure():
    result = extract_text(b"%PDF-1.4 not actually a valid pdf structure", filename="doc.pdf")

    assert result.success is False
    assert result.reason == OCRFailureReason.UNREADABLE_FORMAT
    assert result.error


def test_unreadable_blank_scan_returns_failure():
    result = extract_text(_blank_image_bytes(), filename="blank.png")

    assert result.success is False
    assert result.reason == OCRFailureReason.NO_TEXT_RECOGNIZED
    assert result.text == ""
    assert result.error


def test_empty_input_returns_failure():
    result = extract_text(b"")

    assert result.success is False
    assert result.reason == OCRFailureReason.EMPTY_INPUT
    assert result.error == "empty input"
