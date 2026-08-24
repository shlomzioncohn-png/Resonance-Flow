"""Temporary /debug endpoints that exercise instrumented_call end-to-end
before any real model call exists (extraction starts week 4). See
api/README.md "Instrumentation". Not part of the versioned /v1 API
surface -- /debug/simulate-call goes away once a real business endpoint
can demonstrate the wrapper instead.
"""

from __future__ import annotations

import asyncio
import random

from fastapi import APIRouter

from mind.api.instrumentation import instrumentation_summary, instrumented_call

router = APIRouter(prefix="/debug", tags=["debug"])


@router.get("/simulate-call")
async def simulate_call() -> dict:
    """Stand-in for a real model call (OCR/ASR/LLM/...) until week 4.

    Sleeps a short random duration and reports a placeholder cost, so
    instrumented_call has something to wrap end-to-end before any real
    model call exists.
    """
    cost = round(random.uniform(0.0001, 0.01), 6)
    with instrumented_call("simulate_call", cost=cost):
        await asyncio.sleep(random.uniform(0.01, 0.1))
    return {"status": "ok"}


@router.get("/instrumentation")
async def instrumentation() -> dict:
    """Aggregate stats over every instrumented_call measurement so far.

    Minimal stand-in for the real monitoring dashboard Relay will build
    in week 8 -- call_count, error_count, p95_duration_ms,
    cumulative_cost.
    """
    return instrumentation_summary()
