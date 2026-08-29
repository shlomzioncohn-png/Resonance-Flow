# observability

Single tracking point for every AI model call MIND makes -- latency and cost.

- `record_call(model_name, duration_ms, cost)` -- every future model
  integration (extraction, embeddings, scoring, multimodal) calls this once
  per model invocation. Do not log model calls any other way; this is the
  one source of truth the benchmarking work (see `benchmarks/README.md`)
  and any future cost dashboard will read from.
- `get_recent_calls(limit=None)` -- read back what's been recorded so far.
  For tests/debugging, not a metrics backend.
- Storage is currently an in-memory ring buffer (last 10k calls) plus an
  INFO-level log line per call. When a metrics backend is chosen (see the
  team README's "Open decisions"), only `tracking.py`'s storage needs to
  change -- call sites stay the same.
