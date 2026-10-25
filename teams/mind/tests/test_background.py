"""#920: BackgroundThreads runs loops inside the API, runs a failed one again
after a growing wait, and stops them all on shutdown, without ever taking the
process down."""

import logging
import threading
import time

import pytest

from mind.messaging import background
from mind.messaging.background import BackgroundThreads


def _loop(stop: threading.Event, progress) -> None:
    """A well-behaved target: runs until asked to stop."""
    stop.wait()


def _broken(stop: threading.Event, progress) -> None:
    """A bad message at the head of a topic: fails on every run."""
    raise RuntimeError("cannot deserialize")


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

    def loop(stop: threading.Event, progress) -> None:
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

    def fails_once(stop: threading.Event, progress) -> None:
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
    threads = BackgroundThreads([("broken", _broken), ("ok", _loop)])
    threads.start()

    assert _wait_until(lambda: "again in 1.0s" in caplog.text)
    assert threads.healthy

    threads.stop()


@pytest.mark.filterwarnings("ignore::pytest.PytestUnhandledThreadExceptionWarning")
def test_a_thread_that_died_is_unhealthy():
    # What the retries can't catch, such as SystemExit, ends the thread for good.
    def exits(stop: threading.Event, progress) -> None:
        raise SystemExit

    threads = BackgroundThreads([("dies", exits), ("ok", _loop)])
    threads.start()

    assert _wait_until(lambda: not threads.healthy)

    threads.stop()


def test_a_failing_thread_does_not_stop_the_others(quick_retries):
    still_running = threading.Event()

    def healthy_loop(stop: threading.Event, progress) -> None:
        still_running.set()
        stop.wait()

    threads = BackgroundThreads([("broken", _broken), ("ok", healthy_loop)])
    threads.start()

    assert _wait_until(still_running.is_set)
    time.sleep(0.1)  # several failed runs of "broken"
    assert any(t.name == "ok" and t.is_alive() for t in threading.enumerate())

    threads.stop()


def test_the_wait_doubles_after_each_failure(quick_retries, caplog):
    caplog.set_level(logging.INFO, logger="mind.messaging.background")
    threads = BackgroundThreads([("broken", _broken)])
    threads.start()

    assert _wait_until(lambda: "again in 0.04s" in caplog.text)
    threads.stop()

    assert "again in 0.01s" in caplog.text
    assert "again in 0.02s" in caplog.text


def test_a_thread_that_returns_on_its_own_runs_again(quick_retries):
    # Every target loops until stopped; returning early means it stopped working.
    runs: list[str] = []

    def returns_once(stop: threading.Event, progress) -> None:
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
    threads = BackgroundThreads([("broken", _broken)])
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


# --- a thread that keeps failing is visible (mentor's review) ---------------


def test_a_running_thread_is_not_retrying():
    threads = BackgroundThreads([("a", _loop)])
    threads.start()

    assert threads.retrying == []

    threads.stop()


def test_a_failing_thread_is_retrying_and_still_healthy(quick_retries):
    # A bad message at the head of a topic: it fails on every run, forever.
    threads = BackgroundThreads([("broken", _broken), ("ok", _loop)])
    threads.start()

    assert _wait_until(lambda: threads.retrying == ["broken"])
    assert threads.healthy  # liveness must not restart the API for it

    threads.stop()


def test_a_thread_leaves_retrying_once_it_makes_progress(quick_retries):
    # Recovery is progress (a message handled), not time: deterministic here.
    runs: list[str] = []
    go = threading.Event()

    def fails_once(stop: threading.Event, progress) -> None:
        runs.append("run")
        if len(runs) == 1:
            raise RuntimeError("broker down")
        go.wait()
        progress()
        stop.wait()

    threads = BackgroundThreads([("consumer", fails_once)])
    threads.start()

    assert _wait_until(lambda: len(runs) == 2)
    assert threads.retrying == ["consumer"]  # running again, nothing handled yet
    go.set()
    assert _wait_until(lambda: threads.retrying == [])

    threads.stop()


def test_a_slow_failure_is_not_taken_for_a_recovery(monkeypatch, quick_retries, caplog):
    # A send to a broker that is down blocks for minutes, then fails. A run
    # longer than the longest wait must still count as a failure in a row.
    monkeypatch.setattr(background, "_MAX_RESTART_WAIT", 0.04)
    caplog.set_level(logging.INFO, logger="mind.messaging.background")

    def fails_slowly(stop: threading.Event, progress) -> None:
        time.sleep(0.06)  # longer than the longest wait
        raise RuntimeError("delivery timed out")

    threads = BackgroundThreads([("producer", fails_slowly)])
    threads.start()

    assert _wait_until(lambda: "keeps failing" in caplog.text)
    assert threads.retrying == ["producer"]

    threads.stop()


def test_an_idle_thread_recovers_after_running_quietly_long_enough(monkeypatch, quick_retries):
    # An idle topic handles nothing, so has no progress to report: running
    # _RECOVERED_AFTER with no new failure counts as recovered too.
    monkeypatch.setattr(background, "_RECOVERED_AFTER", 0.2)
    runs: list[str] = []

    def fails_once_then_idles(stop: threading.Event, progress) -> None:
        runs.append("run")
        if len(runs) == 1:
            raise RuntimeError("broker down")
        stop.wait()

    threads = BackgroundThreads([("idle", fails_once_then_idles)])
    threads.start()

    assert _wait_until(lambda: len(runs) == 2)
    assert _wait_until(lambda: threads.retrying == [])

    threads.stop()


def test_a_thread_still_failing_at_the_longest_wait_logs_an_error(
    monkeypatch, quick_retries, caplog
):
    # 0.01, 0.02, then the longest wait (0.04 here, 60 s in production).
    monkeypatch.setattr(background, "_MAX_RESTART_WAIT", 0.04)
    caplog.set_level(logging.INFO, logger="mind.messaging.background")
    threads = BackgroundThreads([("broken", _broken)])
    threads.start()

    assert _wait_until(lambda: "keeps failing" in caplog.text)
    threads.stop()

    stuck = [r for r in caplog.records if "keeps failing" in r.getMessage()]
    assert stuck[0].levelno == logging.ERROR
    assert "broken keeps failing (3 times in a row)" in stuck[0].getMessage()
    # Before it, a failure is a WARNING and a short wait INFO: a blip isn't an alert.
    failed = [r for r in caplog.records if "broken failed" in r.getMessage()]
    assert failed[0].levelno == logging.WARNING
    shorter = [r for r in caplog.records if "again in 0.01s" in r.getMessage()]
    assert shorter[0].levelno == logging.INFO


def test_a_failure_during_shutdown_is_not_retried_or_logged(caplog):
    # A send that fails because the API is stopping must not log a retry.
    caplog.set_level(logging.INFO, logger="mind.messaging.background")

    def fails_on_stop(stop: threading.Event, progress) -> None:
        stop.wait()
        raise RuntimeError("closed while stopping")

    threads = BackgroundThreads([("a", fails_on_stop)])
    threads.start()
    threads.stop()

    assert not threads.healthy
    assert "again" not in caplog.text
    assert "keeps failing" not in caplog.text


def test_stop_waits_for_all_threads_together_not_each_in_turn(monkeypatch):
    # Three stuck threads (a send blocked on a hung broker) cost one wait, not three.
    monkeypatch.setattr(background, "_JOIN_SECONDS", 0.3)
    release = threading.Event()

    def stuck(stop: threading.Event, progress) -> None:
        release.wait()

    threads = BackgroundThreads([("a", stuck), ("b", stuck), ("c", stuck)])
    threads.start()

    began = time.monotonic()
    threads.stop()
    took = time.monotonic() - began
    release.set()

    assert took < 0.6
