"""OCRResult's shape and failure classification (src/mind/multimodal/ocr.py), agreed with R2 on #449.

R2 asked for a typed reason so her consumer can tell a data failure (drop the
document) from an environment one (a missing tesseract binary must not make her
commit the Kafka offset past every scanned document), per-document counts so the
low-confidence list has a denominator, text per page, and a page cap. These
tests need no tesseract binary -- the tesseract-facing steps are replaced -- so
they run in CI as well.
"""

from __future__ import annotations

import inspect
import io
import subprocess

import pytest
from PIL import Image
from pydantic import ValidationError

from mind.multimodal import ocr
from mind.multimodal.ocr import (
    LowConfidenceWord,
    OCRFailureReason,
    OCRResult,
    extract_text,
)

TSV_HEADER = (
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
    "left\ttop\twidth\theight\tconf\ttext\n"
)


def tsv(*words: tuple[str, float]) -> bytes:
    rows = [
        "\t".join(["5", "1", "1", "1", "1", "1", "0", "0", "10", "10", str(conf), text])
        for text, conf in words
    ]
    return (TSV_HEADER + "\n".join(rows) + "\n").encode()


def pdf_of_blank_pages(n: int) -> bytes:
    image = Image.new("RGB", (100, 100), "white")
    buf = io.BytesIO()
    image.save(buf, format="PDF", save_all=True, append_images=[image] * (n - 1))
    return buf.getvalue()


@pytest.fixture
def tesseract_found(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(ocr, "_find_tesseract", lambda: "tesseract")


def tesseract_prints(monkeypatch: pytest.MonkeyPatch, *outputs: bytes) -> None:
    """Each tesseract call (one per page) returns the next output."""
    remaining = list(outputs)
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda cmd, **kw: subprocess.CompletedProcess(cmd, 0, stdout=remaining.pop(0), stderr=b""),
    )


def test_empty_input_is_a_data_failure():
    result = extract_text(b"")
    assert (result.success, result.reason) == (False, OCRFailureReason.EMPTY_INPUT)
    assert result.retryable is False


def test_missing_tesseract_binary_is_an_environment_failure(monkeypatch: pytest.MonkeyPatch):
    # The case R2 flagged: this must not look like a corrupt document, or her
    # consumer would silently drop and commit every scanned document.
    def not_installed() -> str:
        raise RuntimeError("tesseract not found")

    monkeypatch.setattr(ocr, "_find_tesseract", not_installed)
    result = extract_text(b"some bytes")
    assert (result.success, result.reason) == (False, OCRFailureReason.OCR_UNAVAILABLE)
    assert result.retryable is True


@pytest.mark.usefixtures("tesseract_found")
def test_a_corrupt_pdf_is_unreadable_format():
    result = extract_text(b"%PDF-1.4 not really a pdf")
    assert (result.success, result.reason) == (False, OCRFailureReason.UNREADABLE_FORMAT)
    assert result.retryable is False


@pytest.mark.usefixtures("tesseract_found")
def test_an_image_tesseract_rejects_is_unreadable_format(monkeypatch: pytest.MonkeyPatch):
    def rejects(cmd, **kwargs):
        return subprocess.CompletedProcess(cmd, 1, stdout=b"", stderr=b"Error: bad image")

    monkeypatch.setattr(subprocess, "run", rejects)
    result = extract_text(b"not an image", filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.UNREADABLE_FORMAT)
    assert "bad image" in (result.error or "")


@pytest.mark.usefixtures("tesseract_found")
def test_a_tesseract_timeout_is_an_environment_failure(monkeypatch: pytest.MonkeyPatch):
    def times_out(cmd, **kwargs):
        raise subprocess.TimeoutExpired(cmd, 60)

    monkeypatch.setattr(subprocess, "run", times_out)
    result = extract_text(b"fine image", filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.OCR_FAILED)
    assert result.retryable is True


@pytest.mark.usefixtures("tesseract_found")
def test_a_page_with_no_recognised_text_is_a_data_failure(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv())
    result = extract_text(b"blank page", filename="blank.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.NO_TEXT_RECOGNIZED)
    assert result.retryable is False


@pytest.mark.usefixtures("tesseract_found")
def test_a_pdf_over_max_pages_fails_before_any_ocr(monkeypatch: pytest.MonkeyPatch):
    def must_not_run(*args, **kwargs):
        raise AssertionError("OCR must not start on a PDF over the page cap")

    monkeypatch.setattr(ocr, "_run_tesseract_tsv", must_not_run)
    result = extract_text(pdf_of_blank_pages(3), max_pages=2)
    assert (result.success, result.reason) == (False, OCRFailureReason.TOO_MANY_PAGES)
    assert result.retryable is False
    assert "3 pages" in (result.error or "") and "max_pages=2" in (result.error or "")


@pytest.mark.usefixtures("tesseract_found")
def test_a_pdf_at_exactly_max_pages_is_processed(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("a", 90.0)), tsv(("b", 90.0)))
    assert extract_text(pdf_of_blank_pages(2), max_pages=2).success


def test_the_default_page_cap_is_the_module_constant():
    assert inspect.signature(extract_text).parameters["max_pages"].default == ocr.MAX_PAGES


@pytest.mark.usefixtures("tesseract_found")
def test_pages_are_indexed_by_page_number_with_an_empty_string_for_a_blank_page(
    monkeypatch: pytest.MonkeyPatch,
):
    # R2 builds extraction provenance on the page index, so a blank page in the
    # middle must keep its slot instead of shifting the later pages up.
    tesseract_prints(monkeypatch, tsv(("Hello", 90.0)), tsv(), tsv(("Bye", 80.0)))
    result = extract_text(pdf_of_blank_pages(3))
    assert result.success
    assert result.pages == ["Hello", "", "Bye"]
    assert result.text == "Hello\nBye"  # the convenience view skips the blank page


@pytest.mark.usefixtures("tesseract_found")
def test_counts_and_mean_confidence_cover_all_pages(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(
        monkeypatch,
        tsv(("Hello", 90.0), ("wrld", 30.0)),
        tsv(("Bye", 60.0)),
    )
    result = extract_text(pdf_of_blank_pages(2))
    assert result.success
    assert result.word_count == 3
    assert result.mean_confidence == 60.0  # (90 + 30 + 60) / 3
    # 60 is not below the threshold; only "wrld" is flagged
    assert result.low_confidence_words == [LowConfidenceWord(text="wrld", confidence=30.0)]
    assert result.low_confidence_count == 1


@pytest.mark.usefixtures("tesseract_found")
def test_a_negative_confidence_word_is_left_out_of_every_count(monkeypatch: pytest.MonkeyPatch):
    # Tesseract's "not applicable" sentinel (-1) must not drag the mean down
    tesseract_prints(monkeypatch, tsv(("ok", 80.0), ("junk", -1.0)))
    result = extract_text(b"image", filename="a.png")
    assert (result.word_count, result.mean_confidence) == (1, 80.0)


@pytest.mark.usefixtures("tesseract_found")
def test_a_single_image_is_one_page(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("one", 70.0), ("two", 90.0)))
    result = extract_text(b"image", filename="a.png")
    assert result.pages == ["one two"]
    assert result.text == "one two"


def test_a_serialized_result_includes_the_derived_fields():
    dumped = OCRResult(
        success=True,
        text="x",
        pages=["x"],
        word_count=1,
        mean_confidence=50.0,
        low_confidence_words=[LowConfidenceWord(text="x", confidence=50.0)],
    ).model_dump()
    assert dumped["low_confidence_count"] == 1
    assert dumped["retryable"] is False


def test_retryable_is_true_only_for_the_environment_reasons():
    environment = {OCRFailureReason.OCR_UNAVAILABLE, OCRFailureReason.OCR_FAILED}
    for reason in OCRFailureReason:
        result = OCRResult(success=False, reason=reason, error="e")
        assert result.retryable is (reason in environment), reason


def test_a_failed_result_must_carry_a_reason_and_a_message():
    with pytest.raises(ValidationError):
        OCRResult(success=False, error="something")  # no reason
    with pytest.raises(ValidationError):
        OCRResult(success=False, reason=OCRFailureReason.EMPTY_INPUT)  # no message


def test_a_successful_result_cannot_carry_a_reason():
    with pytest.raises(ValidationError):
        OCRResult(success=True, text="t", reason=OCRFailureReason.OCR_FAILED, error="e")
