"""Tests for MIND's model-call tracking (src/mind/observability)."""

import logging

import pytest

from mind.observability.tracking import (
    CallKind,
    CallStatus,
    _reset_for_tests,
    get_recent_calls,
    record_call,
)


@pytest.fixture(autouse=True)
def _clean_call_records():
    """_records in tracking.py is a module-level global -- without this,
    one test's calls would leak into the next, making assertions here
    depend on execution order instead of what each test actually did."""
    _reset_for_tests()


def test_record_call_stores_and_returns_the_record():
    record = record_call(model_name="test-model", duration_ms=42.0, cost=0.0015)

    assert record.model_name == "test-model"
    assert record.duration_ms == 42.0
    assert record.cost == 0.0015
    assert record in get_recent_calls()


def test_get_recent_calls_respects_limit():
    for i in range(5):
        record_call(model_name=f"model-{i}", duration_ms=1.0, cost=0.0)

    recent = get_recent_calls(limit=2)
    assert len(recent) == 2
    # most recent last
    assert recent[-1].model_name == "model-4"


def test_the_optional_fields_default_to_unknown_not_zero():
    record = record_call(model_name="test-model", duration_ms=1.0, cost=None)

    assert record.cost is None
    assert record.kind is None
    assert record.status == CallStatus.SUCCESS
    assert record.cpu_s is None
    assert (record.prompt_tokens, record.completion_tokens) == (None, None)


def test_record_call_keeps_every_field_it_is_given():
    record = record_call(
        "deepseek-chat",
        1200.0,
        0.00042,
        kind=CallKind.LLM,
        status=CallStatus.ERROR,
        cpu_s=0.01,
        prompt_tokens=900,
        completion_tokens=150,
    )

    assert get_recent_calls() == [record]
    assert (record.kind, record.status) == (CallKind.LLM, CallStatus.ERROR)
    assert record.cpu_s == 0.01
    assert (record.prompt_tokens, record.completion_tokens) == (900, 150)


def test_the_log_line_names_its_units_and_what_does_not_apply(caplog: pytest.LogCaptureFixture):
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        record_call("whisper-base.en-int8-local", 3500.0, None, kind=CallKind.ASR, cpu_s=7.25)

    line = caplog.messages[-1]
    assert "kind=asr status=success duration_ms=3500.0" in line
    assert "cpu_seconds=7.250" in line
    # Tokens and dollars are LLM measures: not applicable to ASR, not unknown.
    assert "prompt_tokens=n/a completion_tokens=n/a cost_usd=n/a" in line


def test_the_log_line_tells_unknown_from_not_applicable(caplog: pytest.LogCaptureFixture):
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        record_call("deepseek-chat", 900.0, None, kind=CallKind.LLM)

    line = caplog.messages[-1]
    assert "cpu_seconds=n/a" in line  # CPU is an OCR/ASR measure
    assert "prompt_tokens=unreported completion_tokens=unreported" in line
    assert "cost_usd=unpriced" in line  # applies, but unknown -- not 0
