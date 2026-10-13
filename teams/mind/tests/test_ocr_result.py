"""OCRResult's shape and failure classification (src/mind/multimodal/ocr.py), agreed with R2 (Slack, confirmed on PR #509).

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
import sys

import pytest
from PIL import Image
from pydantic import ValidationError

from mind.multimodal import ocr
from mind.multimodal.ocr import (
    OCR_MODEL_NAME,
    LowConfidenceWord,
    OCRFailureReason,
    OCRResult,
    extract_text,
)
from mind.observability import compute
from mind.observability.tracking import CallKind, CallStatus, _reset_for_tests, get_recent_calls

TSV_HEADER = (
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
    "left\ttop\twidth\theight\tconf\ttext\n"
)


def tsv(*words: tuple) -> bytes:
    """Tesseract TSV for one page. A word is (text, conf), or (text, conf, (block, par, line))
    to say which line it is on; without a position every word is on the same line."""
    rows = []
    for text, conf, *where in words:
        block, par, line = where[0] if where else (1, 1, 1)
        rows.append(
            "\t".join(
                [
                    "5",
                    "1",
                    str(block),
                    str(par),
                    str(line),
                    "1",
                    "0",
                    "0",
                    "10",
                    "10",
                    str(conf),
                    text,
                ]
            )
        )
    return (TSV_HEADER + "\n".join(rows) + "\n").encode()


def pdf_of_blank_pages(n: int) -> bytes:
    image = Image.new("RGB", (100, 100), "white")
    buf = io.BytesIO()
    image.save(buf, format="PDF", save_all=True, append_images=[image] * (n - 1))
    return buf.getvalue()


def png_bytes() -> bytes:
    """A real, decodable image: extract_text() proves the bytes are one before tesseract runs."""
    buf = io.BytesIO()
    Image.new("RGB", (50, 50), "white").save(buf, format="PNG")
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


def _tesseract_must_not_run(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*args, **kwargs):
        raise AssertionError("tesseract must not run on bytes that are not a readable image")

    monkeypatch.setattr(subprocess, "run", boom)


@pytest.mark.usefixtures("tesseract_found")
def test_bytes_that_are_not_an_image_are_unreadable_format_without_running_tesseract(
    monkeypatch: pytest.MonkeyPatch,
):
    _tesseract_must_not_run(monkeypatch)
    result = extract_text(b"this is not an image", filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.UNREADABLE_FORMAT)
    assert result.retryable is False


@pytest.mark.usefixtures("tesseract_found")
def test_a_truncated_image_is_unreadable_format(monkeypatch: pytest.MonkeyPatch):
    _tesseract_must_not_run(monkeypatch)
    png = png_bytes()
    result = extract_text(png[: len(png) // 2], filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.UNREADABLE_FORMAT)


@pytest.mark.usefixtures("tesseract_found")
def test_tesseract_failing_on_a_valid_image_is_an_environment_failure(
    monkeypatch: pytest.MonkeyPatch,
):
    # Review feedback on PR #509: tesseract exits non-zero when its language
    # pack is missing (tesseract-ocr installed without tesseract-ocr-eng). That
    # is the environment, not a bad document -- it must not come back as
    # UNREADABLE_FORMAT, or the caller would drop every scanned document.
    stderr = (
        b"Error opening data file /usr/share/tesseract-ocr/5/tessdata/eng.traineddata\n"
        b"Failed loading language 'eng'\nCould not initialize tesseract.\n"
    )
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda cmd, **kw: subprocess.CompletedProcess(cmd, 1, stdout=b"", stderr=stderr),
    )
    result = extract_text(png_bytes(), filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.OCR_FAILED)
    assert result.retryable is True
    assert "eng.traineddata" in (result.error or "")


@pytest.mark.usefixtures("tesseract_found")
def test_a_tesseract_timeout_is_an_environment_failure(monkeypatch: pytest.MonkeyPatch):
    def times_out(cmd, **kwargs):
        raise subprocess.TimeoutExpired(cmd, ocr.TESSERACT_TIMEOUT_S)

    monkeypatch.setattr(subprocess, "run", times_out)
    result = extract_text(png_bytes(), filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.OCR_FAILED)
    assert result.retryable is True


# --- the worst case of a call (R2, PR #509) --------------------------------------
# R2 runs OCR inside her Kafka handler and teams/mind sets no max.poll.interval.ms,
# so librdkafka's default of 300 s applies. A call that can outlast it gets her
# consumer kicked out of the group mid-handler and the message reprocessed. What she
# sizes against is the bound, not the ~20 s typical case.

KAFKA_DEFAULT_MAX_POLL_INTERVAL_S = 300  # librdkafka default for max.poll.interval.ms


@pytest.mark.usefixtures("tesseract_found")
def test_each_tesseract_run_is_given_the_per_page_timeout(monkeypatch: pytest.MonkeyPatch):
    seen: list[float] = []

    def fake_run(cmd, **kwargs):
        seen.append(kwargs["timeout"])
        return subprocess.CompletedProcess(cmd, 0, stdout=tsv(("word", 90.0)), stderr=b"")

    monkeypatch.setattr(subprocess, "run", fake_run)
    assert extract_text(pdf_of_blank_pages(2)).success
    assert seen == [ocr.TESSERACT_TIMEOUT_S] * 2
    assert ocr.TESSERACT_TIMEOUT_S == 20


def test_the_worst_case_of_a_default_call_is_under_the_kafka_poll_interval():
    worst_case_s = ocr.MAX_PAGES * ocr.TESSERACT_TIMEOUT_S
    assert worst_case_s == 200
    assert worst_case_s < KAFKA_DEFAULT_MAX_POLL_INTERVAL_S


@pytest.mark.usefixtures("tesseract_found")
def test_a_timeout_on_one_page_fails_the_call_without_running_the_rest(
    monkeypatch: pytest.MonkeyPatch,
):
    # the bound only holds if the call stops at the first timeout, instead of
    # spending another timeout on every remaining page
    calls: list[int] = []

    def second_page_hangs(cmd, **kwargs):
        calls.append(1)
        if len(calls) == 2:
            raise subprocess.TimeoutExpired(cmd, kwargs["timeout"])
        return subprocess.CompletedProcess(cmd, 0, stdout=tsv(("word", 90.0)), stderr=b"")

    monkeypatch.setattr(subprocess, "run", second_page_hangs)
    result = extract_text(pdf_of_blank_pages(5))
    assert (result.success, result.reason) == (False, OCRFailureReason.OCR_FAILED)
    assert len(calls) == 2


@pytest.mark.usefixtures("tesseract_found")
def test_a_page_with_no_recognised_text_is_a_data_failure(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv())
    result = extract_text(png_bytes(), filename="blank.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.NO_TEXT_RECOGNIZED)
    assert result.retryable is False


@pytest.mark.usefixtures("tesseract_found")
def test_a_pdf_over_max_pages_is_truncated_and_says_so(monkeypatch: pytest.MonkeyPatch):
    # R2 (on PR #509): extraction needs title / description / source_ref, almost
    # always in the first pages, so a long PDF returns its first pages plus a
    # `truncated` flag she can score lower -- not an empty failure she'd drop.
    rendered: list[int] = []

    class SpyDocument(ocr.pdfium.PdfDocument):  # type: ignore[misc]
        def __getitem__(self, index):  # type: ignore[no-untyped-def]
            rendered.append(index)
            return super().__getitem__(index)

    monkeypatch.setattr(ocr.pdfium, "PdfDocument", SpyDocument)
    # exactly two outputs: a third tesseract call would run off the end of this list
    tesseract_prints(monkeypatch, tsv(("first", 90.0)), tsv(("second", 80.0)))

    result = extract_text(pdf_of_blank_pages(3), max_pages=2)

    assert result.success
    assert result.pages == ["first", "second"]
    assert result.text == "first\nsecond"
    assert result.pages_available == 3
    assert result.truncated is True
    assert rendered == [0, 1]  # the pages past the cap are not even rendered


@pytest.mark.usefixtures("tesseract_found")
def test_a_pdf_at_exactly_max_pages_is_not_truncated(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("a", 90.0)), tsv(("b", 90.0)))
    result = extract_text(pdf_of_blank_pages(2), max_pages=2)
    assert result.success
    assert (result.pages_available, result.truncated) == (2, False)


@pytest.mark.usefixtures("tesseract_found")
def test_a_single_image_reports_one_page_available(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("one", 90.0)))
    result = extract_text(png_bytes(), filename="a.png", max_pages=1)
    assert (result.pages_available, result.truncated) == (1, False)


def test_max_pages_below_one_is_a_programming_error():
    with pytest.raises(ValueError, match="max_pages"):
        extract_text(b"x", max_pages=0)


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
    result = extract_text(png_bytes(), filename="a.png")
    assert (result.word_count, result.mean_confidence) == (1, 80.0)


@pytest.mark.usefixtures("tesseract_found")
def test_a_single_image_is_one_page(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("one", 70.0), ("two", 90.0)))
    result = extract_text(png_bytes(), filename="a.png")
    assert result.pages == ["one two"]
    assert result.text == "one two"


# --- line breaks (review feedback on PR #509) -----------------------------------
# Joining every word with a space folded a 4-line form into one line, mixing the
# fields of different lines in what R2's LLM extraction reads. Tesseract's TSV rows
# carry block_num / par_num / line_num, so words are grouped by those three.


@pytest.mark.usefixtures("tesseract_found")
def test_a_form_keeps_its_lines(monkeypatch: pytest.MonkeyPatch):
    form = tsv(
        ("Invoice", 95.0, (1, 1, 1)),
        ("number:", 95.0, (1, 1, 1)),
        ("4471", 95.0, (1, 1, 1)),
        ("Due", 95.0, (2, 1, 1)),
        ("date:", 95.0, (2, 1, 1)),
        ("12", 95.0, (2, 1, 1)),
        ("March", 95.0, (2, 1, 1)),
        ("Owner:", 95.0, (3, 1, 1)),
        ("Dana", 95.0, (3, 1, 1)),
        ("Levi", 95.0, (3, 1, 1)),
    )
    tesseract_prints(monkeypatch, form)
    result = extract_text(png_bytes(), filename="a.png")
    assert result.text == "Invoice number: 4471\nDue date: 12 March\nOwner: Dana Levi"
    assert result.pages == [result.text]
    assert result.word_count == 10


@pytest.mark.usefixtures("tesseract_found")
def test_the_same_line_number_in_another_block_or_paragraph_is_another_line(
    monkeypatch: pytest.MonkeyPatch,
):
    tesseract_prints(
        monkeypatch,
        tsv(
            ("a", 90.0, (1, 1, 1)),
            ("b", 90.0, (1, 1, 2)),  # next line, same block and paragraph
            ("c", 90.0, (1, 2, 1)),  # line_num 1 again, but another paragraph
            ("d", 90.0, (2, 1, 1)),  # line_num 1 again, but another block
        ),
    )
    assert extract_text(png_bytes(), filename="a.png").text == "a\nb\nc\nd"


@pytest.mark.usefixtures("tesseract_found")
def test_lines_survive_across_pages(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(
        monkeypatch,
        tsv(("one", 90.0, (1, 1, 1)), ("two", 90.0, (1, 1, 2))),
        tsv(("three", 90.0, (1, 1, 1))),
    )
    result = extract_text(pdf_of_blank_pages(2))
    assert result.pages == ["one\ntwo", "three"]
    assert result.text == "one\ntwo\nthree"


@pytest.mark.usefixtures("tesseract_found")
def test_a_skipped_word_does_not_leave_an_empty_line(monkeypatch: pytest.MonkeyPatch):
    # a word dropped for its -1 sentinel confidence must not leave a blank line behind
    tesseract_prints(
        monkeypatch,
        tsv(("keep", 90.0, (1, 1, 1)), ("junk", -1.0, (1, 1, 2)), ("also", 90.0, (1, 1, 3))),
    )
    assert extract_text(png_bytes(), filename="a.png").text == "keep\nalso"


def test_a_serialized_result_includes_the_derived_fields():
    dumped = OCRResult(
        success=True,
        text="x",
        pages=["x"],
        pages_available=3,
        word_count=1,
        mean_confidence=50.0,
        low_confidence_words=[LowConfidenceWord(text="x", confidence=50.0)],
    ).model_dump()
    assert dumped["low_confidence_count"] == 1
    assert dumped["truncated"] is True  # 1 page read of 3 available
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


# --- a bug of ours is not an environment problem (review feedback on PR #509) ---
# `retryable=True` makes R2's consumer leave the Kafka offset uncommitted, so a
# bug misreported as an environment failure would be retried forever and block
# every message behind it. Only the errors that really are environmental are
# OCR_FAILED; anything unexpected is INTERNAL_ERROR, not retryable.


@pytest.mark.usefixtures("tesseract_found")
def test_a_bug_while_parsing_tesseract_output_is_internal_error_not_retryable(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))

    def buggy_parse(_tsv: str):
        raise ValueError("a bug in our parser")

    monkeypatch.setattr(ocr, "_parse_tsv", buggy_parse)
    with caplog.at_level("ERROR", logger=ocr.logger.name):
        result = extract_text(png_bytes(), filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.INTERNAL_ERROR)
    assert result.retryable is False
    assert "a bug in our parser" in (result.error or "")
    assert "unexpected error in extract_text" in caplog.text  # not silent


@pytest.mark.usefixtures("tesseract_found")
def test_a_bug_while_reading_the_input_is_internal_error_not_a_bad_document(
    monkeypatch: pytest.MonkeyPatch,
):
    # A RuntimeError out of the format check is our bug, not "the file is
    # corrupt": mislabelled as UNREADABLE_FORMAT it would be silently dropped.
    def buggy_check(content: bytes) -> bytes:
        raise RuntimeError("a bug in our image check")

    monkeypatch.setattr(ocr, "_checked_image", buggy_check)
    result = extract_text(png_bytes(), filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.INTERNAL_ERROR)
    assert result.retryable is False


@pytest.mark.usefixtures("tesseract_found")
def test_being_unable_to_run_tesseract_is_an_environment_failure(monkeypatch: pytest.MonkeyPatch):
    def cannot_start(cmd, **kwargs):
        raise FileNotFoundError("tesseract vanished")

    monkeypatch.setattr(subprocess, "run", cannot_start)
    result = extract_text(png_bytes(), filename="a.png")
    assert (result.success, result.reason) == (False, OCRFailureReason.OCR_FAILED)
    assert result.retryable is True


def test_internal_error_is_not_retryable():
    assert (
        OCRResult(success=False, reason=OCRFailureReason.INTERNAL_ERROR, error="e").retryable
        is False
    )


# --- every tesseract run is recorded (#479) -----------------------------------------


@pytest.fixture
def ledger() -> None:
    """record_call()'s ledger is module-level: start each of these tests empty."""
    _reset_for_tests()


@pytest.mark.usefixtures("tesseract_found", "ledger")
def test_a_successful_call_is_recorded_as_ocr(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    assert extract_text(png_bytes(), filename="a.png").success
    [call] = get_recent_calls()
    assert call.model_name == OCR_MODEL_NAME
    assert (call.kind, call.status) == (CallKind.OCR, CallStatus.SUCCESS)
    assert call.duration_ms > 0
    assert call.cost is None  # unpriced until local compute has a rate (#479), not free


@pytest.mark.usefixtures("tesseract_found", "ledger")
def test_tesseract_failing_is_recorded_as_an_error(monkeypatch: pytest.MonkeyPatch):
    def times_out(cmd, **kwargs):
        raise subprocess.TimeoutExpired(cmd, ocr.TESSERACT_TIMEOUT_S)

    monkeypatch.setattr(subprocess, "run", times_out)
    assert not extract_text(png_bytes(), filename="a.png").success
    [call] = get_recent_calls()
    assert (call.kind, call.status) == (CallKind.OCR, CallStatus.ERROR)


@pytest.mark.usefixtures("tesseract_found", "ledger")
def test_a_bug_inside_the_tesseract_loop_is_recorded_as_an_error(
    monkeypatch: pytest.MonkeyPatch,
):
    # Not caught by the OCR_FAILED handler -- it must not be recorded as a success.
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))

    def buggy_parse(_tsv: str):
        raise ValueError("a bug in our parser")

    monkeypatch.setattr(ocr, "_parse_tsv", buggy_parse)
    extract_text(png_bytes(), filename="a.png")
    [call] = get_recent_calls()
    assert call.status == CallStatus.ERROR


@pytest.mark.usefixtures("tesseract_found", "ledger")
def test_input_rejected_before_tesseract_runs_is_not_recorded():
    extract_text(b"not an image at all", filename="a.png")
    assert get_recent_calls() == []


_real_run = subprocess.run


def tesseract_burns_cpu(monkeypatch: pytest.MonkeyPatch, seconds: float, output: bytes) -> None:
    """Each tesseract call is a real child process using `seconds` of CPU, then printing `output`."""
    code = f"import time\nt = time.process_time()\nwhile time.process_time() - t < {seconds}: pass"

    def run(cmd, **kwargs):
        _real_run([sys.executable, "-c", code], check=True)
        return subprocess.CompletedProcess(cmd, 0, stdout=output, stderr=b"")

    monkeypatch.setattr(subprocess, "run", run)


@pytest.mark.skipif(sys.platform == "win32", reason="Windows reports no child-process CPU")
@pytest.mark.usefixtures("tesseract_found", "ledger")
def test_the_cpu_tesseract_uses_is_recorded_and_priced(monkeypatch: pytest.MonkeyPatch):
    # Tesseract is a child process: its CPU is not this process's process_time().
    tesseract_burns_cpu(monkeypatch, 0.3, tsv(("word", 90.0)))
    monkeypatch.setattr(compute, "usd_per_cpu_hour", lambda: 0.036)
    assert extract_text(png_bytes(), filename="a.png").success
    [call] = get_recent_calls()
    assert call.cpu_s is not None and call.cpu_s >= 0.3
    assert call.cost == pytest.approx(call.cpu_s / 3600 * 0.036)


@pytest.mark.skipif(sys.platform != "win32", reason="only Windows lacks child-process CPU")
@pytest.mark.usefixtures("tesseract_found", "ledger")
def test_where_tesseract_cpu_cannot_be_measured_it_is_none_and_unpriced(
    monkeypatch: pytest.MonkeyPatch,
):
    tesseract_burns_cpu(monkeypatch, 0.1, tsv(("word", 90.0)))
    monkeypatch.setattr(compute, "usd_per_cpu_hour", lambda: 0.036)
    assert extract_text(png_bytes(), filename="a.png").success
    [call] = get_recent_calls()
    assert (call.cpu_s, call.cost) == (None, None)  # unknown, not 0
