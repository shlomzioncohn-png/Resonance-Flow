"""Tests for MIND's model-call tracking (src/mind/observability)."""

import pytest

from mind.observability.tracking import _reset_for_tests, get_recent_calls, record_call


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
