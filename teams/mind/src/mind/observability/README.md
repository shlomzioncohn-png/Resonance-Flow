# observability

Single tracking point for every AI model call MIND makes -- latency and cost.

- `record_call(model_name, duration_ms, cost, *, kind, status, cpu_s,
  prompt_tokens, completion_tokens)` -- every model integration (OCR, ASR,
  LLM extraction, and later embeddings and scoring) calls this once per
  model invocation. Do not log model calls any other way; this is the one
  source of truth the benchmarking work (see `benchmarks/README.md`) and
  any future cost dashboard will read from. Today: OCR (`ocr.py`), ASR
  (`asr.py`) and LLM extraction (`extractor.py`) -- the last one only
  partly, see "Status of #479" below.
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

## Status of #479 (cost and latency monitoring)

### What each call records

| Call | Latency | CPU (`cpu_s`) | Cost | `kind` / limits / warnings | Tokens |
|---|---|---|---|---|---|
| OCR (`ocr.extract_text`) | yes | Tesseract's child-process CPU; `None` on Windows | `cpu_s` x CPU rate, else `None` | `ocr` -- yes | -- |
| ASR (`asr.transcribe`) | yes | this process's CPU (Whisper runs in it) | `cpu_s` x CPU rate, else `None` | `asr` -- yes | -- |
| LLM (`BasicExtractor._call`, R2's) | yes | -- | token price; **unknown recorded as `0.0`** | **none yet** -- no limits, no warnings | **not recorded yet** |

### Configuration

All optional; read once per process, so a change needs a restart. Shapes
and defaults: `config.ComputeCostSettings` / `config.MonitoringSettings`,
and `.env.example`.

| Variable | Default | Effect |
|---|---|---|
| `MIND_COMPUTE_COST__USD_PER_CPU_HOUR` | unset | prices OCR/ASR CPU; unset leaves their cost `None` |
| `MIND_MONITORING__OCR_MAX_DURATION_MS` | 30000 | OCR call slower than this: `MODEL_CALL_SLOW` |
| `MIND_MONITORING__ASR_MAX_DURATION_MS` | 120000 | same, ASR |
| `MIND_MONITORING__LLM_MAX_DURATION_MS` | 30000 | same, LLM (needs `kind=llm`, see below) |
| `MIND_MONITORING__{OCR,ASR}_MAX_COST_USD` | unset | call dearer than this: `MODEL_CALL_EXPENSIVE` |
| `MIND_MONITORING__LLM_MAX_COST_USD` | 0.01 | same, LLM (needs `kind=llm`) |
| `MIND_MONITORING__SUMMARY_EVERY_N_CALLS` | 100 | log the summary every N calls; 0 = never |

`none` turns a limit off (an empty value is rejected). The limit defaults
are starting points from the measurements so far, not validated targets --
see the `MonitoringSettings` docstring for where each comes from.

### LLM calls: incomplete, pending R2

LLM calls are made in R2's `extraction/extractor.py`, and three changes
there are proposed to her and **not made in this module's PR**: pass
`kind=CallKind.LLM`, `status`, and the token counts to `record_call`; stop
turning an unknown cost into `0.0` (`estimate_cost(...) or 0.0`, and `0.0` on
the failure path); add Gemini's price to `pricing.py`. Until they land:

- LLM calls have no `kind`, so no LLM limit applies and no LLM call ever
  warns;
- token totals in the summary are `null` for every LLM call name;
- a failed call, an unpriced model and every Gemini call are counted as
  priced at $0, not as unpriced -- so an LLM `cost_total_usd` is not a lower
  bound the way the OCR/ASR ones are.

`tests/test_cost_latency_monitoring.py` holds three `xfail(strict=True)`
tests that spell out that change (`kind` and tokens recorded; no price and
no usage both give `cost is None`). **Do not remove or weaken them before
R2's change lands.** When it does, they pass, `strict` turns the pass into
a failure, and the right fix is to drop the `xfail` marker -- nothing else.

### Tests

- `tests/test_cost_latency_monitoring.py` -- the issue's acceptance suite,
  through the real entry points (`extract_text`, `transcribe`, and
  `BasicExtractor` over the real DeepSeek client on a mocked transport).
- `tests/observability/` -- the rules underneath: `record_call`, `CpuClock`
  and `compute_cost`, the summary, the limit warnings.
- The acceptance suite imports helpers from other test modules rather than
  copying them: `tests/test_ocr_result.py`, `tests/test_asr_pipeline.py`,
  and **R2's `tests/extraction/test_deepseek.py`** (`_envelope`, `_always`,
  `_provider`, `VALID_ANSWER`, `SOURCE_TEXT`). Renaming those breaks this
  suite at import time.
- **What CI actually runs:** the test job (`.github/actions/test-python`)
  only `pip install`s the package. Whisper weights are gitignored
  (`models/*`), so the real-Whisper test skips in CI. No workflow step
  installs Tesseract, so the real-Tesseract test runs only if the
  self-hosted runner happens to have it. The fake-backed tests run
  everywhere. Both real-model tests pass in a Linux container with the
  binary and the weights.

### Known limitations

- **No CPU rate is set anywhere yet**, so OCR/ASR costs are `None` in every
  deployed environment. The rate, and where it is configured, is an open
  decision with R1 (team README "Open decisions").
- CPU time is process-wide: accurate while one call runs at a time, as in
  MIND's consumers; overlapping calls in one process are each charged for
  both. It is the share of the machine a call used, not an invoice line.
- OCR's window covers the Tesseract runs only (not PDF rasterizing), and
  ASR's covers VAD + Whisper only (not audio decoding).
- Duration limits are absolute -- not per OCR page or per second of audio --
  so an unusually slow short recording is not flagged.
- Every call over a limit warns, with no rate limiting: a system-wide
  slowdown produces one warning per call.
- An unpriced call never warns on its own; unpriced calls show only as
  `unpriced_count` in the summary.
- In-memory and per-process; resets on restart. The readable week-8 report
  is a separate issue.
