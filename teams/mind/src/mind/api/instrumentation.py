"""Generic call-timing + cost instrumentation for MIND's API layer.

`instrumented_call(name)` wraps any function or block of code (it works
both as a decorator and as a context manager) and measures wall-clock
duration around it. On exit it writes one structured JSON log line
(`call_name`, `duration_ms`, `cost`, `timestamp`, `status`) and folds
the same numbers into an in-memory aggregate that GET
/debug/instrumentation exposes.

This is deliberately generic: it does not know or care whether it is
wrapping a health check, the /debug/simulate-call stand-in, or (starting
week 4) a real OCR/ASR/LLM/embedding/ranking call. Every future model
call should wrap itself in this instead of inventing its own
timing/logging -- see api/README.md "Instrumentation" for the pattern.

Not the same thing as `mind.observability.tracking.record_call`: that
is a manual ledger a caller feeds pre-computed numbers into (kept for
the benchmarking work described in the team README). This module is
the automatic, higher-level wrapper -- new call sites should prefer it.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from collections import deque
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Iterator

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


def _percentile(sorted_values: list[float], pct: float) -> float:
    """Linear-interpolation percentile over an already-sorted list."""
    if len(sorted_values) == 1:
        return sorted_values[0]
    rank = (pct / 100) * (len(sorted_values) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(sorted_values) - 1)
    if lo == hi:
        return sorted_values[lo]
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (rank - lo)


class _Aggregator:
    """Cumulative in-memory stats over recorded measurements.

    Minimal stand-in for the real monitoring Relay will build in week
    8 -- not a persistent metrics store (see team README "Open
    decisions"); this resets when the process restarts.
    """

    def __init__(self, maxlen: int = _MAX_IN_MEMORY_MEASUREMENTS) -> None:
        self._lock = threading.Lock()
        self._measurements: deque[Measurement] = deque(maxlen=maxlen)

    def record(self, measurement: Measurement) -> None:
        with self._lock:
            self._measurements.append(measurement)

    def summary(self) -> dict[str, Any]:
        with self._lock:
            measurements = list(self._measurements)

        count = len(measurements)
        if count == 0:
            return {
                "call_count": 0,
                "error_count": 0,
                "p95_duration_ms": None,
                "cumulative_cost": 0.0,
            }

        durations = sorted(m.duration_ms for m in measurements)
        error_count = sum(1 for m in measurements if m.status == "error")
        cumulative_cost = sum(m.cost for m in measurements)

        return {
            "call_count": count,
            "error_count": error_count,
            "p95_duration_ms": round(_percentile(durations, 95), 3),
            "cumulative_cost": round(cumulative_cost, 6),
        }


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
    failure.

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
    except Exception:
        status = "error"
        raise
    finally:
        duration_ms = (time.perf_counter() - start) * 1000
        measurement = Measurement(
            call_name=name, duration_ms=duration_ms, cost=cost, status=status
        )
        _aggregator.record(measurement)
        logger.info(json.dumps(measurement.as_log_dict()))


def instrumentation_summary() -> dict[str, Any]:
    """Current aggregate stats -- what GET /debug/instrumentation returns."""
    return _aggregator.summary()
