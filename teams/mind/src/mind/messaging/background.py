"""BackgroundThreads - run long-lived loops (Kafka consumers, workers) as
threads inside a process that does something else, such as MIND's API.

Each target is a function that takes a stop event and loops until it is set:
`ProtobufConsumer.run(..., stop=stop)` and `ScorePublisher.run_rescores(stop)`
both fit. Use it from the API's lifespan:

    background = BackgroundThreads([("my-consumer", lambda stop: consumer.run(T, handle, stop=stop))])
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

`healthy` is False while any thread waits to be run again. How it reaches the
probes is up to the API (/health, #481); recovery does not depend on it.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable

logger = logging.getLogger(__name__)

#: How long stop() waits for each thread. Every loop checks its stop event
#: about once a second, so this is ample.
_JOIN_SECONDS = 5.0

#: The wait before the first retry; it doubles on each failure after that.
_FIRST_RESTART_WAIT = 1.0

#: The longest wait between two runs of a failing target, matching
#: task_creation/listener.py. A run that lasted longer than this was a
#: recovery, so the next failure starts the waits again from the first.
_MAX_RESTART_WAIT = 60.0

Target = Callable[[threading.Event], None]


class BackgroundThreads:
    """Named loops run as daemon threads that share one stop event."""

    def __init__(self, targets: list[tuple[str, Target]]) -> None:
        self._stop = threading.Event()
        # Names of the threads waiting to run their target again.
        self._failing: set[str] = set()
        self._failing_lock = threading.Lock()
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
        """Ask every loop to end, and wait for each a few seconds."""
        self._stop.set()
        for thread in self._threads:
            thread.join(timeout=_JOIN_SECONDS)
            if thread.is_alive():
                logger.warning("background: thread %s did not stop in time", thread.name)

    @property
    def healthy(self) -> bool:
        """True while every thread runs its target; False while one waits to retry."""
        with self._failing_lock:
            failing = bool(self._failing)
        return not failing and all(thread.is_alive() for thread in self._threads)

    def _guard(self, name: str, target: Target) -> None:
        failures = 0
        while not self._stop.is_set():
            run_began = time.monotonic()
            try:
                target(self._stop)
            except Exception:
                logger.exception("background: thread %s failed", name)
            else:
                if self._stop.is_set():
                    return
                # Every target loops until stopped; returning early is a failure too.
                logger.error("background: thread %s stopped on its own", name)

            if time.monotonic() - run_began > _MAX_RESTART_WAIT:
                failures = 0
            wait = min(_FIRST_RESTART_WAIT * 2.0**failures, _MAX_RESTART_WAIT)
            failures += 1
            with self._failing_lock:
                self._failing.add(name)
            logger.info("background: running thread %s again in %ss", name, wait)
            # Wakes at once on stop(), so a shutdown never waits out the backoff.
            self._stop.wait(wait)
            with self._failing_lock:
                self._failing.discard(name)
