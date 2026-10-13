"""Generic call-timing + cost instrumentation for MIND's API layer.

`instrumented_call(name)` wraps any function or block of code (it works
both as a decorator and as a context manager) and measures wall-clock
duration around it. On exit it writes one structured JSON log line
(`call_name`, `duration_ms`, `cost`, `timestamp`, `status`) and folds
the same numbers into an in-memory aggregate that GET
/debug/instrumentation exposes.

This is deliberately generic: it times HTTP-level work such as the
health check and the /debug/simulate-call stand-in -- see api/README.md
"Instrumentation" for the pattern.

Model calls (OCR, ASR, LLM) do *not* go through this: they are recorded
with `mind.observability.tracking.record_call`, which also carries what a
model call has and an HTTP handler does not -- kind, CPU time, token
usage, and an unpriced cost kept distinct from a free one (#479).
"""

from __future__ import annotations

import json
import logging
import threading
import time
from collections import deque
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel

from mind.observability.tracking import percentile

logger = logging.getLogger("mind.instrumentation")
# uvicorn only attaches handlers to its own loggers, and the root
# logger has no handler by default -- without this, every INFO record
# below is silently dropped instead of reaching stderr. Scoped to this
# logger only (propagate=False) so it neither depends on nor disturbs
# whatever logging setup the rest of the app ends up using.
if not logger.handlers:
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(_handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False

_MAX_IN_MEMORY_MEASUREMENTS = 10_000


@dataclass(frozen=True)
class Measurement:
    """One completed (or failed) instrumented call."""

    call_name: str
    duration_ms: float
    cost: float
    status: str  # "success" | "error"
    timestamp: float = field(default_factory=time.time)

    def as_log_dict(self) -> dict[str, Any]:
        return {
            "call_name": self.call_name,
            "duration_ms": round(self.duration_ms, 3),
            "cost": self.cost,
            "timestamp": self.timestamp,
            "status": self.status,
        }


class InstrumentationSummary(BaseModel):
    """The body of GET /debug/instrumentation -- current aggregate stats."""

    call_count: int
    error_count: int
    p95_duration_ms: float | None
    cumulative_cost: float


class _Aggregator:
    """Cumulative in-memory stats over recorded measurements.

    call_count, error_count, and cumulative_cost are running totals
    since the process started -- plain counters, incremented in
    record() and never evicted, so they stay accurate no matter how
    many calls happen. Only the duration samples used for p95 are kept
    in a bounded deque: percentile needs *a* sample, not literally
    every duration ever recorded, so bounding that one is fine -- but
    bounding the totals themselves would silently make them "totals
    over roughly the last N calls" instead, which is a very different
    (and wrong) thing for a number meant to eventually track a real
    cost budget.

    Minimal stand-in for the real monitoring Relay will build in week
    8 -- not a persistent metrics store (see team README "Open
    decisions"); this resets when the process restarts.
    """

    def __init__(self, maxlen: int = _MAX_IN_MEMORY_MEASUREMENTS) -> None:
        self._lock = threading.Lock()
        self._durations: deque[float] = deque(maxlen=maxlen)
        self._call_count = 0
        self._error_count = 0
        self._cumulative_cost = 0.0

    def record(self, measurement: Measurement) -> None:
        with self._lock:
            self._durations.append(measurement.duration_ms)
            self._call_count += 1
            if measurement.status == "error":
                self._error_count += 1
            self._cumulative_cost += measurement.cost

    def summary(self) -> InstrumentationSummary:
        with self._lock:
            durations = sorted(self._durations)
            call_count = self._call_count
            error_count = self._error_count
            cumulative_cost = self._cumulative_cost

        if call_count == 0:
            return InstrumentationSummary(
                call_count=0, error_count=0, p95_duration_ms=None, cumulative_cost=0.0
            )

        return InstrumentationSummary(
            call_count=call_count,
            error_count=error_count,
            p95_duration_ms=round(percentile(durations, 95), 3),
            cumulative_cost=round(cumulative_cost, 6),
        )


_aggregator = _Aggregator()


@contextmanager
def instrumented_call(name: str, cost: float = 0.0) -> Iterator[None]:
    """Time a block of code and record + log the result.

    As a context manager (works in both sync and async functions):

        with instrumented_call("whisper_transcribe", cost=0.0012):
            result = call_whisper_model(audio)

    As a decorator (synchronous call sites only -- see note below):

        @instrumented_call("some_sync_call")
        def do_work(): ...

    Args:
        name: Stable identifier for this call site, e.g.
            "whisper-base-local" or "health_check". Becomes `call_name`
            in the structured log line and the aggregate.
        cost: Cost of this call. No real pricing model exists yet (see
            team README "Open decisions") -- pass a placeholder (0.0,
            or tokens * a stand-in price) until one does. Every call is
            still recorded regardless, so this defaults to 0.0 rather
            than being required.

    An exception raised inside the wrapped block is still recorded
    (status="error") and always re-raised -- this never swallows a
    failure. Catches BaseException, not just Exception: a client
    disconnect mid-request raises asyncio.CancelledError, which
    inherits from BaseException (not Exception, since Python 3.8) --
    `except Exception` alone would miss it and record a cancelled call
    as "success".

    Note on async: decorating an `async def` directly does not time it
    correctly (the decorator would return the coroutine without
    awaiting it). For async call sites -- e.g. FastAPI route handlers --
    use the `with` form inside the function body instead, as /health
    and /debug/simulate-call do.
    """
    start = time.perf_counter()
    status = "success"
    try:
        yield
    except BaseException:
        status = "error"
        raise
    finally:
        duration_ms = (time.perf_counter() - start) * 1000
        measurement = Measurement(call_name=name, duration_ms=duration_ms, cost=cost, status=status)
        _aggregator.record(measurement)
        logger.info(json.dumps(measurement.as_log_dict()))


def instrumentation_summary() -> InstrumentationSummary:
    """Current aggregate stats -- what GET /debug/instrumentation returns."""
    return _aggregator.summary()
