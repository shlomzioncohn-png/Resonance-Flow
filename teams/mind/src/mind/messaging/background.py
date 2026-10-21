"""BackgroundThreads - run long-lived loops (Kafka consumers, workers) as
threads inside a process that does something else, such as MIND's API.

Each target is a function that takes a stop event and loops until it is set:
`ProtobufConsumer.run(..., stop=stop)` and `ScorePublisher.run_rescores(stop)`
both fit. Use it from the API's lifespan:

    background = BackgroundThreads([("my-consumer", lambda stop: consumer.run(T, handle, stop=stop))])
    background.start()
    ...
    background.stop()   # on shutdown

A thread that dies is logged and marks the whole set unhealthy; it does not
take the process down, unlike a standalone runner's `os._exit(1)`, which in the
API would stop serving HTTP too. How `healthy` reaches the probes is up to the
API (/health, #481).
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable

logger = logging.getLogger(__name__)

#: How long stop() waits for each thread. Every loop checks its stop event
#: about once a second, so this is ample.
_JOIN_SECONDS = 5.0

Target = Callable[[threading.Event], None]


class BackgroundThreads:
    """Named loops run as daemon threads that share one stop event."""

    def __init__(self, targets: list[tuple[str, Target]]) -> None:
        self._stop = threading.Event()
        self._failed: str | None = None
        # Daemon: if stop() is never reached, the threads still end with the process.
        self._threads = [
            threading.Thread(target=self._guard, args=(name, target), name=name, daemon=True)
            for name, target in targets
        ]

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
        """True while every thread runs; False once one has died or stopped."""
        return self._failed is None and all(thread.is_alive() for thread in self._threads)

    def _guard(self, name: str, target: Target) -> None:
        try:
            target(self._stop)
        except Exception:
            # Not re-raised: in the API that would only end this thread silently.
            logger.exception("background: thread %s died", name)
            self._failed = name
        else:
            if not self._stop.is_set():
                # Every target loops until stopped; returning early is a failure too.
                logger.error("background: thread %s stopped on its own", name)
                self._failed = name
