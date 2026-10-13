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
from collections.abc import Sequence
from dataclasses import dataclass, field
from enum import StrEnum
from functools import lru_cache

from pydantic import BaseModel

from mind.config import MonitoringSettings

logger = logging.getLogger("mind.observability")

_MAX_IN_MEMORY_RECORDS = 10_000
_MAX_DURATION_SAMPLES = 10_000  # per call name, for p50/p95
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


@lru_cache(maxsize=1)
def monitoring_settings() -> MonitoringSettings:
    """Limits and summary interval, read once per process (a change needs a restart)."""
    return MonitoringSettings()


def _limits(kind: CallKind | None) -> tuple[float | None, float | None]:
    """(duration limit in ms, cost limit in USD) for a kind; None means no check.

    A call recorded without a kind has no limits: there is nothing to say what
    "expected" is for it.
    """
    settings = monitoring_settings()
    match kind:
        case CallKind.OCR:
            return settings.ocr_max_duration_ms, settings.ocr_max_cost_usd
        case CallKind.ASR:
            return settings.asr_max_duration_ms, settings.asr_max_cost_usd
        case CallKind.LLM:
            return settings.llm_max_duration_ms, settings.llm_max_cost_usd
    return None, None


@dataclass(frozen=True)
class LimitCheck:
    """One call measured against its kind's limits.

    Worked out once per call, in record_call(), and the only thing both the
    per-call WARNING lines and the summary's slow/expensive counters read --
    so a warning and a count can never disagree.
    """

    duration_limit_ms: float | None
    cost_limit_usd: float | None
    slow: bool
    expensive: bool


def check_limits(record: ModelCallRecord) -> LimitCheck:
    """Whether `record` is over its kind's duration and cost limits.

    Strictly over: a call exactly at a limit is not flagged. An unpriced call
    (cost None) is never expensive -- its cost is unknown, not zero and not
    high -- and shows up in the summary's unpriced_count instead.
    """
    duration_limit_ms, cost_limit_usd = _limits(record.kind)
    return LimitCheck(
        duration_limit_ms=duration_limit_ms,
        cost_limit_usd=cost_limit_usd,
        slow=duration_limit_ms is not None and record.duration_ms > duration_limit_ms,
        expensive=(
            cost_limit_usd is not None and record.cost is not None and record.cost > cost_limit_usd
        ),
    )


def _times_over(value: float, limit: float) -> str:
    """How far over its limit a value is, e.g. "2.4x" -- "-" against a limit of 0."""
    return f"{value / limit:.1f}x" if limit > 0 else "-"


def _warn_if_over(record: ModelCallRecord, check: LimitCheck) -> None:
    """One WARNING line per limit crossed, with a fixed MODEL_CALL_* marker to grep for."""
    if check.slow and check.duration_limit_ms is not None:
        logger.warning(
            "MODEL_CALL_SLOW model_name=%s kind=%s status=%s duration_ms=%.1f limit_ms=%.1f "
            "over=%s",
            record.model_name,
            record.kind or "-",
            record.status,
            record.duration_ms,
            check.duration_limit_ms,
            _times_over(record.duration_ms, check.duration_limit_ms),
        )
    if check.expensive and check.cost_limit_usd is not None and record.cost is not None:
        logger.warning(
            "MODEL_CALL_EXPENSIVE model_name=%s kind=%s status=%s cost=%.6f limit_usd=%.6f over=%s",
            record.model_name,
            record.kind or "-",
            record.status,
            record.cost,
            check.cost_limit_usd,
            _times_over(record.cost, check.cost_limit_usd),
        )


def percentile(sorted_values: Sequence[float], pct: float) -> float:
    """Linear-interpolation percentile over an already-sorted sequence (0.0 if empty)."""
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    rank = (pct / 100) * (len(sorted_values) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(sorted_values) - 1)
    if lo == hi:
        return sorted_values[lo]
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (rank - lo)


@dataclass
class _Totals:
    """Running totals for one call name since the process started.

    The counters and sums are never evicted, so a total stays a total however
    many calls happen; only the duration sample behind p50/p95 is bounded --
    the same split as api/instrumentation.py's _Aggregator, for the same reason.
    A value a call did not have (CPU not measured, usage not reported, cost
    unpriced) is left out of its sum and counted as missing, never added as 0.
    """

    kind: CallKind | None = None
    durations: deque[float] = field(default_factory=lambda: deque(maxlen=_MAX_DURATION_SAMPLES))
    call_count: int = 0
    error_count: int = 0
    cpu_s: float = 0.0
    cpu_measured: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    tokens_reported: int = 0
    cost: float = 0.0
    priced: int = 0
    slow: int = 0
    expensive: int = 0

    def add(self, record: ModelCallRecord, check: LimitCheck) -> None:
        if record.kind is not None:
            self.kind = record.kind
        self.durations.append(record.duration_ms)
        self.call_count += 1
        if record.status == CallStatus.ERROR:
            self.error_count += 1
        if record.cpu_s is not None:
            self.cpu_s += record.cpu_s
            self.cpu_measured += 1
        # Usage counts as reported only when both halves are: providers report
        # both or neither, and half a pair would skew the ratio between them.
        if record.prompt_tokens is not None and record.completion_tokens is not None:
            self.prompt_tokens += record.prompt_tokens
            self.completion_tokens += record.completion_tokens
            self.tokens_reported += 1
        if record.cost is not None:
            self.cost += record.cost
            self.priced += 1
        # The same LimitCheck the WARNING lines read (see check_limits).
        if check.slow:
            self.slow += 1
        if check.expensive:
            self.expensive += 1


_totals: dict[str, _Totals] = {}
_total_calls = 0


class CallNameSummary(BaseModel):
    """Every call recorded under one name since the process started.

    A total over values some calls did not have is the total of the ones they
    did; the matching *_count says how many calls it leaves out, and the total
    is None when it leaves out all of them -- unknown, never 0. So
    cost_total_usd with unpriced_count > 0 is a lower bound.
    """

    call_name: str
    kind: CallKind | None
    call_count: int
    error_count: int
    p50_duration_ms: float
    p95_duration_ms: float
    cpu_s_total: float | None
    cpu_unmeasured_count: int
    prompt_tokens_total: int | None
    completion_tokens_total: int | None
    tokens_unreported_count: int
    cost_total_usd: float | None
    unpriced_count: int
    # The limits in force for this kind (None: no check), and how many calls crossed them.
    duration_limit_ms: float | None
    slow_count: int
    cost_limit_usd: float | None
    expensive_count: int


class ModelCallSummary(BaseModel):
    """The body of GET /debug/model-calls, and what log_summary() writes."""

    total_calls: int
    by_call_name: list[CallNameSummary]


def _summarize(name: str, totals: _Totals) -> CallNameSummary:
    durations = sorted(totals.durations)
    duration_limit_ms, cost_limit_usd = _limits(totals.kind)
    return CallNameSummary(
        call_name=name,
        kind=totals.kind,
        call_count=totals.call_count,
        error_count=totals.error_count,
        p50_duration_ms=round(percentile(durations, 50), 3),
        p95_duration_ms=round(percentile(durations, 95), 3),
        cpu_s_total=round(totals.cpu_s, 3) if totals.cpu_measured else None,
        cpu_unmeasured_count=totals.call_count - totals.cpu_measured,
        prompt_tokens_total=totals.prompt_tokens if totals.tokens_reported else None,
        completion_tokens_total=totals.completion_tokens if totals.tokens_reported else None,
        tokens_unreported_count=totals.call_count - totals.tokens_reported,
        cost_total_usd=totals.cost if totals.priced else None,
        unpriced_count=totals.call_count - totals.priced,
        duration_limit_ms=duration_limit_ms,
        slow_count=totals.slow,
        cost_limit_usd=cost_limit_usd,
        expensive_count=totals.expensive,
    )


def model_call_summary() -> ModelCallSummary:
    """Aggregate over every model call this process has recorded, per call name.

    In-memory and per-process: it resets on restart, and with several worker
    processes each one only sees its own calls (see team README "Open
    decisions" -- the week-8 report is where a shared store would come in).
    """
    with _lock:
        rows = [_summarize(name, totals) for name, totals in sorted(_totals.items())]
        total_calls = _total_calls
    return ModelCallSummary(total_calls=total_calls, by_call_name=rows)


def _or(value: float | None, missing: str, spec: str = "") -> str:
    return missing if value is None else format(value, spec)


def log_summary() -> ModelCallSummary:
    """Log the summary, one line per call name, and return it.

    Logged by itself every `summary_every_n_calls` calls (MonitoringSettings);
    call it directly at the end of a run that has no HTTP server to ask -- a
    batch extraction, a benchmark.
    """
    summary = model_call_summary()
    for row in summary.by_call_name:
        logger.info(
            "model_call_summary call_name=%s kind=%s calls=%d errors=%d p50_ms=%.1f "
            "p95_ms=%.1f cpu_s=%s cpu_unmeasured=%d prompt_tokens=%s completion_tokens=%s "
            "tokens_unreported=%d cost=%s unpriced=%d slow=%d expensive=%d",
            row.call_name,
            row.kind or "-",
            row.call_count,
            row.error_count,
            row.p50_duration_ms,
            row.p95_duration_ms,
            _or(row.cpu_s_total, "unmeasured", ".3f"),
            row.cpu_unmeasured_count,
            _or(row.prompt_tokens_total, "unreported"),
            _or(row.completion_tokens_total, "unreported"),
            row.tokens_unreported_count,
            _or(row.cost_total_usd, "unpriced", ".6f"),
            row.unpriced_count,
            row.slow_count,
            row.expensive_count,
        )
    return summary


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

    Storage: this currently keeps the last 10k calls in memory, folds each
    into per-call-name running totals (model_call_summary()), and logs it at
    INFO level -- plus a WARNING line for each limit it crosses (see
    check_limits) and the whole summary every `summary_every_n_calls`
    calls. When a metrics backend is chosen (see the team README's "Open
    decisions"), swap the storage here -- call sites do not change.
    """
    global _total_calls
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
    check = check_limits(record)
    with _lock:
        _records.append(record)
        _totals.setdefault(record.model_name, _Totals()).add(record, check)
        _total_calls += 1
        every_n = monitoring_settings().summary_every_n_calls
        summary_due = every_n > 0 and _total_calls % every_n == 0
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
    _warn_if_over(record, check)
    if summary_due:
        log_summary()
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
    """Clear every recorded call and the running totals.

    Test-only: _records is a module-level global, so without resetting
    it between tests, one test's calls leak into the next -- see
    tests/test_observability.py's autouse fixture, which calls this
    before every test.
    """
    global _total_calls
    with _lock:
        _records.clear()
        _totals.clear()
        _total_calls = 0
