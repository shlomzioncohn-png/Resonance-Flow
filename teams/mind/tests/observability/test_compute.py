"""CPU time and estimated cost for local model calls (src/mind/observability/compute.py)."""

import subprocess
import sys
import time

import pytest

from mind.observability import compute
from mind.observability.compute import CpuClock, compute_cost

# The real one: tests/conftest.py replaces compute.usd_per_cpu_hour for every test,
# after this module has been imported.
_real_usd_per_cpu_hour = compute.usd_per_cpu_hour

_RATE_ENV = "MIND_COMPUTE_COST__USD_PER_CPU_HOUR"


def burn_cpu(seconds: float) -> None:
    started = time.process_time()
    while time.process_time() - started < seconds:
        pass


def burn_cpu_in_a_child(seconds: float) -> None:
    code = f"import time\nt = time.process_time()\nwhile time.process_time() - t < {seconds}: pass"
    subprocess.run([sys.executable, "-c", code], check=True)


# --- the rate -----------------------------------------------------------------------


def test_no_rate_on_file_means_unpriced_not_free():
    assert compute_cost(10.0) is None


def test_cpu_that_was_not_measured_is_unpriced_even_with_a_rate(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(compute, "usd_per_cpu_hour", lambda: 0.05)
    assert compute_cost(None) is None


def test_cost_is_cpu_hours_times_the_rate(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(compute, "usd_per_cpu_hour", lambda: 0.036)
    assert compute_cost(100.0) == pytest.approx(0.001)  # 100 s = 1/36 h
    assert compute_cost(0.0) == 0.0


def test_the_rate_is_read_once_per_process(monkeypatch: pytest.MonkeyPatch):
    _real_usd_per_cpu_hour.cache_clear()
    try:
        monkeypatch.setenv(_RATE_ENV, "0.5")
        assert _real_usd_per_cpu_hour() == 0.5
        monkeypatch.setenv(_RATE_ENV, "9.0")
        assert _real_usd_per_cpu_hour() == 0.5  # cached: a change needs a restart
    finally:
        _real_usd_per_cpu_hour.cache_clear()


# --- the clock ----------------------------------------------------------------------


def test_the_clock_counts_this_process_cpu():
    clock = CpuClock()
    burn_cpu(0.2)
    elapsed = clock.elapsed_s()
    assert elapsed is not None and elapsed >= 0.2


def test_the_clock_does_not_count_waiting():
    clock = CpuClock()
    time.sleep(0.3)
    elapsed = clock.elapsed_s()
    assert elapsed is not None and elapsed < 0.1


@pytest.mark.skipif(sys.platform == "win32", reason="Windows reports no child-process CPU")
def test_the_clock_counts_a_child_process_cpu_when_asked():
    clock = CpuClock(include_children=True)
    burn_cpu_in_a_child(0.3)
    elapsed = clock.elapsed_s()
    assert elapsed is not None and elapsed >= 0.3


@pytest.mark.skipif(sys.platform == "win32", reason="Windows reports no child-process CPU")
def test_the_clock_leaves_a_child_process_out_unless_asked():
    clock = CpuClock()
    burn_cpu_in_a_child(0.3)
    elapsed = clock.elapsed_s()
    assert elapsed is not None and elapsed < 0.2


@pytest.mark.skipif(sys.platform != "win32", reason="only Windows lacks child-process CPU")
def test_child_cpu_that_cannot_be_measured_is_none_not_zero():
    clock = CpuClock(include_children=True)
    burn_cpu_in_a_child(0.1)
    assert clock.elapsed_s() is None
