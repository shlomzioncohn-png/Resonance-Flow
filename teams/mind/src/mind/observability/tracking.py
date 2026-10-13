"""Single tracking point for every AI model call MIND makes.

Every model call -- OCR, ASR, LLM, local or otherwise -- goes through
record_call() so latency and cost are captured the same way everywhere.
This module intentionally has no opinion on model size or where the model
runs; it just records what happened. That keeps it usable for the
benchmarking work described in the team README (comparing local model
sizes on speed vs. accuracy) as well as for production call sites.

Not the same thing as `mind.api.instrumentation.instrumented_call`: that
times HTTP-level work (e.g. /health). Model calls are recorded here, not
there, so one ledger holds all of them (#479).
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from enum import StrEnum

logger = logging.getLogger("mind.observability")

_MAX_IN_MEMORY_RECORDS = 10_000
_lock = threading.Lock()


class CallKind(StrEnum):
    """Which stage of the pipeline made the call -- what a summary groups by."""

    OCR = "ocr"
    ASR = "asr"
    LLM = "llm"


class CallStatus(StrEnum):
    """Whether the call itself failed (raised), not whether its output was useful."""

    SUCCESS = "success"
    ERROR = "error"


@dataclass(frozen=True)
class ModelCallRecord:
    """One completed call to a model.

    Every field after `cost` is optional, so a caller that does not have a
    number passes nothing rather than a made-up one.
    """

    model_name: str
    duration_ms: float
    # USD. None means *unpriced* (an unknown model, a provider that reported no
    # usage, local compute with no rate yet) -- not free. A cost report has to
    # be able to tell those two apart.
    cost: float | None
    kind: CallKind | None = None
    status: CallStatus = CallStatus.SUCCESS
    # CPU time the call used, in seconds -- the compute-cost signal for local
    # models (OCR, ASR). None when it was not measured.
    cpu_s: float | None = None
    # As the provider reported them; None when it did not.
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    timestamp: float = field(default_factory=time.time)


_records: deque[ModelCallRecord] = deque(maxlen=_MAX_IN_MEMORY_RECORDS)


def record_call(
    model_name: str,
    duration_ms: float,
    cost: float | None,
    *,
    kind: CallKind | None = None,
    status: CallStatus = CallStatus.SUCCESS,
    cpu_s: float | None = None,
    prompt_tokens: int | None = None,
    completion_tokens: int | None = None,
) -> ModelCallRecord:
    """Record one model call.

    Args:
        model_name: Identifier for the model that was called, e.g.
            "whisper-base.en-int8-local" or "deepseek-chat". Include
            enough detail (size/quantization) to tell benchmark variants
            apart -- this is what the benchmarking work in benchmarks/
            will read.
        duration_ms: Wall-clock time the call took, in milliseconds.
        cost: Cost of the call in USD, or None if it cannot be priced.
            Pass None rather than 0.0 for "unknown" -- and record the call
            either way: every model call must be recorded.
        kind: OCR, ASR or LLM.
        status: ERROR if the call raised, so failed calls -- often the
            slowest ones -- stay in the latency figures.
        cpu_s: CPU seconds the call used, if measured.
        prompt_tokens, completion_tokens: Token usage, for LLM calls whose
            provider reports it.

    Returns:
        The stored record, for callers that want to log or assert on it
        (e.g. in tests).

    Storage: this currently keeps the last 10k calls in memory and logs
    each one at INFO level. When a metrics backend is chosen (see the
    team README's "Open decisions"), swap the storage here -- call sites
    do not change.
    """
    record = ModelCallRecord(
        model_name=model_name,
        duration_ms=duration_ms,
        cost=cost,
        kind=kind,
        status=status,
        cpu_s=cpu_s,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
    )
    with _lock:
        _records.append(record)
    logger.info(
        "model_call model_name=%s kind=%s status=%s duration_ms=%.1f cpu_s=%s "
        "prompt_tokens=%s completion_tokens=%s cost=%s",
        record.model_name,
        record.kind or "-",
        record.status,
        record.duration_ms,
        "-" if record.cpu_s is None else f"{record.cpu_s:.3f}",
        "-" if record.prompt_tokens is None else record.prompt_tokens,
        "-" if record.completion_tokens is None else record.completion_tokens,
        "unpriced" if record.cost is None else f"{record.cost:.6f}",
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
