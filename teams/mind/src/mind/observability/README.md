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
- `model_call_summary()` -- the aggregate, per call name, since the process
  started: count, errors, p50/p95 latency, CPU total, token totals, cost
  total, and how many calls crossed the slow / expensive limits. Served at
  `GET /debug/model-calls`, and logged as one `model_call_summary` line per
  call name every `MIND_MONITORING__SUMMARY_EVERY_N_CALLS` calls (default
  100, 0 = off); `log_summary()` logs it on demand, e.g. at the end of a batch
  run or a benchmark.
  - **Missing is not zero.** A call with no CPU measurement, no reported
    usage, or no price is left out of that total and counted in
    `cpu_unmeasured_count` / `tokens_unreported_count` / `unpriced_count`. A
    total no call contributed to is `null`, never 0 -- and a cost total with
    `unpriced_count > 0` is a lower bound.
  - **Limits** are per kind (`MIND_MONITORING__OCR_MAX_DURATION_MS`, ...
    `LLM_MAX_COST_USD`; defaults and where they come from in
    `config.MonitoringSettings`; the value `none` turns a check off). A call
    strictly over a limit logs a WARNING line the moment it is recorded --
    grep for `MODEL_CALL_SLOW` / `MODEL_CALL_EXPENSIVE`:

    ```
    MODEL_CALL_SLOW model_name=tesseract-psm11-ocr kind=ocr status=success duration_ms=45000.0 limit_ms=30000.0 over=1.5x
    MODEL_CALL_EXPENSIVE model_name=deepseek-chat kind=llm status=success cost=0.025000 limit_usd=0.010000 over=2.5x
    ```

    Each warning and the summary's `slow_count` / `expensive_count` come from
    the same `check_limits()` result, so they always agree. An unpriced call
    is never "expensive" (nor free) -- it shows up in `unpriced_count`
    instead -- and a call recorded without a `kind` has no limits.
  - In-memory and per-process: resets on restart, and each worker process
    only sees its own calls. Counters are running totals that never evict;
    only the latency sample behind p50/p95 is bounded (last 10k per name).
- Storage is currently an in-memory ring buffer (last 10k calls) plus an
  INFO-level log line per call. When a metrics backend is chosen (see the
  team README's "Open decisions"), only `tracking.py`'s storage needs to
  change -- call sites stay the same.
