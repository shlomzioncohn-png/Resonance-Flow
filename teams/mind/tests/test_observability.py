"""Tests for MIND's model-call tracking (src/mind/observability)."""

from mind.observability.tracking import get_recent_calls, record_call


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
