"""Single tracking point for every AI model call MIND makes.

Every model call -- OCR, ASR, LLM, local or otherwise -- goes through
record_call() so latency and cost are captured the same way everywhere.
This module intentionally has no opinion on model size or where the model
runs; it just records what happened. That keeps it usable for the
benchmarking work described in the team README (comparing local model
sizes on speed vs. accuracy) as well as for production call sites.

Cost has a different unit per kind (#479): the local models (OCR, ASR) cost
CPU seconds -- MIND does not pay for its machine by the hour, so they have no
dollar price -- and only LLM calls cost US dollars, at the provider's price
per token. Every field and log key carries its unit, and a measure that does
not apply to a kind is reported as not applicable, never as unknown or 0.

Not the same thing as `mind.api.instrumentation.instrumented_call`: that
times HTTP-level work (e.g. /health). Model calls are recorded here, not
there, so one ledger holds all of them.
"""

from __future__ import annotations

import dataclasses
import logging
import threading
import time
from collections import deque
from collections.abc import Sequence
from dataclasses import dataclass, field
from enum import StrEnum
from functools import lru_cache

from pydantic import BaseModel, ValidationError

from mind.config import MonitoringSettings

logger = logging.getLogger("mind.observability")

_MAX_IN_MEMORY_RECORDS = 10_000
_MAX_DURATION_SAMPLES = 10_000  # per call name, for p50/p95
_lock = threading.Lock()

_NOT_APPLICABLE = "n/a"


class CallKind(StrEnum):
    """Which stage of the pipeline made the call -- what a summary groups by."""

    OCR = "ocr"
    ASR = "asr"
    LLM = "llm"


class CallStatus(StrEnum):
    """Whether the call itself failed (raised), not whether its output was useful."""

    SUCCESS = "success"
    ERROR = "error"


def _cpu_applies(kind: CallKind | None) -> bool:
    """CPU seconds are the compute cost of the local models: OCR and ASR.

    A call recorded without a kind gets every measure -- nothing says which
    of them it should have.
    """
    return kind is None or kind in (CallKind.OCR, CallKind.ASR)


def _llm_measures_apply(kind: CallKind | None) -> bool:
    """Token usage and a US-dollar cost apply to LLM calls only."""
    return kind is None or kind == CallKind.LLM


@dataclass(frozen=True)
class ModelCallRecord:
    """One completed call to a model.

    Every field after `cost` is optional, so a caller that does not have a
    number passes nothing rather than a made-up one.
    """

    model_name: str
    duration_ms: float
    # US dollars, for LLM calls (the provider's price per token). None means
    # unpriced -- an unknown model, or a provider that reported no usage --
    # not free. OCR/ASR calls pass None: their cost is cpu_s, not dollars.
    cost: float | None
    kind: CallKind | None = None
    status: CallStatus = CallStatus.SUCCESS
    # CPU seconds the call used -- the compute cost of the local models (OCR,
    # ASR). None when it was not measured.
    cpu_s: float | None = None
    # As the provider reported them; None when it did not.
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    timestamp: float = field(default_factory=time.time)


_records: deque[ModelCallRecord] = deque(maxlen=_MAX_IN_MEMORY_RECORDS)


@lru_cache(maxsize=1)
def monitoring_settings() -> MonitoringSettings:
    """Limits and summary interval, read once per process (a change needs a restart).

    An invalid MIND_MONITORING__* value falls back to every default and logs
    one ERROR -- it never raises. record_call() reads this on every model
    call, so raising here would turn a typo in a monitoring setting into a
    failure of every OCR, ASR and LLM call. The API loads it at start-up
    (api/main.py) so the error shows up then, not at the first model call.
    """
    try:
        return MonitoringSettings()
    except ValidationError as exc:
        logger.error(
            "invalid MIND_MONITORING__* settings, using the defaults for all of them: %s", exc
        )
        return MonitoringSettings.model_construct()


def _limits(kind: CallKind | None) -> tuple[float | None, float | None]:
    """(duration limit in ms, cost limit in USD) for a kind; None means no check.

    Only LLM calls have a cost limit: they are the only ones priced in
    dollars. A call recorded without a kind has no limits: there is nothing
    to say what "expected" is for it.
    """
    settings = monitoring_settings()
    match kind:
        case CallKind.OCR:
            return settings.ocr_max_duration_ms, None
        case CallKind.ASR:
            return settings.asr_max_duration_ms, None
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
            "MODEL_CALL_EXPENSIVE model_name=%s kind=%s status=%s cost_usd=%.6f "
            "limit_usd=%.6f over=%s",
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

    Each measure is tallied only over the calls it applies to (see
    _cpu_applies / _llm_measures_apply): `*_applicable` counts those calls,
    and of them a value the call did not have (CPU not measured, usage not
    reported, cost unpriced) is left out of the sum and counted as missing,
    never added as 0. A value passed for a measure that does not apply to the
    call's kind is not counted at all.
    """

    kind: CallKind | None = None
    durations: deque[float] = field(default_factory=lambda: deque(maxlen=_MAX_DURATION_SAMPLES))
    call_count: int = 0
    error_count: int = 0
    cpu_applicable: int = 0
    cpu_measured: int = 0
    cpu_seconds: float = 0.0
    tokens_applicable: int = 0
    tokens_reported: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    usd_applicable: int = 0
    priced: int = 0
    cost_usd: float = 0.0
    slow: int = 0
    expensive: int = 0

    def add(self, record: ModelCallRecord, check: LimitCheck) -> None:
        if record.kind is not None:
            self.kind = record.kind
        self.durations.append(record.duration_ms)
        self.call_count += 1
        if record.status == CallStatus.ERROR:
            self.error_count += 1
        if _cpu_applies(record.kind):
            self.cpu_applicable += 1
            if record.cpu_s is not None:
                self.cpu_seconds += record.cpu_s
                self.cpu_measured += 1
        if _llm_measures_apply(record.kind):
            self.tokens_applicable += 1
            # Usage counts as reported only when both halves are: providers
            # report both or neither, and half a pair would skew the ratio.
            if record.prompt_tokens is not None and record.completion_tokens is not None:
                self.prompt_tokens += record.prompt_tokens
                self.completion_tokens += record.completion_tokens
                self.tokens_reported += 1
            self.usd_applicable += 1
            if record.cost is not None:
                self.cost_usd += record.cost
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

    Units are in the names: CPU in seconds, cost in US dollars, durations in
    milliseconds. CPU seconds apply to OCR/ASR calls; tokens and dollars to
    LLM calls.

    error_count counts the calls that raised. A failed LLM call reported no
    usage, so it is in unpriced_count too: unpriced_count is every call whose
    dollar cost is unknown -- no price on file, or no usage reported -- and
    error_count says how many of them failed.

    For each measure, its *_count tells the two kinds of null apart:

    * count None -- the measure does not apply to this kind (an OCR call has
      no dollar cost); its total is None too.
    * count a number -- it applies, and that many calls lacked it (CPU not
      measured, usage not reported, model unpriced). The total covers the
      rest, so a cost_usd_total with unpriced_count > 0 is a lower bound, and
      it is None when every call lacked it -- unknown, never 0.
    """

    call_name: str
    kind: CallKind | None
    call_count: int
    error_count: int
    p50_duration_ms: float
    p95_duration_ms: float
    cpu_seconds_total: float | None
    cpu_unmeasured_count: int | None
    prompt_tokens_total: int | None
    completion_tokens_total: int | None
    tokens_unreported_count: int | None
    cost_usd_total: float | None
    unpriced_count: int | None
    # The limits in force for this kind (None: no check), and how many calls crossed them.
    duration_limit_ms: float | None
    slow_count: int
    cost_limit_usd: float | None
    expensive_count: int


class ModelCallSummary(BaseModel):
    """The body of GET /debug/model-calls, and what log_summary() writes."""

    total_calls: int
    by_call_name: list[CallNameSummary]


def _summarize(name: str, totals: _Totals, durations: list[float]) -> CallNameSummary:
    durations.sort()
    duration_limit_ms, cost_limit_usd = _limits(totals.kind)
    cpu_applies = totals.cpu_applicable > 0
    tokens_apply = totals.tokens_applicable > 0
    usd_applies = totals.usd_applicable > 0
    return CallNameSummary(
        call_name=name,
        kind=totals.kind,
        call_count=totals.call_count,
        error_count=totals.error_count,
        p50_duration_ms=round(percentile(durations, 50), 3),
        p95_duration_ms=round(percentile(durations, 95), 3),
        cpu_seconds_total=round(totals.cpu_seconds, 3) if totals.cpu_measured else None,
        cpu_unmeasured_count=(totals.cpu_applicable - totals.cpu_measured if cpu_applies else None),
        prompt_tokens_total=totals.prompt_tokens if totals.tokens_reported else None,
        completion_tokens_total=totals.completion_tokens if totals.tokens_reported else None,
        tokens_unreported_count=(
            totals.tokens_applicable - totals.tokens_reported if tokens_apply else None
        ),
        # Rounded like the per-call log line's precision allows: a float sum of
        # dollar amounts otherwise shows as 0.30000000000000004.
        cost_usd_total=round(totals.cost_usd, 8) if totals.priced else None,
        unpriced_count=totals.usd_applicable - totals.priced if usd_applies else None,
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
    # Copy under the lock and sort outside it: sorting up to 10k samples per
    # name holds the lock (and, from the async /debug endpoint, the event
    # loop) for milliseconds, and record_call() waits on that lock.
    with _lock:
        snapshot = [
            (name, dataclasses.replace(totals), list(totals.durations))
            for name, totals in sorted(_totals.items())
        ]
        total_calls = _total_calls
    rows = [_summarize(name, totals, durations) for name, totals, durations in snapshot]
    return ModelCallSummary(total_calls=total_calls, by_call_name=rows)


def _shown(value: float | None, applies: bool, missing: str, spec: str = "") -> str:
    """A log value: "n/a" if the measure does not apply, `missing` if it is unknown."""
    if not applies:
        return _NOT_APPLICABLE
    return missing if value is None else format(value, spec)


def _count(value: int | None) -> str:
    return _NOT_APPLICABLE if value is None else str(value)


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
            "p95_ms=%.1f cpu_seconds=%s cpu_unmeasured=%s prompt_tokens=%s "
            "completion_tokens=%s tokens_unreported=%s cost_usd=%s unpriced=%s slow=%d "
            "expensive=%d",
            row.call_name,
            row.kind or "-",
            row.call_count,
            row.error_count,
            row.p50_duration_ms,
            row.p95_duration_ms,
            _shown(
                row.cpu_seconds_total, row.cpu_unmeasured_count is not None, "unmeasured", ".3f"
            ),
            _count(row.cpu_unmeasured_count),
            _shown(row.prompt_tokens_total, row.tokens_unreported_count is not None, "unreported"),
            _shown(
                row.completion_tokens_total, row.tokens_unreported_count is not None, "unreported"
            ),
            _count(row.tokens_unreported_count),
            _shown(row.cost_usd_total, row.unpriced_count is not None, "unpriced", ".6f"),
            _count(row.unpriced_count),
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
        cost: Cost of an LLM call in US dollars, or None if it cannot be
            priced -- pass None rather than 0.0 for "unknown". OCR/ASR calls
            pass None: their cost is `cpu_s`. Record the call either way:
            every model call must be recorded.
        kind: OCR, ASR or LLM -- also decides which measures apply (CPU
            seconds for OCR/ASR; tokens and dollars for LLM).
        status: ERROR if the call raised, so failed calls -- often the
            slowest ones -- stay in the latency figures.
        cpu_s: CPU seconds the call used, if measured (OCR/ASR).
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
        every_n = monitoring_settings().summary_every_n_calls  # 0 or None: off
        summary_due = every_n is not None and every_n > 0 and _total_calls % every_n == 0
    cpu_applies = _cpu_applies(record.kind)
    llm_applies = _llm_measures_apply(record.kind)
    logger.info(
        "model_call model_name=%s kind=%s status=%s duration_ms=%.1f cpu_seconds=%s "
        "prompt_tokens=%s completion_tokens=%s cost_usd=%s",
        record.model_name,
        record.kind or "-",
        record.status,
        record.duration_ms,
        _shown(record.cpu_s, cpu_applies, "unmeasured", ".3f"),
        _shown(record.prompt_tokens, llm_applies, "unreported"),
        _shown(record.completion_tokens, llm_applies, "unreported"),
        _shown(record.cost, llm_applies, "unpriced", ".6f"),
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
