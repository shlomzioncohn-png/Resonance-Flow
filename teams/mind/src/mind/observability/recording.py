"""Recording for the stages that are not a model call of their own (#482).

Two adapters that put a stage through record_call() from the outside, so the
code they measure stays as its owner wrote it:

* `RecordingClient` -- an `httpx.Client` that records every request it sends:
  latency, and status ERROR when the request raised (timeout, connection
  refused) or was answered with a non-2xx. It re-raises and returns exactly
  what httpx did, so the code above it -- e.g. `ChainHierarchyClient`, which
  swallows every failure and falls back -- behaves as before. Built with the
  same arguments as a plain `httpx.Client`, so its timeouts, proxies and
  transport are the ones the caller would have had.
* `RecordedScorer` -- wraps anything with `score(task, context)` and records
  each call as one SCORING call: wall time, this thread's CPU, ERROR if it
  raised, and `degraded` if a `RecordingClient` request made from inside it
  failed. A score whose Chain lookup failed is the baseline's own, with no
  ancestors, and its payload looks the same as a root task's: `degraded` is
  what tells the two apart in the ledger.

How a failure inside a score reaches the scorer: each failed request bumps a
counter kept per thread, and the scorer compares it before and after. Both
run in the thread that scores -- httpx's sync client sends in the calling
thread -- so a failure in another publisher thread never marks this thread's
score.

Not a second timing mechanism: both only measure and hand the numbers to
record_call(), the one ledger.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from typing import Any, Protocol

import httpx

from mind.observability.compute import CpuClock
from mind.observability.tracking import CallKind, CallStatus, record_call

_failures = threading.local()


def failed_requests_in_this_thread() -> int:
    """How many RecordingClient requests have failed in this thread so far."""
    return getattr(_failures, "count", 0)


def _count_a_failure() -> None:
    _failures.count = failed_requests_in_this_thread() + 1


def name_by_last_path_segment(prefix: str) -> Callable[[httpx.Request], str]:
    """A call name from the URL's last path segment, e.g. `chain-ancestors`
    for GET /tasks/<id>/ancestors -- one row per endpoint, not per task id."""

    def name(request: httpx.Request) -> str:
        segment = request.url.path.rstrip("/").rsplit("/", 1)[-1]
        return f"{prefix}-{segment}" if segment else prefix

    return name


class RecordingClient(httpx.Client):
    """An httpx.Client that records each request with record_call().

    Args:
        call_name: The call name a request is recorded under.
        kind: The stage, e.g. CallKind.CHAIN.
        **kwargs: Everything an httpx.Client takes, passed on unchanged.

    The duration covers the whole send, body included. A 2xx whose body the
    caller then rejects (not JSON, the wrong shape) is recorded as a success
    here; that caller logs it itself.
    """

    def __init__(
        self, *, call_name: Callable[[httpx.Request], str], kind: CallKind, **kwargs: Any
    ) -> None:
        super().__init__(**kwargs)
        self._call_name = call_name
        self._kind = kind

    def send(self, request: httpx.Request, **kwargs: Any) -> httpx.Response:
        started = time.perf_counter()
        try:
            response = super().send(request, **kwargs)
        except Exception:
            self._record(request, started, CallStatus.ERROR)
            raise
        status = CallStatus.SUCCESS if response.is_success else CallStatus.ERROR
        self._record(request, started, status)
        return response

    def _record(self, request: httpx.Request, started: float, status: CallStatus) -> None:
        if status == CallStatus.ERROR:
            _count_a_failure()
        record_call(
            self._call_name(request),
            (time.perf_counter() - started) * 1000,
            None,
            kind=self._kind,
            status=status,
        )


class _Scorer[ResultT](Protocol):
    def score(self, task: Any, context: Any) -> ResultT: ...


class RecordedScorer[ResultT]:
    """`inner`, with each score() recorded as one SCORING call under `call_name`.

    Its duration includes the Chain calls made inside it, which are also
    recorded on their own rows (kind CHAIN): those rows break a score down,
    they are not added to it.
    """

    def __init__(self, inner: _Scorer[ResultT], call_name: str) -> None:
        self._inner = inner
        self._call_name = call_name

    def score(self, task: Any, context: Any) -> ResultT:
        failures_before = failed_requests_in_this_thread()
        clock = CpuClock(this_thread_only=True)
        started = time.perf_counter()
        status = CallStatus.ERROR
        try:
            result = self._inner.score(task, context)
            status = CallStatus.SUCCESS
            return result
        finally:
            record_call(
                self._call_name,
                (time.perf_counter() - started) * 1000,
                None,
                kind=CallKind.SCORING,
                status=status,
                cpu_s=clock.elapsed_s(),
                degraded=failed_requests_in_this_thread() > failures_before,
            )
