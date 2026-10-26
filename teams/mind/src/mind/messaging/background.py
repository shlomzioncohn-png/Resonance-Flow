"""BackgroundThreads - run long-lived loops (Kafka consumers, workers) as
threads inside a process that does something else, such as MIND's API.

Each target is a function that takes a stop event and a `progress` callback,
and loops until the event is set, calling `progress()` after each piece of
work done (a message handled, a re-score finished). Use it from the API's
lifespan:

    def consume(stop, progress):
        consumer.run(T, lambda event, msg: (handle(event), progress()), stop=stop)

    background = BackgroundThreads([("my-consumer", consume)])
    background.start()
    ...
    background.stop()   # on shutdown

A target that raises is logged and run again after a wait that doubles each
time (1 s, 2 s, 4 s ... up to 60 s, the same as the task.created listener in
task_creation/listener.py). It never takes the process down, unlike a
standalone runner's `os._exit(1)`, which in the API would stop serving HTTP
too. A Kafka consumer target builds a new consumer on every run, so the
message it failed on, still uncommitted, is read again: the same recovery a
pod restart would give, without one. The way Chain's consumers work too.

`healthy` is False only if a thread has died, which the retries above should
never let happen. A Kafka outage, including one at startup, stays healthy and
is retried inside the thread, so a liveness probe on it never restarts the API
for a broker that is merely down. How it reaches the probes is up to the API
(/health, #481); recovery does not depend on it.

A thread that keeps failing would then go unnoticed, so `retrying` names the
threads that failed and have not recovered since, and once the wait reaches
60 s every retry is logged at ERROR (a single failure is a WARNING): a bad
message stuck at the head of a topic, or a wrong setting, never stops scores
silently. A thread recovers by making progress, not by merely running a while:
a send to a broker that is down blocks for up to 5 minutes before it fails.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable

logger = logging.getLogger(__name__)

#: How long stop() waits for all the threads together. Every loop checks its
#: stop event about once a second, so this is ample.
_JOIN_SECONDS = 5.0

#: The wait before the first retry; it doubles on each failure after that.
_FIRST_RESTART_WAIT = 1.0

#: The longest wait between two runs of a failing target, matching
#: task_creation/listener.py.
_MAX_RESTART_WAIT = 60.0

#: A thread that has run this long since its last failure, with nothing to do
#: (an idle topic) and no new failure, has recovered too. Longer than a Kafka
#: send can block before it fails (librdkafka's message.timeout.ms, 5 min), so
#: a send that fails slowly every time is never taken for a recovery.
_RECOVERED_AFTER = 600.0

Progress = Callable[[], None]
Target = Callable[[threading.Event, Progress], None]


class BackgroundThreads:
    """Named loops run as daemon threads that share one stop event."""

    def __init__(self, targets: list[tuple[str, Target]]) -> None:
        self._stop = threading.Event()
        # For each thread that failed and has not recovered since: when it
        # last failed or was run again.
        self._retrying: dict[str, float] = {}
        self._retrying_lock = threading.Lock()
        # Daemon: if stop() is never reached, the threads still end with the process.
        self._threads = [
            threading.Thread(target=self._guard, args=(name, target), name=name, daemon=True)
            for name, target in targets
        ]

    @property
    def names(self) -> list[str]:
        return [thread.name for thread in self._threads]

    def start(self) -> None:
        for thread in self._threads:
            thread.start()

    def stop(self) -> None:
        """Ask every loop to end, and wait a few seconds for all of them together."""
        self._stop.set()
        deadline = time.monotonic() + _JOIN_SECONDS
        for thread in self._threads:
            thread.join(timeout=max(0.0, deadline - time.monotonic()))
            if thread.is_alive():
                logger.warning("background: thread %s did not stop in time", thread.name)

    @property
    def healthy(self) -> bool:
        """True while every thread is alive, retrying included; False once one has died."""
        return all(thread.is_alive() for thread in self._threads)

    @property
    def retrying(self) -> list[str]:
        """Threads that failed and have not recovered since: no progress yet, and
        not _RECOVERED_AFTER of running without a new failure."""
        now = time.monotonic()
        with self._retrying_lock:
            since = dict(self._retrying)
        return [
            name for name in self.names if name in since and now - since[name] <= _RECOVERED_AFTER
        ]

    def _guard(self, name: str, target: Target) -> None:
        failures = 0

        def progress() -> None:
            nonlocal failures
            if failures:
                failures = 0
                with self._retrying_lock:
                    self._retrying.pop(name, None)

        while not self._stop.is_set():
            run_began = time.monotonic()
            if failures:
                self._mark_retrying(name, run_began)
            try:
                target(self._stop, progress)
            except Exception:
                if self._stop.is_set():
                    return  # failed while shutting down: not run again
                logger.warning("background: thread %s failed", name, exc_info=True)
            else:
                if self._stop.is_set():
                    return
                # Every target loops until stopped; returning early is a failure too.
                logger.warning("background: thread %s stopped on its own", name)

            if failures and time.monotonic() - run_began > _RECOVERED_AFTER:
                failures = 0
            wait = min(_FIRST_RESTART_WAIT * 2.0**failures, _MAX_RESTART_WAIT)
            failures += 1
            self._mark_retrying(name, time.monotonic())
            if wait >= _MAX_RESTART_WAIT:
                logger.error(
                    "background: thread %s keeps failing (%d times in a row); "
                    "running it again every %ss",
                    name,
                    failures,
                    wait,
                )
            else:
                logger.info("background: running thread %s again in %ss", name, wait)
            # Wakes at once on stop(), so a shutdown never waits out the backoff.
            self._stop.wait(wait)

    def _mark_retrying(self, name: str, at: float) -> None:
        with self._retrying_lock:
            self._retrying[name] = at
