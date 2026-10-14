# observability

Single tracking point for every AI model call MIND makes -- latency and cost.

**Cost has a unit per kind (#479):** OCR and ASR cost **CPU seconds** --
MIND does not pay for its machine by the hour, so local models have no
dollar price -- and only LLM calls cost **US dollars**, at the provider's
price per token. Every field and log key carries its unit (`cpu_seconds`,
`cost_usd`, `duration_ms`).

- `record_call(model_name, duration_ms, cost, *, kind, status, cpu_s,
  prompt_tokens, completion_tokens)` -- every model integration (OCR, ASR,
  LLM extraction, and later embeddings and scoring) calls this once per
  model invocation. Do not log model calls any other way; this is the one
  source of truth the benchmarking work (see `benchmarks/README.md`) and
  any future cost dashboard will read from. Today: OCR (`ocr.py`), ASR
  (`asr.py`) and LLM extraction (`extractor.py`); see "Status of #479"
  below.
  - `cost` is the dollar cost of an **LLM** call, or **`None` for
    unpriced** (unknown model, no usage reported). Never pass 0.0 to mean
    "don't know": a report must be able to tell unpriced from free. OCR/ASR
    calls pass `None` -- their cost is `cpu_s`.
  - `cpu_s` is the CPU seconds an **OCR/ASR** call used; the token counts
    are as the LLM provider reported them.
  - `kind` is `CallKind.OCR` / `ASR` / `LLM`, and decides which measures
    apply; `status` is `CallStatus.ERROR` when the call raised -- failed
    calls are recorded too, they are often the slowest.
  Everything after `cost` is optional, so an existing call site keeps
  working unchanged.
- Model calls are recorded here, not with `api/instrumentation.py`'s
  `instrumented_call` -- that one times HTTP-level work.
- `compute.py` -- `CpuClock()` measures the CPU seconds of a local model
  call: this process's own (Whisper) or, with `include_children=True`, also
  the child processes it waited for (Tesseract). Windows reports no
  child-process CPU, so there an OCR call's `cpu_s` is `None` -- unknown,
  not 0. It is CPU time, not wall time: Tesseract keeps several cores busy,
  so a page measured in a Linux container took ~1.1 s wall but ~3.6 s of
  CPU. And it is process-wide -- accurate while one call runs at a time, as
  in MIND's consumers; two overlapping calls in one process are each charged
  for both.
- `get_recent_calls(limit=None)` -- read back what's been recorded so far.
  For tests/debugging, not a metrics backend.
- `model_call_summary()` -- the aggregate, per call name, since the process
  started (see "The summary" below). Served at `GET /debug/model-calls`, and
  logged as one `model_call_summary` line per call name every
  `MIND_MONITORING__SUMMARY_EVERY_N_CALLS` calls (default 100, 0 = off);
  `log_summary()` logs it on demand, e.g. at the end of a batch run or a
  benchmark.
- Storage is currently an in-memory ring buffer (last 10k calls) plus an
  INFO-level log line per call. When a metrics backend is chosen (see the
  team README's "Open decisions"), only `tracking.py`'s storage needs to
  change -- call sites stay the same.

## Status of #479 (cost and latency monitoring)

### What each call records

| Call | Latency | Compute cost | Dollar cost | Tokens | `kind` / limits / warnings |
|---|---|---|---|---|---|
| OCR (`ocr.extract_text`) | `duration_ms` | `cpu_s`: Tesseract's child-process CPU seconds; `None` on Windows | n/a | n/a | `ocr` -- duration limit |
| ASR (`asr.transcribe`) | `duration_ms` | `cpu_s`: this process's CPU seconds (Whisper runs in it) | n/a | n/a | `asr` -- duration limit |
| LLM (`BasicExtractor._call`, R2's) | `duration_ms` | n/a | `cost`: tokens x provider price in US dollars; `None` when unpriced or failed | `prompt_tokens`, `completion_tokens` | `llm` -- duration and dollar limits |

### The summary

Per call name: `call_count`, `error_count`, `p50_duration_ms`,
`p95_duration_ms`, then one total per measure with its own counter:

| Measure | Applies to | Total | Counter |
|---|---|---|---|
| CPU | OCR, ASR | `cpu_seconds_total` | `cpu_unmeasured_count` |
| Tokens | LLM | `prompt_tokens_total`, `completion_tokens_total` | `tokens_unreported_count` |
| Dollars | LLM | `cost_usd_total` | `unpriced_count` |

and the limits in force (`duration_limit_ms`, `cost_limit_usd`) with
`slow_count` / `expensive_count`. The counter tells the two kinds of
`null` apart:

- **Not applicable** -- the counter is `null` too: an OCR call has no
  `cost_usd_total` and no `unpriced_count`, an LLM call no CPU seconds. The
  log line says `n/a`.
- **Unknown** -- the counter is a number: the measure applies, and that many
  calls lacked it (CPU not measured, usage not reported, model unpriced).
  The total covers the rest, so a `cost_usd_total` with `unpriced_count > 0`
  is a lower bound; it is `null` only when every call lacked it -- never 0.
  The log line says `unmeasured` / `unreported` / `unpriced`.

A call recorded without a `kind` gets every measure, since nothing says
which of them it should have. Every call site in MIND passes one today.

Counters are running totals that never evict; only the latency sample
behind p50/p95 is bounded (last 10k per name). In-memory and per-process:
resets on restart, and each worker process only sees its own calls.

### Limits and warnings

A call strictly over its kind's limit logs a WARNING line the moment it is
recorded -- grep for `MODEL_CALL_SLOW` / `MODEL_CALL_EXPENSIVE`:

```
MODEL_CALL_SLOW model_name=tesseract-psm11-ocr kind=ocr status=success duration_ms=45000.0 limit_ms=30000.0 over=1.5x
MODEL_CALL_EXPENSIVE model_name=deepseek-chat kind=llm status=success cost_usd=0.025000 limit_usd=0.010000 over=2.5x
```

Each warning and the summary's `slow_count` / `expensive_count` come from
the same `check_limits()` result, so they always agree. Only LLM calls have
a cost limit (in dollars); OCR/ASR have duration limits only -- a limit in
CPU seconds waits until there is a measured baseline. An unpriced call is
never "expensive" (nor free) -- it shows up in `unpriced_count` instead --
and a call recorded without a `kind` has no limits.

### Configuration

All optional; read once per process, so a change needs a restart. Shapes
and defaults: `config.MonitoringSettings`, and `.env.example`.

| Variable | Default | Effect |
|---|---|---|
| `MIND_MONITORING__OCR_MAX_DURATION_MS` | 30000 | OCR call slower than this: `MODEL_CALL_SLOW` |
| `MIND_MONITORING__ASR_MAX_DURATION_MS` | 120000 | same, ASR |
| `MIND_MONITORING__LLM_MAX_DURATION_MS` | 30000 | same, LLM (needs `kind=llm`, see below) |
| `MIND_MONITORING__LLM_MAX_COST_USD` | 0.01 | LLM call dearer than this: `MODEL_CALL_EXPENSIVE` (needs `kind=llm`) |
| `MIND_MONITORING__SUMMARY_EVERY_N_CALLS` | 100 | log the summary every N calls; 0 = never |

`none` turns a limit off (an empty value is rejected). The limit defaults
are starting points from the measurements so far, not validated targets --
see the `MonitoringSettings` docstring for where each comes from.

### LLM calls

LLM calls are made in R2's `extraction/extractor.py`, in `BasicExtractor._call`
-- the one call site for every LLM call, repair rounds and #351/#352
included (confirmed by R2). The change there was agreed with her on the PR:
every call is recorded with `kind=CallKind.LLM`, its status and token
counts, and an unknown cost stays `None` instead of `0.0`:

- a failed call (no usage reported) and a model with no price on file are
  `None` -- unpriced, counted in `unpriced_count`;
- the dollar price comes from `extraction/providers/pricing.py`. **Gemini has
  no price on this branch:** R2 added one on her own branch
  (`yonit/mind-gemini-pricing`, not merged yet), so until that lands, Gemini
  calls are recorded as unpriced -- correctly, not as $0;
- with R2's pricing branch, Gemini's `completion_tokens` include its
  thinking tokens, which Google bills at the output price.

### Tests

- `tests/test_cost_latency_monitoring.py` -- the issue's acceptance suite,
  through the real entry points (`extract_text`, `transcribe`, and
  `BasicExtractor` over the real DeepSeek client on a mocked transport).
- `tests/observability/` -- the rules underneath: `record_call`, `CpuClock`,
  the summary, the limit warnings (including the dollar-cost boundary).
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

- CPU time is process-wide: accurate while one call runs at a time, as in
  MIND's consumers; overlapping calls in one process are each charged for
  both. It is the share of the machine a call used.
- OCR's window covers the Tesseract runs only (not PDF rasterizing), and
  ASR's covers VAD + Whisper only (not audio decoding).
- Duration limits are absolute -- not per OCR page or per second of audio --
  so an unusually slow short recording is not flagged. There is no CPU-seconds
  limit yet.
- Every call over a limit warns, with no rate limiting: a system-wide
  slowdown produces one warning per call.
- An unpriced LLM call never warns on its own; unpriced calls show only as
  `unpriced_count` in the summary.
- A failed LLM call is recorded as unpriced, but some failures are billed
  (`MAX_TOKENS`, a blocked answer). R2 tracks that on her side; until then an
  LLM `cost_usd_total` can undercount them.
- In-memory and per-process; resets on restart. The readable week-8 report
  is a separate issue.
