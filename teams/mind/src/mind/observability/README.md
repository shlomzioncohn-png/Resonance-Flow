# observability

Single tracking point for every AI model call MIND makes -- latency and cost.

**Cost has a unit per kind (#479):** OCR and ASR cost **CPU seconds** --
MIND does not pay for its machine by the hour, so local models have no
dollar price -- and only LLM calls cost **US dollars**, at the provider's
price per token. Every field and log key carries its unit (`cpu_seconds`,
`cost_usd`, `duration_ms`).

- `record_call(model_name, duration_ms, cost, *, kind, status, cpu_s,
  prompt_tokens, completion_tokens, degraded)` -- every model integration
  (OCR, ASR, LLM extraction, embeddings, scoring) and every other stage of
  the full flow (vector queries, Chain requests) calls this once per
  invocation. Do not log them any other way; this is the one
  source of truth the benchmarking work (see `benchmarks/README.md`) and
  any future cost dashboard will read from. Today: OCR (`ocr.py`), ASR
  (`asr.py`), LLM extraction (`extractor.py`), scoring and Chain requests
  (`recording.py`, wired in `score_publisher_runner.py`); see "Status of
  #479" and "Status of #482" below.
  - `cost` is the dollar cost of an **LLM** call, or **`None` for
    unpriced** (unknown model, no usage reported). Never pass 0.0 to mean
    "don't know": a report must be able to tell unpriced from free. OCR/ASR
    calls pass `None` -- their cost is `cpu_s`.
  - `cpu_s` is the CPU seconds an **OCR/ASR** call used; the token counts
    are as the LLM provider reported them.
  - `kind` is `CallKind.OCR` / `ASR` / `LLM` / `EMBEDDING` / `SCORING` /
    `VECTOR_QUERY` / `CHAIN`, and decides which measures apply; `status` is
    `CallStatus.ERROR` when the call raised -- failed calls are recorded
    too, they are often the slowest.
  - `degraded=True` marks a call that succeeded on a fallback path, so its
    result is not the full path's (a score without its ancestors because
    Chain could not be asked). Counted as `degraded_count`, apart from
    errors.
  Everything after `cost` is optional, so an existing call site keeps
  working unchanged.
- Model calls are recorded here, not with `api/instrumentation.py`'s
  `instrumented_call` -- that one times HTTP-level work.
- A call is recorded once its input is accepted and its model can run. A
  model that is not installed -- OCR without Tesseract, ASR without the
  Whisper weights -- is the host's problem, not a failed call: it would end
  in a millisecond, pull p50 down and inflate `error_count`. It is not
  recorded; the caller gets `OCR_UNAVAILABLE` / `ASR_UNAVAILABLE`, and a
  WARNING line with that marker is logged.
- `compute.py` -- `CpuClock()` measures the CPU seconds of a local model
  call: this process's own (Whisper) or, with `include_children=True`, also
  the child processes it waited for (Tesseract). Windows reports no
  child-process CPU, so there an OCR call's `cpu_s` is `None` -- unknown,
  not 0. It is CPU time, not wall time: Tesseract keeps several cores busy,
  so a page measured in a Linux container took ~1.1 s wall but ~3.6 s of
  CPU. And it is process-wide -- accurate while one call runs at a time, as
  in MIND's consumers; two overlapping calls in one process are each charged
  for both. `CpuClock(this_thread_only=True)` counts the calling thread's
  CPU only: scoring uses it, since it runs in the API next to other threads.
- `recording.py` (#482) -- records a stage from the outside, so the code it
  measures is not edited: `RecordingClient`, an `httpx.Client` that records
  each request (ERROR on a raise or a non-2xx, re-raised/returned
  unchanged), and `RecordedScorer`, which records each `score()` and marks
  it `degraded` when a `RecordingClient` request inside it failed.
- `get_recent_calls(limit=None)` -- read back what's been recorded so far.
  For tests/debugging, not a metrics backend.
- `model_call_summary()` -- the aggregate, per call name, since the process
  started (see "The summary" below). Served at `GET /debug/model-calls`, and
  logged as one `model_call_summary` line per call name every
  `MIND_MONITORING__SUMMARY_EVERY_N_CALLS` calls (default 100, 0 or `none` = off);
  `log_summary()` logs it on demand, e.g. at the end of a batch run or a
  benchmark.
- Storage is currently an in-memory ring buffer (last 10k calls) plus an
  INFO-level log line per call, and -- with `MIND_MONITORING__CALLS_DIR`
  set -- one JSON line per call in a file of the process's own (`sink.py`,
  #482; "The JSONL file" below). When a metrics backend is chosen (see the
  team README's "Open decisions"), only `tracking.py`'s storage needs to
  change -- call sites stay the same.
- `context.py` (#482) -- `call_context(item=, task=, stage=)` attributes every
  call recorded inside the block; record_call() reads it, so no call site
  passes it. See "Item, task and stage" below.
- `report.py` (#482, #484) -- `python -m mind.observability.report [DIR ...]
  [--since 7d] [--out FILE]`: the cost and latency report, over one run or
  many, from every `*.jsonl` under each DIR (default
  `$MIND_MONITORING__CALLS_DIR`, else `demo_flow/runs/`). Totals and cost
  per item, by stage and by model, p50/p95/max, the runs compared. Known and
  unknown costs apart, dollars and CPU seconds never added, Chain requests
  never added to the scores they are inside, degraded scores reported apart
  -- and a stage with no record listed as not measured, never as zero. The
  #359 demo writes it to `runs/<run-id>/report.md` at the end of every run.
  See "Status of #484" below.

## Status of #479 (cost and latency monitoring)

### What each call records

| Call | Latency | Compute cost | Dollar cost | Tokens | `kind` / limits / warnings |
|---|---|---|---|---|---|
| OCR (`ocr.extract_text`) | `duration_ms` | `cpu_s`: rendering the PDF pages (this process) + Tesseract's child processes; `None` on Windows | n/a | n/a | `ocr` -- duration limit |
| ASR (`asr.transcribe`) | `duration_ms` | `cpu_s`: decoding the audio + VAD + Whisper, all in this process | n/a | n/a | `asr` -- duration limit |
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
MODEL_CALL_EXPENSIVE model_name=deepseek-chat kind=llm status=success cost_usd=0.050000 limit_usd=0.020000 over=2.5x
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
| `MIND_MONITORING__LLM_MAX_DURATION_MS` | 10000 | same, LLM |
| `MIND_MONITORING__LLM_MAX_COST_USD` | 0.02 | LLM call dearer than this: `MODEL_CALL_EXPENSIVE` |
| `MIND_MONITORING__SUMMARY_EVERY_N_CALLS` | 100 | log the summary every N calls; 0 or `none` = never |
| `MIND_MONITORING__CALLS_DIR` | unset | also write every call to `<dir>/model-calls-<process>-<pid>.jsonl` (#482) |
| `MIND_MONITORING__PROCESS_NAME` | the script's name | `<process>` in that file's name and on each line; `[A-Za-z0-9_.-]{1,64}` |

`none` turns a limit off (an empty value is rejected). **An invalid value
never breaks a model call:** the settings fall back to all their defaults
and log one ERROR -- the API loads them at start-up, so it shows up then.
The limit defaults
are starting points from the measurements so far, not validated targets --
see the `MonitoringSettings` docstring for where each comes from. The LLM
ones come from R2's measurements with the paid key (~1.4 s a call, 2.2 s at
the slowest; ~$0.0024 a typical item, ~$0.008 the heaviest), and $0.02 leaves
room for gemini-3.6-flash's price doubling on 1.1.2027.

### LLM calls

LLM calls are made in R2's `extraction/extractor.py`, in `BasicExtractor._call`
-- the one call site for every LLM call, repair rounds and #351/#352
included (confirmed by R2). The change there was agreed with her on the PR:
every call is recorded with `kind=CallKind.LLM`, its status and token
counts, and an unknown cost stays `None` instead of `0.0`:

- every call -- failures included -- is recorded under the model id the
  provider sends (`LLMProvider.model`, part of the Protocol), so a model's
  failures and successes share one row and its p95 includes the slow
  failures; a success is priced by the id the answer reports, the model
  actually billed;
- a failed call (no usage reported) and a model with no price on file are
  `None` -- unpriced, counted in `unpriced_count`;
- the dollar price comes from `extraction/providers/pricing.py` (R2's): the
  DeepSeek models and the Gemini models with a published price -- others
  (e.g. `gemini-3.5-flash`) stay unpriced on purpose. A price can change on
  a set date (`effective_from`), which `estimate_cost` takes into account;
- Gemini's `completion_tokens` include its thinking tokens, which Google
  bills at the output price.

### Tests

- `tests/test_cost_latency_monitoring.py` -- the issue's acceptance suite,
  through the real entry points (`extract_text`, `transcribe`, and
  `BasicExtractor` over R2's `ScriptedProvider` from
  `tests/extraction/fakes.py`, the fake her own extraction tests use).
- `tests/observability/` -- the rules underneath: `record_call`, `CpuClock`,
  the summary, the limit warnings (including the dollar-cost boundary).
- The acceptance suite reuses the OCR/ASR helpers in
  `tests/test_ocr_result.py` and `tests/test_asr_pipeline.py` rather than
  copying them, and for LLM calls only `ScriptedProvider` -- R2's public test
  fake -- not her private HTTP-test helpers (her call). How a provider parses
  token usage out of an HTTP response is tested in
  `tests/extraction/test_deepseek.py` / `test_gemini.py`.
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
- A call's window starts once its input is accepted: OCR's includes
  rendering the PDF pages and ASR's decoding the audio, but not loading the
  Whisper model (warm-up, once per process). ASR's transcription timeout
  still counts from Whisper's start, as before.
- Duration limits are absolute -- not per OCR page or per second of audio --
  so an unusually slow short recording is not flagged. There is no CPU-seconds
  limit yet.
- Every call over a limit warns, with no rate limiting: a system-wide
  slowdown produces one warning per call.
- An unpriced LLM call never warns on its own; unpriced calls show only as
  `unpriced_count` in the summary. That count includes failed calls (no usage
  reported); `error_count` on the same row says how many failed.
- A failed LLM call is recorded as unpriced, but some failures are billed
  (`MAX_TOKENS`, a blocked answer). R2 tracks that on her side; until then an
  LLM `cost_usd_total` can undercount them.
- In-memory and per-process; resets on restart. The readable report reads
  the JSONL files instead: "Status of #484" below.

## Status of #482 (the full flow, stage by stage)

### What each stage records

| Stage | `kind` | Call name | Latency | Compute cost | Recorded by |
|---|---|---|---|---|---|
| Scoring (`HierarchicalRankingModel.score`, in the API) | `scoring` | `hierarchical-ranking` | `duration_ms`, Chain requests inside it included | `cpu_s`: this thread's CPU | `RecordedScorer`, wired in `score_publisher_runner.py` |
| Chain's traversal API (`ChainHierarchyClient`, R3's) | `chain` | `chain-ancestors`, `chain-children` | `duration_ms`, the whole request | n/a | `RecordingClient`, the client's HTTP client |
| Embedding (`embeddings.embed`/`embed_batch`, R3's) | `embedding` | `bge-small-en-v1.5-local` | `duration_ms` of `model.encode()`, the model's first load left out; a batch is one call | `cpu_s`: process-wide (torch spreads an encode over a thread pool) | `embeddings._encode()`, approved by R3 |
| Vector query (`PgTenantStore.search`, R3's) | `vector_query` | `pgvector-search` | `duration_ms`, from asking the pool for a connection to the last row | n/a | `PgTenantStore.search()`, approved by R3 |

Tokens and dollars apply to none of them. No stage has a duration limit
yet: a limit waits for the baseline #482 measures, as the OCR/ASR
CPU-seconds limit does. A score's row includes the Chain requests made
inside it; the `chain-*` rows break it down, they are not added to it.

The scoring and Chain stages are recorded from the outside -- the model is
wrapped, and the Chain client gets an HTTP client built with the same base
URL and timeout -- so neither R1's publisher nor R3's model or client
changed. Embedding and vector queries can only be timed inside R3's
`embeddings/` code; that change was proposed to R3 and made with her
approval, as was importing `sentence_transformers` only when a model is
first loaded (~380 MB off every process that never embeds, the API's
included -- `benchmarks/serving-reliability-482.md`). An `upsert` is not
recorded.

### When Chain cannot be asked

`ChainHierarchyClient` swallows every failure -- refused, timed out, a
non-2xx, a body of the wrong shape -- logs a WARNING, and returns `[]`, as
#474 requires. With no ancestors, `HierarchicalRankingModel` scores the
task with the baseline alone: the same score, `hierarchy` contribution 0.0
and reasoning a root task gets. The published payload cannot tell the two
apart (tested: `tests/observability/test_recording.py`).

The ledger can:

- each failed request is a `chain-*` call with status `error` (a refused
  connection, a timeout -- recorded at about the 0.2 s timeout, not the
  server's full time -- or a non-2xx);
- the score it happened in is `success` with `degraded=true`, counted in
  `degraded_count`; a root task's score, with Chain answering `[]`, is not.

Not marked degraded:

- a 2xx whose body Chain's client then rejects (not JSON, not a list).
  `RecordingClient` records it as a success; the client's own WARNING is the
  only trace;
- an ancestor Chain named but this process has not seen since start-up, or
  one the baseline could not score: `ChainAncestorSource` leaves it out of
  the mean and logs a WARNING. Chain answered, so the score is not the
  Chain fallback, but it is a partial one.

A failure in another thread, or one outside a score (the rescore cascade's
`/children`), never marks a score.

`ChainAncestorSource` asks Chain for every task, root or not. So Mind
running without Chain -- the #359 demo stack has none, and sets no
`MIND_CHAIN__BASE_URL` -- records every score as degraded, each after a
failed `chain-ancestors` request. That is the expected state there, not a
fault, and a baseline measured there must say so.

### Item, task and stage

Each record carries the context it was recorded in (`context.py`), so one
item's calls can be added up across both processes. Ids only, never content:

| Field | What it is | Set by |
|---|---|---|
| `item` | `item_ref(tenant_id, source_ref)`: the first 16 hex digits of a SHA-256 of the tenant and the `source_ref` Mind sends Inbox for the item's tasks. Not the `source_ref` itself (a mail id, a Slack link) | the landed runner, from the Bridge event (`source_ref_for_inbox`); the API, from the `source_ref` that comes back on task.created / task.updated. The same value on both sides |
| `task` | Inbox's task id (UUID) | task.created / task.updated handlers |
| `stage` | which handler did the work, one of `context.STAGES` | the runners |

The stages, all set where messages are handled -- in
`landed_consumer_runner.py` and `score_publisher_runner.py` -- so no handler
changed:

| Stage | Process | Covers |
|---|---|---|
| `landed` | landed runner | one `bridge.raw.landed` item, whatever has no finer stage |
| `to_text` | landed runner | reading its objects and turning them into text: OCR, ASR |
| `extraction` | landed runner | the LLM calls that turn the text into tasks |
| `duplicate_check` | landed runner | each candidate task against the index (embedding, vector query) |
| `task_created` | landed runner | Inbox's task.created reply: indexing, dependency resolution |
| `score` | API | one task.created / task.updated scored, its Chain requests included |

`call_context` refuses anything but these shapes (ValueError);
`message_context`, which the runners use, leaves a missing or malformed field
out instead -- a message is never failed for its attribution. A context is a
`ContextVar`: it reaches asyncio tasks and `asyncio.to_thread` started inside
it, and no other thread.

**Not attributed:** the publisher's rescore worker re-scores outside any
message (task, item and stage are null); the duplicate check runs before
Inbox has a task id, so its calls have an item but no task.

### The JSONL file

With `MIND_MONITORING__CALLS_DIR` set, every record_call() is also appended to
`<calls_dir>/model-calls-<process>-<pid>.jsonl`: UTF-8, one JSON object per
line, `\n`-terminated, flushed as written. Format `mind.model_call/2` -- every
key on every line. `/2` is `/1` plus `audio_s` (#484); readers take both, and a
`/1` line's audio length is unknown, not zero.

| Key | Type | Meaning |
|---|---|---|
| `schema` | string | `"mind.model_call/2"` |
| `ts` | string | when the call was recorded, ISO 8601 UTC, microseconds |
| `process` | string | `MIND_MONITORING__PROCESS_NAME`, or the script's name |
| `pid` | int | the writing process |
| `model_name` | string | the call name (the summary's `call_name`) |
| `kind` | string \| null | `ocr`, `asr`, `llm`, `embedding`, `vector_query`, `scoring`, `chain`; null if recorded without one |
| `status` | string | `success` or `error` |
| `duration_ms` | number | wall time, rounded to 3 decimals |
| `cpu_s` | number \| null | CPU seconds |
| `cost_usd` | number \| null | US dollars |
| `prompt_tokens`, `completion_tokens` | int \| null | as the LLM provider reported them |
| `degraded` | bool | succeeded on a fallback path |
| `audio_s` | number \| null | seconds of audio an ASR call ran on, after the cap; null for every other call |
| `item`, `task`, `stage` | string \| null | the context above |

A null measure is either unknown or not applicable, and `kind` says which,
by the same rules as the summary: CPU applies to OCR, ASR, embedding and
scoring; tokens and dollars to LLM; vector queries and Chain have latency
only.

**One file per process, never a shared one.** Appending to one file from two
processes is not safe everywhere -- on Windows the C runtime's append mode
seeks to the end and then writes, so two processes can overwrite each other.
A file per process has one writer; inside it a lock keeps each line whole
across threads. A restarted process gets a new pid, so a new file. A reader
takes every `*.jsonl` in the directory. Tested with two processes writing
4 x 250 calls each at once (`tests/observability/test_sink.py`).

**A failed write never fails a call.** An OSError (disk full, no permission,
the directory gone) is counted, logged as `MODEL_CALL_SINK_FAILED` the first
time and every 100th after, and the call is still in the in-memory ledger
and the log. The file is opened again on the next write. The file and its
directory are created on the first call, so a process that records nothing
leaves no empty file.

## Status of #484 (a cost and latency report a person can read)

A command, not a dashboard: no Prometheus, Grafana or time-series database
-- a metrics backend is Relay's platform territory, and out of scope here.
The report reads what `record_call()` already writes to the JSONL files
(above) and prints Markdown.

### Running it

From `teams/mind/`, with the venv active (`source .venv/bin/activate`, or
`.venv\Scripts\activate` on Windows):

```
python -m mind.observability.report                    # every demo run
python -m mind.observability.report --since 7d         # the last week's
python -m mind.observability.report benchmarks/pipeline-baseline-482 --out report.md
```

| Argument | Default | Effect |
|---|---|---|
| `DIR ...` | `$MIND_MONITORING__CALLS_DIR`, else `demo_flow/runs/` | one run (a directory holding `*.jsonl`), or a directory searched for runs; several may be given |
| `--since` | everything | only calls recorded in the last `30m` / `24h` / `7d` / `2w`, or since a date (`2026-10-20`, UTC unless it names a zone) |
| `--out` | stdout | write the report to a file |
| `--title` | `Mind: per-stage latency and cost` | the report's heading |

It reads files only, so the same command run again after a change, over
the same runs plus the new one, gives the same numbers for the old runs and
a new row in "Runs compared". That is what makes it monitoring rather than a
one-off measurement.

### Which calls it reads

- **A run** is a directory holding `*.jsonl` -- one file per process, as
  `sink.py` writes them. Its name is the directory's, or its parent's for a
  `calls/` folder (`runs/<run-id>/calls/`, the demo's layout).
- **A run found twice** -- a demo run copied into `benchmarks/` -- is read
  once, from the first DIR it is found under, and the report lists the
  other path. Pass the copy you mean first.
- **An item is (run, `item_ref`).** The demo sends the same items every run,
  so the same `item_ref` comes back in each; reading it alone would merge
  them into one item.
- **`--since` cuts by when each call was recorded** (`ts`), not by run: a
  run that started before the cut is only partly in, and the report says
  how many calls were left out.

### What it reports

| Section | Answers |
|---|---|
| What was read | which runs, files, how many calls, the time window |
| Totals and cost per item | what it all cost; per item by item type, median and mean; what 10,000 such items would cost |
| Where the cost goes, by stage | each stage's share of the dollars, the CPU seconds and the wall time |
| Latency and cost, by stage and call | p50, p95 and max per stage and call name, with its CPU seconds, dollars and tokens |
| By model | every call of a model across stages, with CPU seconds and dollars per call |
| ASR per minute of audio | CPU seconds and wall seconds per minute of audio, over the ASR calls that recorded their audio's length (`/2` files) |
| Scoring: full and degraded | how many scores had Chain's ancestors, and how long the fallback took |
| Per item | a row per item (left out above 50 items: the per-type figures stand for them) |
| Runs compared | per run: items, errors, degraded scores, cost per item, p95 of ASR / OCR / LLM / scoring -- did a change help or hurt |
| How reliable these numbers are | what was not measured, and every figure to read with care |

**Item type** is read off which local models ran on the item: `audio` (ASR
ran), `scan` (OCR ran), both, or `text` (neither). It drives the per-type
rows, because an audio item costs many times a text one, and a total over a
mix says little about another mix.

### The two kinds of cost

- **Dollars** -- LLM calls only, at the provider's published price per token
  (`extraction/providers/pricing.py`). Real money.
- **CPU seconds** -- OCR, ASR, embedding and scoring. Machine time, not a
  bill, and never turned into dollars: Mind does not pay for its machine by
  the call, and a dollar rate for it would be an invented one. The
  10,000-item figure gives it in CPU-hours.

The two are never added, in any row or total.

**Cost per item and the 10,000-item figure.** Per item is the dollars and
the CPU seconds of that item's own calls (a Chain request inside a score
counted once). The 10,000-item figure is the mean per item times 10,000 --
the mean, not the median, because totals grow with the mean. It is an
estimate at this data's mix of item types, and the report says so.

### What it labels as unreliable

- A stage with no record: **not measured, not zero**. So are OCR's CPU on
  Windows and the audio length of ASR calls in `/1` files (before #484).
- A total with any unpriced LLM call or unmeasured CPU is followed by the
  count it is missing (`+ 2 unmeasured`); a per-item figure with such a gap
  is marked `>=`. Both are lower bounds.
- Degraded scores, whose latency is the fallback path's.
- Stage/call rows with fewer than 20 calls, where p95 is close to the max
  and says little on its own.
- Calls with no item (the rescore worker): in the totals, in no per-item
  figure.
- Lines that are not `mind.model_call/1` or `/2`, runs found twice, and calls
  left out by `--since`.
- The 10,000-item figure, as an estimate.

### The ring buffer

`tracking.py` keeps the last 10,000 calls in memory, and the last 10,000
durations per call name behind `/debug/model-calls`' p50/p95. A long run
evicts its early calls from both -- but **the report reads neither.** It
reads the JSONL files, which keep every call, so a 1,000-item run (or a
100,000-call one) is reported whole. A process run without
`MIND_MONITORING__CALLS_DIR` writes no file, and is not in the report at
all: set it for any run you mean to report on. Tested with 10,050 lines in
one file.

### Tests

`tests/observability/test_report.py`: the #482 rules above, and for #484
reading a directory of runs, a run found twice, an item per run, `--since`
(every unit, a date, a bad value, the CLI), the default directory, more
lines than the ring buffer holds, max latency, the by-model table, cost per
item and the 10,000-item figure, the `>=` marks, item types, the runs
compared, the small-sample and many-items cases, and ASR per minute of audio
(including a `/1` file, read with its audio length unknown).
`tests/test_cost_latency_monitoring.py` checks that ASR records the audio it
ran on, up to the cap.

### Not done yet

- **The 1,000-item run** (#483) -- the second headline dataset. It waits for
  #483; the report needs no change for it, only the run's directory.
- **The written conclusion** -- the most expensive stage, the cost per item,
  anything trending badly -- is drafted in `benchmarks/cost-latency-484.md`
  from the demo dataset; its 1,000-item half waits for that run. From the
  current #482 baseline (the two 2026-10-27 runs, 6 items, every stage
  recorded): `to_text` -- ASR, with OCR's unmeasured CPU beside it -- is 93% of
  the measured CPU (~31 CPU s a meeting) and 38% of the wall time in calls;
  an item costs ~$0.0044 in LLM calls whatever its
  type; 10,000 items at the demo's mix, ~$45 and at least 30 CPU-hours.
- **Cost per ASR minute** is reported for runs recorded since `audio_s` was
  added (`mind.model_call/2`); the #482 baseline's files are `/1`, so the
  report says it is not recorded there. Worked out by hand from them (the
  meeting is 67.5 s): 23-30 CPU s per audio minute; the long-job measurement
  (`benchmarks/serving-reliability-482.md`), 17-31.
