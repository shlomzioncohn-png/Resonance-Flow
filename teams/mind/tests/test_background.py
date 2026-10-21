"""#920: BackgroundThreads runs loops inside the API, stops them on shutdown,
and reports a dead thread instead of taking the process down."""

import threading
import time

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


def test_running_threads_are_healthy():
    threads = BackgroundThreads([("a", _loop), ("b", _loop)])
    threads.start()

    assert threads.healthy

    threads.stop()


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


def test_a_thread_that_raises_makes_it_unhealthy_without_stopping_the_others():
    # In the API a dead thread must not take the process down; it is reported.
    still_running = threading.Event()

    def broken(stop: threading.Event) -> None:
        raise RuntimeError("broker down")

    def healthy_loop(stop: threading.Event) -> None:
        still_running.set()
        stop.wait()

    threads = BackgroundThreads([("broken", broken), ("ok", healthy_loop)])
    threads.start()

    assert _wait_until(lambda: not threads.healthy)
    assert still_running.is_set()

    threads.stop()


def test_a_thread_that_returns_on_its_own_makes_it_unhealthy():
    # Every target loops until stopped; returning early means it stopped working.
    threads = BackgroundThreads([("early", lambda stop: None), ("ok", _loop)])
    threads.start()

    assert _wait_until(lambda: not threads.healthy)

    threads.stop()


def test_a_thread_that_returns_after_stop_is_not_a_failure(caplog):
    threads = BackgroundThreads([("a", _loop)])
    threads.start()

    threads.stop()

    assert "stopped on its own" not in caplog.text
    assert "died" not in caplog.text
