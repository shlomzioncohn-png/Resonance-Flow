"""Per-call WARNING lines for a model call over its kind's limits -- #479.

The rules: strictly over a limit warns, at it does not; an unpriced call is
never expensive (its cost is unknown, not high and not zero); and every
warning has a matching count in the summary, because both read the same
check_limits() result.
"""

import logging

import pytest

from mind.config import MonitoringSettings
from mind.observability import tracking
from mind.observability.tracking import (
    CallKind,
    CallStatus,
    _reset_for_tests,
    check_limits,
    model_call_summary,
    record_call,
)


@pytest.fixture(autouse=True)
def _clean_call_records():
    _reset_for_tests()


@pytest.fixture
def warnings_logged(caplog: pytest.LogCaptureFixture) -> pytest.LogCaptureFixture:
    """Capture the tracking logger from INFO up.

    Returns the fixture, not caplog.records: pytest keeps a separate record
    list per test phase, so a list taken here, during setup, stays empty.
    """
    caplog.set_level(logging.INFO, logger="mind.observability")
    return caplog


def warned(caplog: pytest.LogCaptureFixture, marker: str) -> list[str]:
    return [
        r.getMessage()
        for r in caplog.records
        if r.levelno == logging.WARNING and r.getMessage().startswith(marker)
    ]


def use_limits(monkeypatch: pytest.MonkeyPatch, **limits: object) -> None:
    monkeypatch.setattr(
        tracking, "monitoring_settings", lambda: MonitoringSettings.model_construct(**limits)
    )


# --- slow ---------------------------------------------------------------------------


def test_a_call_over_its_duration_limit_warns_once(warnings_logged):
    record_call("tesseract", 45_000.0, None, kind=CallKind.OCR)
    [line] = warned(warnings_logged, "MODEL_CALL_SLOW")
    assert "model_name=tesseract kind=ocr status=success" in line
    assert "duration_ms=45000.0 limit_ms=30000.0 over=1.5x" in line
    assert warned(warnings_logged, "MODEL_CALL_EXPENSIVE") == []


def test_a_call_exactly_at_its_duration_limit_does_not_warn(warnings_logged):
    record_call("tesseract", 30_000.0, None, kind=CallKind.OCR)
    assert warned(warnings_logged, "MODEL_CALL_SLOW") == []


def test_a_failed_slow_call_warns_and_says_it_failed(warnings_logged):
    record_call("deepseek-chat", 60_000.0, None, kind=CallKind.LLM, status=CallStatus.ERROR)
    [line] = warned(warnings_logged, "MODEL_CALL_SLOW")
    assert "status=error" in line and "over=6.0x" in line  # the LLM limit is 10 s


# --- expensive ----------------------------------------------------------------------


def test_a_call_over_its_cost_limit_warns_once(warnings_logged):
    record_call("deepseek-chat", 100.0, 0.05, kind=CallKind.LLM)
    [line] = warned(warnings_logged, "MODEL_CALL_EXPENSIVE")
    assert "model_name=deepseek-chat kind=llm" in line
    assert "cost_usd=0.050000 limit_usd=0.020000 over=2.5x" in line
    assert warned(warnings_logged, "MODEL_CALL_SLOW") == []


def test_a_call_exactly_at_its_cost_limit_does_not_warn(warnings_logged):
    record_call("deepseek-chat", 100.0, 0.02, kind=CallKind.LLM)
    assert warned(warnings_logged, "MODEL_CALL_EXPENSIVE") == []


def test_an_unpriced_call_is_never_expensive(warnings_logged):
    # LLM has a cost limit; this call's cost is unknown -- not over it, and not 0.
    record_call("gemini-3.6-flash", 100.0, None, kind=CallKind.LLM)
    assert warned(warnings_logged, "MODEL_CALL_EXPENSIVE") == []
    assert check_limits(tracking.get_recent_calls()[0]).expensive is False
    [row] = model_call_summary().by_call_name
    assert (row.expensive_count, row.unpriced_count, row.cost_usd_total) == (0, 1, None)


def test_a_zero_cost_limit_flags_any_positive_cost_but_not_a_free_call(
    monkeypatch: pytest.MonkeyPatch, warnings_logged
):
    use_limits(monkeypatch, llm_max_cost_usd=0.0)
    record_call("free", 100.0, 0.0, kind=CallKind.LLM)
    record_call("paid", 100.0, 0.000001, kind=CallKind.LLM)
    record_call("unknown", 100.0, None, kind=CallKind.LLM)
    [line] = warned(warnings_logged, "MODEL_CALL_EXPENSIVE")
    assert "model_name=paid" in line and "over=-" in line  # no ratio against 0


# --- both, neither ------------------------------------------------------------------


def test_a_call_over_both_limits_warns_twice(warnings_logged):
    record_call("deepseek-chat", 31_000.0, 0.03, kind=CallKind.LLM)
    assert len(warned(warnings_logged, "MODEL_CALL_SLOW")) == 1
    assert len(warned(warnings_logged, "MODEL_CALL_EXPENSIVE")) == 1


def test_a_call_without_a_kind_never_warns(warnings_logged):
    record_call("unclassified", 999_999.0, 99.0)
    assert warned(warnings_logged, "MODEL_CALL_") == []


def test_a_check_turned_off_never_warns(monkeypatch: pytest.MonkeyPatch, warnings_logged):
    use_limits(monkeypatch, llm_max_duration_ms=None, llm_max_cost_usd=None)
    record_call("deepseek-chat", 999_999.0, 99.0, kind=CallKind.LLM)
    assert warned(warnings_logged, "MODEL_CALL_") == []


def test_a_call_within_its_limits_logs_only_at_info(warnings_logged):
    record_call("whisper", 5_000.0, 0.0001, kind=CallKind.ASR, cpu_s=4.0)
    assert [r for r in warnings_logged.records if r.levelno >= logging.WARNING] == []
    assert any(r.getMessage().startswith("model_call ") for r in warnings_logged.records)


# --- warnings and counters agree ----------------------------------------------------


def test_every_warning_has_its_count_in_the_summary_and_no_more(warnings_logged):
    calls = [
        ("tesseract", CallKind.OCR, 29_000.0, None, CallStatus.SUCCESS),
        ("tesseract", CallKind.OCR, 31_000.0, None, CallStatus.SUCCESS),  # slow
        ("tesseract", CallKind.OCR, 90_000.0, None, CallStatus.ERROR),  # slow
        ("whisper", CallKind.ASR, 119_000.0, None, CallStatus.SUCCESS),
        ("whisper", CallKind.ASR, 121_000.0, None, CallStatus.SUCCESS),  # slow; no cost limit
        ("deepseek-chat", CallKind.LLM, 1_000.0, 0.002, CallStatus.SUCCESS),
        ("deepseek-chat", CallKind.LLM, 1_000.0, 0.05, CallStatus.SUCCESS),  # expensive
        ("deepseek-chat", CallKind.LLM, 45_000.0, 0.03, CallStatus.SUCCESS),  # both
        ("deepseek-chat", CallKind.LLM, 60_000.0, None, CallStatus.ERROR),  # slow, unpriced
        ("gemini-3.6-flash", CallKind.LLM, 1_000.0, None, CallStatus.SUCCESS),  # unpriced
    ]
    for name, kind, ms, cost, status in calls:
        record_call(name, ms, cost, kind=kind, status=status)

    for row in model_call_summary().by_call_name:
        slow = [w for w in warned(warnings_logged, "MODEL_CALL_SLOW") if row.call_name in w]
        expensive = [
            w for w in warned(warnings_logged, "MODEL_CALL_EXPENSIVE") if row.call_name in w
        ]
        assert (len(slow), len(expensive)) == (row.slow_count, row.expensive_count), row
    counts = {
        r.call_name: (r.slow_count, r.expensive_count) for r in model_call_summary().by_call_name
    }
    assert counts == {
        "deepseek-chat": (2, 2),
        "gemini-3.6-flash": (0, 0),
        "tesseract": (2, 0),
        "whisper": (1, 0),
    }
