"""Tests for MIND's call-timing/cost wrapper (src/mind/api/instrumentation.py).

instrumentation_summary() is a shared, process-wide aggregate -- other
tests (e.g. test_api.py hitting /health) record into it too. Every
assertion here is delta-based (before/after a known number of calls)
rather than an absolute count, so these tests are safe to run in any
order alongside the rest of the suite.
"""

from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient

from mind.api.instrumentation import instrumentation_summary, instrumented_call
from mind.api.main import app

client = TestClient(app)


def test_context_manager_records_a_successful_measurement():
    before = instrumentation_summary()["call_count"]

    with instrumented_call("test_context_manager", cost=0.5):
        time.sleep(0.01)

    after = instrumentation_summary()
    assert after["call_count"] == before + 1
    assert after["cumulative_cost"] >= 0.5


def test_decorator_form_records_a_measurement_and_returns_the_value():
    before = instrumentation_summary()["call_count"]

    @instrumented_call("test_decorator")
    def do_work() -> str:
        return "done"

    assert do_work() == "done"
    assert instrumentation_summary()["call_count"] == before + 1


def test_error_inside_wrapped_call_is_recorded_and_reraised():
    before = instrumentation_summary()
    call_count_before = before["call_count"]
    error_count_before = before["error_count"]

    with pytest.raises(ValueError, match="boom"):
        with instrumented_call("test_error"):
            raise ValueError("boom")

    after = instrumentation_summary()
    assert after["call_count"] == call_count_before + 1
    assert after["error_count"] == error_count_before + 1


def test_health_endpoint_is_instrumented():
    before = instrumentation_summary()["call_count"]

    response = client.get("/health")

    assert response.status_code == 200
    assert instrumentation_summary()["call_count"] == before + 1


def test_simulate_call_endpoint_is_instrumented():
    before = instrumentation_summary()["call_count"]

    response = client.get("/debug/simulate-call")

    assert response.status_code == 200
    assert instrumentation_summary()["call_count"] == before + 1


def test_debug_instrumentation_endpoint_reflects_recorded_calls():
    before = client.get("/debug/instrumentation").json()["call_count"]

    client.get("/debug/simulate-call")
    client.get("/debug/simulate-call")

    after = client.get("/debug/instrumentation").json()
    assert after["call_count"] == before + 2
