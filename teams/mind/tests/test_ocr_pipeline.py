"""Tests for the OCR pipeline (src/mind/multimodal/ocr.py).

Tesseract itself is a system binary (not pip-installable, see
pyproject.toml), so every test that actually invokes it skips cleanly on a
machine without it, rather than failing the suite. The clean-scan and
low-confidence tests additionally need the FUNSD sample already used by
benchmarks/ (local-data/, gitignored) and skip if it isn't present.
"""

from __future__ import annotations

import io
from pathlib import Path

import pytest
from PIL import Image

from mind.multimodal.ocr import LOW_CONFIDENCE_THRESHOLD, extract_text

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


def _pdf_bytes_from_image(image_bytes: bytes) -> bytes:
    buf = io.BytesIO()
    Image.open(io.BytesIO(image_bytes)).convert("RGB").save(buf, format="PDF")
    return buf.getvalue()


@_funsd_available
def test_clean_scan_returns_text():
    result = extract_text(_FUNSD_IMAGE.read_bytes(), filename="82092117.png")

    assert result.success
    assert result.error is None
    assert len(result.text) > 100


@_funsd_available
def test_pdf_input_returns_text():
    pdf_bytes = _pdf_bytes_from_image(_FUNSD_IMAGE.read_bytes())

    result = extract_text(pdf_bytes, filename="scan.pdf")

    assert result.success
    assert len(result.text) > 100


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
    assert result.text == ""
    assert result.error


def test_corrupted_pdf_returns_failure():
    result = extract_text(b"%PDF-1.4 not actually a valid pdf structure", filename="doc.pdf")

    assert result.success is False
    assert result.error


def test_unreadable_blank_scan_returns_failure():
    result = extract_text(_blank_image_bytes(), filename="blank.png")

    assert result.success is False
    assert result.text == ""
    assert result.error


def test_empty_input_returns_failure():
    result = extract_text(b"")

    assert result.success is False
    assert result.error == "empty input"
