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
- `compute.py` -- the compute cost of the local models (OCR, ASR):
  - `CpuClock()` measures CPU seconds over a call: this process's own
    (Whisper) or, with `include_children=True`, also the child processes it
    waited for (Tesseract). Windows reports no child-process CPU, so there
    an OCR call's `cpu_s` is `None` -- unknown, not 0.
  - `compute_cost(cpu_s)` prices it at `MIND_COMPUTE_COST__USD_PER_CPU_HOUR`
    (see `.env.example`). No rate set, or no CPU measured, gives `None`.
  - It is CPU time, not wall time: Tesseract keeps several cores busy, so a
    page measured in a Linux container took ~1.1 s wall but ~3.6 s of CPU.
    And it is process-wide -- accurate while one call runs at a time, as in
    MIND's consumers; two overlapping calls in one process are each charged
    for both.
- `get_recent_calls(limit=None)` -- read back what's been recorded so far.
  For tests/debugging, not a metrics backend.
- Storage is currently an in-memory ring buffer (last 10k calls) plus an
  INFO-level log line per call. When a metrics backend is chosen (see the
  team README's "Open decisions"), only `tracking.py`'s storage needs to
  change -- call sites stay the same.
