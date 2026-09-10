"""Single tracking point for every AI model call MIND makes.

Every future model call -- local or otherwise -- must go through
record_call() so latency and cost are captured the same way everywhere.
This module intentionally has no opinion on model size or where the model
runs; it just records what happened. That keeps it usable for the
benchmarking work described in the team README (comparing local model
sizes on speed vs. accuracy) as well as for production call sites later.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque
from dataclasses import dataclass, field

logger = logging.getLogger("mind.observability")

_MAX_IN_MEMORY_RECORDS = 10_000
_lock = threading.Lock()


@dataclass(frozen=True)
class ModelCallRecord:
    """One completed call to a model."""

    model_name: str
    duration_ms: float
    cost: float
    timestamp: float = field(default_factory=time.time)


_records: deque[ModelCallRecord] = deque(maxlen=_MAX_IN_MEMORY_RECORDS)


def record_call(model_name: str, duration_ms: float, cost: float) -> ModelCallRecord:
    """Record one model call.

    Args:
        model_name: Identifier for the model that was called, e.g.
            "whisper-base-local" or "llama-3.1-8b-instruct-local". Include
            enough detail (size/quantization) to tell benchmark variants
            apart -- this is what the benchmarking work in benchmarks/
            will read.
        duration_ms: Wall-clock time the call took, in milliseconds.
        cost: Cost of the call in USD. Local models have a real cost too
            (compute time); until MIND has a cost model for that, pass
            0.0 rather than skipping the call -- every model call must be
            recorded.

    Returns:
        The stored record, for callers that want to log or assert on it
        (e.g. in tests).

    Storage: this currently keeps the last 10k calls in memory and logs
    each one at INFO level. When a metrics backend is chosen (see the
    team README's "Open decisions"), swap the storage here -- call sites
    do not change.
    """
    record = ModelCallRecord(model_name=model_name, duration_ms=duration_ms, cost=cost)
    with _lock:
        _records.append(record)
    logger.info(
        "model_call model_name=%s duration_ms=%.1f cost=%.6f",
        record.model_name,
        record.duration_ms,
        record.cost,
    )
    return record


def get_recent_calls(limit: int | None = None) -> list[ModelCallRecord]:
    """Return recorded calls, most recent last.

    For tests and local debugging only -- not a substitute for a real
    metrics backend once one is chosen.
    """
    with _lock:
        records = list(_records)
    return records[-limit:] if limit else records


def _reset_for_tests() -> None:
    """Clear every recorded call.

    Test-only: _records is a module-level global, so without resetting
    it between tests, one test's calls leak into the next -- see
    tests/test_observability.py's autouse fixture, which calls this
    before every test.
    """
    with _lock:
        _records.clear()
