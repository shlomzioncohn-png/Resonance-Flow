"""CPU time for the local model calls (src/mind/observability/compute.py) -- #479."""

import subprocess
import sys
import time

import pytest

from mind.observability.compute import CpuClock


def burn_cpu(seconds: float) -> None:
    started = time.process_time()
    while time.process_time() - started < seconds:
        pass


def burn_cpu_in_a_child(seconds: float) -> None:
    code = f"import time\nt = time.process_time()\nwhile time.process_time() - t < {seconds}: pass"
    subprocess.run([sys.executable, "-c", code], check=True)


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
