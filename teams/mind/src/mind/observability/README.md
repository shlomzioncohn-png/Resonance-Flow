# observability

Single tracking point for every AI model call MIND makes -- latency and cost.

- `record_call(model_name, duration_ms, cost, *, kind, status, cpu_s,
  prompt_tokens, completion_tokens)` -- every model integration (OCR, ASR,
  LLM extraction, and later embeddings and scoring) calls this once per
  model invocation. Do not log model calls any other way; this is the one
  source of truth the benchmarking work (see `benchmarks/README.md`) and
  any future cost dashboard will read from. Today: OCR (`ocr.py`), ASR
  (`asr.py`) and LLM extraction (`extractor.py`).
  - `cost` is USD, or **`None` for unpriced** (unknown model, no usage
    reported, local compute with no rate yet). Never pass 0.0 to mean
    "don't know": a report must be able to tell unpriced from free.
  - `kind` is `CallKind.OCR` / `ASR` / `LLM`; `status` is
    `CallStatus.ERROR` when the call raised -- failed calls are recorded
    too, they are often the slowest.
  - `cpu_s` is the CPU seconds the call used (the compute-cost signal for
    local models); the token counts are as the LLM provider reported them.
  Everything after `cost` is optional, so an existing call site keeps
  working unchanged.
- Model calls are recorded here, not with `api/instrumentation.py`'s
  `instrumented_call` -- that one times HTTP-level work.
- `get_recent_calls(limit=None)` -- read back what's been recorded so far.
  For tests/debugging, not a metrics backend.
- Storage is currently an in-memory ring buffer (last 10k calls) plus an
  INFO-level log line per call. When a metrics backend is chosen (see the
  team README's "Open decisions"), only `tracking.py`'s storage needs to
  change -- call sites stay the same.
