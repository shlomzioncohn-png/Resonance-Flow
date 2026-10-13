"""The per-call-name model-call summary (src/mind/observability/tracking.py) -- #479.

What it must get right is the difference between "none" and "zero": a call
whose CPU was not measured, whose usage was not reported or that could not be
priced is counted as missing, and a total that only missing calls fed is None,
never 0.
"""

import logging
from collections import deque

import pytest
from fastapi.testclient import TestClient

from mind.api.main import app
from mind.config import MonitoringSettings
from mind.observability import tracking
from mind.observability.tracking import (
    CallKind,
    CallNameSummary,
    CallStatus,
    _reset_for_tests,
    log_summary,
    model_call_summary,
    percentile,
    record_call,
)


@pytest.fixture(autouse=True)
def _clean_call_records():
    _reset_for_tests()


def use_limits(monkeypatch: pytest.MonkeyPatch, **limits: object) -> None:
    monkeypatch.setattr(
        tracking, "monitoring_settings", lambda: MonitoringSettings.model_construct(**limits)
    )


def row(call_name: str) -> CallNameSummary:
    [found] = [r for r in model_call_summary().by_call_name if r.call_name == call_name]
    return found


# --- shape --------------------------------------------------------------------------


def test_nothing_recorded_is_an_empty_summary():
    summary = model_call_summary()
    assert (summary.total_calls, summary.by_call_name) == (0, [])


def test_calls_are_grouped_by_name_in_name_order():
    record_call("whisper", 10.0, None, kind=CallKind.ASR)
    record_call("tesseract", 10.0, None, kind=CallKind.OCR)
    record_call("whisper", 10.0, None, kind=CallKind.ASR, status=CallStatus.ERROR)

    summary = model_call_summary()
    assert summary.total_calls == 3
    assert [r.call_name for r in summary.by_call_name] == ["tesseract", "whisper"]
    assert (row("whisper").kind, row("whisper").call_count, row("whisper").error_count) == (
        CallKind.ASR,
        2,
        1,
    )


def test_p50_and_p95_latency():
    for ms in range(1, 101):
        record_call("m", float(ms), None)
    assert (row("m").p50_duration_ms, row("m").p95_duration_ms) == (50.5, 95.05)


def test_percentile_of_one_value_is_that_value():
    assert percentile([7.0], 95) == 7.0
    assert percentile([], 95) == 0.0


def test_totals_outlive_the_ring_buffer_of_recent_calls(monkeypatch: pytest.MonkeyPatch):
    # get_recent_calls() keeps the last N; the summary's totals are since start-up.
    monkeypatch.setattr(tracking, "_records", deque(maxlen=3))
    for _ in range(5):
        record_call("m", 1.0, 0.5)
    assert (row("m").call_count, row("m").cost_total_usd) == (5, 2.5)


# --- missing is not zero ------------------------------------------------------------


def test_cpu_never_measured_is_none_not_zero():
    record_call("ocr-on-windows", 10.0, None, cpu_s=None)
    record_call("ocr-on-windows", 10.0, None, cpu_s=None)
    assert (row("ocr-on-windows").cpu_s_total, row("ocr-on-windows").cpu_unmeasured_count) == (
        None,
        2,
    )


def test_cpu_measured_as_zero_is_zero_not_none():
    record_call("idle", 10.0, None, cpu_s=0.0)
    assert (row("idle").cpu_s_total, row("idle").cpu_unmeasured_count) == (0.0, 0)


def test_cpu_partly_measured_sums_what_was_measured_and_counts_the_rest():
    record_call("m", 10.0, None, cpu_s=1.25)
    record_call("m", 10.0, None, cpu_s=None)
    record_call("m", 10.0, None, cpu_s=2.0)
    assert (row("m").cpu_s_total, row("m").cpu_unmeasured_count) == (3.25, 1)


def test_usage_never_reported_is_none():
    record_call("ocr", 10.0, None, kind=CallKind.OCR)
    r = row("ocr")
    assert (r.prompt_tokens_total, r.completion_tokens_total, r.tokens_unreported_count) == (
        None,
        None,
        1,
    )


def test_reported_usage_is_summed_and_the_unreported_calls_counted():
    record_call("llm", 10.0, 0.001, prompt_tokens=900, completion_tokens=100)
    record_call("llm", 10.0, None)  # a failed call: no usage
    record_call("llm", 10.0, 0.002, prompt_tokens=1100, completion_tokens=0)
    r = row("llm")
    assert (r.prompt_tokens_total, r.completion_tokens_total, r.tokens_unreported_count) == (
        2000,
        100,
        1,
    )


def test_half_a_usage_pair_counts_as_unreported():
    record_call("llm", 10.0, None, prompt_tokens=900, completion_tokens=None)
    r = row("llm")
    assert (r.prompt_tokens_total, r.tokens_unreported_count) == (None, 1)


def test_cost_never_priced_is_none_not_zero():
    record_call("asr", 10.0, None)
    assert (row("asr").cost_total_usd, row("asr").unpriced_count) == (None, 1)


def test_a_priced_zero_cost_is_zero_not_none():
    record_call("free", 10.0, 0.0)
    assert (row("free").cost_total_usd, row("free").unpriced_count) == (0.0, 0)


def test_cost_partly_priced_is_a_lower_bound_with_the_gap_counted():
    record_call("llm", 10.0, 0.25)
    record_call("llm", 10.0, None)
    assert (row("llm").cost_total_usd, row("llm").unpriced_count) == (0.25, 1)


# --- limits -------------------------------------------------------------------------


def test_a_call_over_its_kinds_duration_limit_is_slow():
    record_call("tesseract", 30_000.0, None, kind=CallKind.OCR)  # at the limit: not over
    record_call("tesseract", 30_001.0, None, kind=CallKind.OCR)
    r = row("tesseract")
    assert (r.duration_limit_ms, r.slow_count) == (30_000, 1)


def test_each_kind_has_its_own_default_limit():
    record_call("whisper", 60_000.0, None, kind=CallKind.ASR)  # fine for ASR (120 s)
    record_call("deepseek", 60_000.0, None, kind=CallKind.LLM)  # slow for an LLM (30 s)
    assert (row("whisper").slow_count, row("deepseek").slow_count) == (0, 1)


def test_a_failed_slow_call_still_counts_as_slow():
    record_call("deepseek", 60_000.0, None, kind=CallKind.LLM, status=CallStatus.ERROR)
    assert row("deepseek").slow_count == 1


def test_a_call_over_its_kinds_cost_limit_is_expensive():
    record_call("deepseek", 10.0, 0.01, kind=CallKind.LLM)  # at the limit: not over
    record_call("deepseek", 10.0, 0.02, kind=CallKind.LLM)
    r = row("deepseek")
    assert (r.cost_limit_usd, r.expensive_count) == (0.01, 1)


def test_an_unpriced_call_is_never_expensive_only_unpriced():
    record_call("gemini", 10.0, None, kind=CallKind.LLM)
    r = row("gemini")
    assert (r.expensive_count, r.unpriced_count) == (0, 1)


def test_a_call_without_a_kind_has_no_limits():
    record_call("unclassified", 999_999.0, 99.0)
    r = row("unclassified")
    assert (r.duration_limit_ms, r.cost_limit_usd, r.slow_count, r.expensive_count) == (
        None,
        None,
        0,
        0,
    )


def test_limits_are_configurable_and_none_turns_a_check_off(monkeypatch: pytest.MonkeyPatch):
    use_limits(monkeypatch, ocr_max_duration_ms=100.0, ocr_max_cost_usd=0.5)
    record_call("tesseract", 150.0, 0.75, kind=CallKind.OCR)
    assert (row("tesseract").slow_count, row("tesseract").expensive_count) == (1, 1)

    _reset_for_tests()
    use_limits(monkeypatch, ocr_max_duration_ms=None, ocr_max_cost_usd=None)
    record_call("tesseract", 150.0, 0.75, kind=CallKind.OCR)
    r = row("tesseract")
    assert (r.duration_limit_ms, r.slow_count, r.expensive_count) == (None, 0, 0)


# --- logged summary -----------------------------------------------------------------


def test_the_summary_is_logged_every_n_calls(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    use_limits(monkeypatch, summary_every_n_calls=3)
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        record_call("m", 1.0, None)
        record_call("m", 1.0, None)
        assert not [m for m in caplog.messages if m.startswith("model_call_summary")]
        record_call("m", 1.0, None)
    [line] = [m for m in caplog.messages if m.startswith("model_call_summary")]
    assert "calls=3" in line


def test_zero_turns_the_periodic_summary_off(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    use_limits(monkeypatch, summary_every_n_calls=0)
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        for _ in range(5):
            record_call("m", 1.0, None)
    assert not [m for m in caplog.messages if m.startswith("model_call_summary")]


def test_the_logged_summary_names_what_is_missing(caplog: pytest.LogCaptureFixture):
    record_call("tesseract", 31_000.0, None, kind=CallKind.OCR)
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        log_summary()
    [line] = [m for m in caplog.messages if m.startswith("model_call_summary")]
    assert "call_name=tesseract kind=ocr calls=1" in line
    assert "cpu_s=unmeasured cpu_unmeasured=1" in line
    assert "prompt_tokens=unreported" in line
    assert "cost=unpriced unpriced=1" in line
    assert "slow=1" in line


def test_reset_clears_the_totals_too():
    record_call("m", 1.0, None)
    _reset_for_tests()
    assert model_call_summary().total_calls == 0


# --- over HTTP ----------------------------------------------------------------------


def test_debug_model_calls_serves_the_summary_with_nulls_for_unknowns():
    record_call("tesseract", 1200.0, None, kind=CallKind.OCR, cpu_s=None)
    record_call(
        "deepseek-chat", 800.0, 0.0005, kind=CallKind.LLM, prompt_tokens=900, completion_tokens=150
    )

    body = TestClient(app).get("/debug/model-calls").json()
    assert body["total_calls"] == 2
    by_name = {r["call_name"]: r for r in body["by_call_name"]}
    ocr = by_name["tesseract"]
    assert (ocr["kind"], ocr["cpu_s_total"], ocr["cost_total_usd"], ocr["unpriced_count"]) == (
        "ocr",
        None,
        None,
        1,
    )
    llm = by_name["deepseek-chat"]
    assert (llm["prompt_tokens_total"], llm["completion_tokens_total"]) == (900, 150)
    assert llm["cost_total_usd"] == 0.0005
    assert (llm["duration_limit_ms"], llm["cost_limit_usd"]) == (30_000, 0.01)
