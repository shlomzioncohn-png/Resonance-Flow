"""OCRResult's failure classification (src/mind/multimodal/ocr.py), asked for by R2 on #449.

R2 wants a classified reason, not just a message, so her pipeline can react
differently to "the file is missing" (a bug on our side) and "the scan is
unreadable" (a bad document). These tests need no tesseract binary -- the
tesseract-facing steps are replaced -- so they run in CI as well.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest
from pydantic import ValidationError

from mind.multimodal import ocr
from mind.multimodal.ocr import LowConfidenceWord, OCRResult, extract_text

TSV_HEADER = (
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
    "left\ttop\twidth\theight\tconf\ttext\n"
)


@pytest.fixture
def tesseract_found(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(ocr, "_find_tesseract", lambda: "tesseract")


def _file(tmp_path: Path, name: str, content: bytes) -> Path:
    path = tmp_path / name
    path.write_bytes(content)
    return path


def test_missing_file_is_file_not_found(tmp_path: Path):
    result = extract_text(tmp_path / "nope.png")
    assert (result.success, result.reason) == (False, "file_not_found")
    assert "nope.png" in (result.error or "")


def test_a_directory_is_file_unreadable_not_a_crash(tmp_path: Path):
    result = extract_text(tmp_path)
    assert (result.success, result.reason) == (False, "file_unreadable")


def test_empty_file_is_empty_input(tmp_path: Path):
    result = extract_text(_file(tmp_path, "empty.png", b""))
    assert (result.success, result.reason) == (False, "empty_input")


def test_missing_tesseract_binary_is_ocr_unavailable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    def not_installed() -> str:
        raise RuntimeError("tesseract not found")

    monkeypatch.setattr(ocr, "_find_tesseract", not_installed)
    result = extract_text(_file(tmp_path, "a.png", b"x"))
    assert (result.success, result.reason) == (False, "ocr_unavailable")


@pytest.mark.usefixtures("tesseract_found")
def test_a_corrupt_pdf_is_corrupt_input(tmp_path: Path):
    result = extract_text(_file(tmp_path, "a.pdf", b"%PDF-1.4 not really a pdf"))
    assert (result.success, result.reason) == (False, "corrupt_input")


@pytest.mark.usefixtures("tesseract_found")
def test_an_image_tesseract_rejects_is_corrupt_input(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    def rejects(cmd, **kwargs):
        return subprocess.CompletedProcess(cmd, 1, stdout=b"", stderr=b"Error: bad image")

    monkeypatch.setattr(subprocess, "run", rejects)
    result = extract_text(_file(tmp_path, "a.png", b"not an image"))
    assert (result.success, result.reason) == (False, "corrupt_input")
    assert "bad image" in (result.error or "")


@pytest.mark.usefixtures("tesseract_found")
def test_a_tesseract_timeout_is_ocr_failed_not_corrupt_input(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    def times_out(cmd, **kwargs):
        raise subprocess.TimeoutExpired(cmd, 60)

    monkeypatch.setattr(subprocess, "run", times_out)
    result = extract_text(_file(tmp_path, "a.png", b"fine image"))
    assert (result.success, result.reason) == (False, "ocr_failed")


@pytest.mark.usefixtures("tesseract_found")
def test_a_page_with_no_recognised_text_is_unreadable_scan(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda cmd, **kw: subprocess.CompletedProcess(
            cmd, 0, stdout=TSV_HEADER.encode(), stderr=b""
        ),
    )
    result = extract_text(_file(tmp_path, "blank.png", b"blank page"))
    assert (result.success, result.reason) == (False, "unreadable_scan")


def test_success_carries_no_reason_or_error():
    result = OCRResult(
        success=True,
        text="hello",
        low_confidence_words=[LowConfidenceWord(text="x", confidence=3.0)],
    )
    assert result.reason is None and result.error is None


def test_a_failed_result_must_carry_a_reason_and_a_message():
    with pytest.raises(ValidationError):
        OCRResult(success=False, error="something")  # no reason
    with pytest.raises(ValidationError):
        OCRResult(success=False, reason="empty_input")  # no message


def test_a_successful_result_cannot_carry_a_reason():
    with pytest.raises(ValidationError):
        OCRResult(success=True, text="t", reason="ocr_failed", error="e")
