"""/debug endpoints: the instrumented_call stand-in and aggregate, and the
model-call summary (#479). See api/README.md "Instrumentation". Not part
of the versioned /v1 API surface.

# TODO: Remove /debug/simulate-call once a real business endpoint can
# demonstrate instrumented_call instead of this stand-in. /debug/model-calls
# stays until the week-8 cost and latency report replaces it.
"""

from __future__ import annotations

import asyncio
import random
from typing import Literal

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from mind.api.instrumentation import (
    InstrumentationSummary,
    instrumentation_summary,
    instrumented_call,
)
from mind.observability.tracking import ModelCallSummary, model_call_summary

router = APIRouter(prefix="/debug", tags=["debug"])


class SimulateCallResponse(BaseModel):
    """The body of GET /debug/simulate-call."""

    status: Literal["ok"]


_SIMULATED_FAILURE_RATE = 0.05


@router.get("/simulate-call", response_model=SimulateCallResponse)
async def simulate_call() -> SimulateCallResponse:
    """Stand-in for a real model call (OCR/ASR/LLM/...) until week 4.

    Sleeps a short random duration and reports a placeholder cost, so
    instrumented_call has something to wrap end-to-end before any real
    model call exists. Also fails ~5% of the time (a real model call
    can fail) -- this is what actually exercises instrumented_call's
    error path (status="error", error_count) end-to-end over real
    HTTP, not just in a unit test that raises directly.
    """
    cost = round(random.uniform(0.0001, 0.01), 6)
    with instrumented_call("simulate_call", cost=cost):
        await asyncio.sleep(random.uniform(0.01, 0.1))
        if random.random() < _SIMULATED_FAILURE_RATE:
            raise HTTPException(status_code=500, detail="Simulated model-call failure")
    return SimulateCallResponse(status="ok")


@router.get("/instrumentation", response_model=InstrumentationSummary)
async def instrumentation() -> InstrumentationSummary:
    """Aggregate stats over every instrumented_call measurement so far.

    Minimal stand-in for the real monitoring dashboard Relay will build
    in week 8 -- call_count, error_count, p95_duration_ms,
    cumulative_cost.

    In-memory, per-process: with more than one uvicorn/gunicorn worker,
    this only reflects the calls the specific worker handling this
    request happened to see, not the whole app's totals. Fine for
    single-worker local debugging; a real multi-worker deployment needs
    a shared store (see team README "Open decisions").
    """
    return instrumentation_summary()


@router.get("/model-calls", response_model=ModelCallSummary)
async def model_calls() -> ModelCallSummary:
    """Every OCR/ASR/LLM call recorded so far, per call name (#479).

    Count, errors, p50/p95 latency, CPU, tokens and cost, with how many calls
    had no CPU measurement / no reported usage / no price -- a total is None,
    never 0, when none did -- and how many crossed the slow / expensive
    limits (MonitoringSettings). Per-process and in-memory, like
    /debug/instrumentation above.
    """
    return model_call_summary()
