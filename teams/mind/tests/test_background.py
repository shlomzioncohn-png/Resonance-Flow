"""#920: BackgroundThreads runs loops inside the API, runs a failed one again
after a growing wait, and stops them all on shutdown, without ever taking the
process down."""

import logging
import threading
import time

import pytest

from mind.messaging import background
from mind.messaging.background import BackgroundThreads


def _loop(stop: threading.Event) -> None:
    """A well-behaved target: runs until asked to stop."""
    stop.wait()


def _wait_until(condition, seconds: float = 2.0) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.01)
    return condition()


@pytest.fixture
def quick_retries(monkeypatch):
    """Retries after 10 ms instead of 1 s, so a test sees several of them."""
    monkeypatch.setattr(background, "_FIRST_RESTART_WAIT", 0.01)


def test_running_threads_are_healthy():
    threads = BackgroundThreads([("a", _loop), ("b", _loop)])
    threads.start()

    assert threads.healthy

    threads.stop()


def test_names_are_the_threads_names():
    threads = BackgroundThreads([("a", _loop), ("b", _loop)])

    assert threads.names == ["a", "b"]


def test_stop_ends_every_thread():
    started: list[str] = []
    ended: list[str] = []

    def loop(stop: threading.Event) -> None:
        started.append("x")
        stop.wait()
        ended.append("x")

    threads = BackgroundThreads([("a", loop), ("b", loop)])
    threads.start()
    assert _wait_until(lambda: len(started) == 2)

    threads.stop()  # waits for each thread

    assert len(ended) == 2


# --- a failed thread runs again ---------------------------------------------


def test_a_failed_thread_runs_again_and_becomes_healthy_again(quick_retries):
    # The mentor's case: one Kafka failure must not stop the thread for good.
    runs: list[str] = []

    def fails_once(stop: threading.Event) -> None:
        runs.append("run")
        if len(runs) == 1:
            raise RuntimeError("broker down")
        stop.wait()

    threads = BackgroundThreads([("consumer", fails_once)])
    threads.start()

    assert _wait_until(lambda: len(runs) == 2)
    assert _wait_until(lambda: threads.healthy)

    threads.stop()


def test_it_stays_healthy_while_waiting_to_run_again(caplog):
    # Michal Brecher's liveness probe reads `healthy`: a Kafka outage (or Kafka
    # down at startup) must not restart the API. The first retry waits a full
    # second here, so the check falls inside the wait.
    caplog.set_level(logging.INFO, logger="mind.messaging.background")
    threads = BackgroundThreads([("broken", lambda stop: 1 / 0), ("ok", _loop)])
    threads.start()

    assert _wait_until(lambda: "again in 1.0s" in caplog.text)
    assert threads.healthy

    threads.stop()


@pytest.mark.filterwarnings("ignore::pytest.PytestUnhandledThreadExceptionWarning")
def test_a_thread_that_died_is_unhealthy():
    # What the retries can't catch, such as SystemExit, ends the thread for good.
    def exits(stop: threading.Event) -> None:
        raise SystemExit

    threads = BackgroundThreads([("dies", exits), ("ok", _loop)])
    threads.start()

    assert _wait_until(lambda: not threads.healthy)

    threads.stop()


def test_a_failing_thread_does_not_stop_the_others(quick_retries):
    still_running = threading.Event()

    def healthy_loop(stop: threading.Event) -> None:
        still_running.set()
        stop.wait()

    threads = BackgroundThreads([("broken", lambda stop: 1 / 0), ("ok", healthy_loop)])
    threads.start()

    assert _wait_until(still_running.is_set)
    time.sleep(0.1)  # several failed runs of "broken"
    assert any(t.name == "ok" and t.is_alive() for t in threading.enumerate())

    threads.stop()


def test_the_wait_doubles_after_each_failure(quick_retries, caplog):
    caplog.set_level(logging.INFO, logger="mind.messaging.background")
    threads = BackgroundThreads([("broken", lambda stop: 1 / 0)])
    threads.start()

    assert _wait_until(lambda: "again in 0.04s" in caplog.text)
    threads.stop()

    assert "again in 0.01s" in caplog.text
    assert "again in 0.02s" in caplog.text


def test_a_thread_that_returns_on_its_own_runs_again(quick_retries):
    # Every target loops until stopped; returning early means it stopped working.
    runs: list[str] = []

    def returns_once(stop: threading.Event) -> None:
        runs.append("run")
        if len(runs) > 1:
            stop.wait()

    threads = BackgroundThreads([("early", returns_once)])
    threads.start()

    assert _wait_until(lambda: len(runs) == 2)

    threads.stop()


def test_stop_does_not_wait_out_the_backoff(monkeypatch, caplog):
    # A shutdown must not wait up to 60 s for a thread between two retries.
    caplog.set_level(logging.INFO, logger="mind.messaging.background")
    monkeypatch.setattr(background, "_FIRST_RESTART_WAIT", 30.0)
    threads = BackgroundThreads([("broken", lambda stop: 1 / 0)])
    threads.start()
    assert _wait_until(lambda: "again in 30.0s" in caplog.text)

    began = time.monotonic()
    threads.stop()

    assert time.monotonic() - began < 2


def test_a_thread_that_returns_after_stop_is_not_a_failure(caplog):
    threads = BackgroundThreads([("a", _loop)])
    threads.start()

    threads.stop()

    assert "stopped on its own" not in caplog.text
    assert "failed" not in caplog.text
