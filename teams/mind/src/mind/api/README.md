# api

Outward service boundary — the only part of MIND other teams talk to.

- Inbox: publishes enriched task events; conforms to their canonical schema.
- Chain: publishes the per-task score (the exact API boundary); consumes their traversal API in week 7.
- Lens: publishes classification signals for manager-facing views.
- Relay: this image deploys on their platform; publish/consume on their event bus.

`main.py` is the FastAPI app and currently exposes `GET /health` for
Relay's liveness monitoring. Every route here uses the standard error
format from `contracts/errors.py`. Full contract (endpoints, error
format, versioning plan) is documented in `docs/API.md` — that's the
document to share with other teams, not this README.

`v1/` holds `/v1`-prefixed business routes (search/score once Phase 2
lands) — see `v1/README.md`. `/health` deliberately stays here in
`main.py`, outside `v1/`: infrastructure endpoints aren't versioned.

## Instrumentation

`instrumentation.py` has `instrumented_call(name, cost=0.0)` — wrap any
future model call (OCR, ASR, LLM extraction, embeddings, ranking) in it
instead of writing your own timing/logging:

```python
with instrumented_call("whisper-base-local", cost=0.0):
    result = call_whisper_model(audio)
```

It measures wall-clock duration, logs one structured JSON line
(`call_name`, `duration_ms`, `cost`, `timestamp`, `status`), and folds
the result into an in-memory aggregate. A failure inside the wrapped
code is still recorded (`status: "error"`) and always re-raised — it is
never swallowed. `cost` has no real pricing model yet (see team README
"Open decisions") — pass a placeholder (e.g. `0.0`, or
`tokens * a stand-in price`) until one exists; every call must still be
recorded regardless.

Works as a context manager (any call site) or as a decorator
(synchronous call sites only — an `async def` route handler should use
the `with` form inside its body instead, as `/health` and
`/debug/simulate-call` do below).

`debug.py` demonstrates the wrapper end-to-end before any real model
call exists (extraction starts week 4):

- `GET /debug/simulate-call` — sleeps a short random duration and
  reports a placeholder cost, standing in for a real model call.
- `GET /debug/instrumentation` — the aggregate so far: `call_count`,
  `error_count`, `p95_duration_ms`, `cumulative_cost`. A minimal
  stand-in for the real monitoring dashboard Relay builds in week 8 —
  not a persistent metrics store, resets on process restart.

`/health` is wrapped too, purely to prove the mechanism end-to-end — a
health check isn't a model call.

Not the same thing as `observability/tracking.py`'s `record_call()`
(a manual ledger for benchmarking, fed pre-computed numbers). New call
sites should prefer `instrumented_call`.
