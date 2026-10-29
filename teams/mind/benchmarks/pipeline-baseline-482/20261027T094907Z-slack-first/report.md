# Demo run 20261027T094907Z-slack-first

## What was read

- Run: `20261027T094907Z-slack-first`
- Files: 2 -- `mind-api` (pid 31428), `mind-landed` (pid 29660)
- Calls recorded: 25; lines not read (not `mind.model_call/1`): 0
- From 2026-10-27T10:00:59+00:00 to 2026-10-27T10:01:26+00:00

## Totals and cost per item

3 item(s) in 1 run(s). Totals: **$0.0135** in LLM calls, **35.62 + 1 unmeasured CPU seconds** of local compute, 24.2 s of wall time in calls (Chain requests inside a score counted once, in the score).

Per item: the LLM dollars and the local CPU seconds of its calls, never added together. Item type is read off which local models ran on it. The last column is the mean times 10,000.

| Items | Count | USD per item, median / mean | CPU s per item, median / mean | Span, median / max | 10,000 such items |
|---|---|---|---|---|---|
| text | 1 | $0.0043 / $0.0043 | 0.88 / 0.88 | 6.6 s / 6.6 s | $42.98 and 2.4 CPU-hours |
| audio | 1 | $0.0047 / $0.0047 | 34.30 / 34.30 | 16.6 s / 16.6 s | $46.84 and 95.3 CPU-hours |
| scan | 1 | $0.0045 / $0.0045 | >=0.45 / >=0.45 | 10.1 s / 10.1 s | $45.30 and >=1.3 CPU-hours |
| **all items** | 3 | $0.0045 / $0.0045 | >=0.88 / >=11.88 | 10.1 s / 16.6 s | $45.04 and >=33.0 CPU-hours |

>= marks a lower bound: 0 item(s) have an LLM call with no price, 1 a local call whose CPU was not measured. The 10,000-item figure assumes this data's mix of item types; a different mix costs differently -- the per-type rows say how.

## Where the cost goes, by stage

Each stage's share of the measured total: dollars of the priced LLM calls, CPU seconds of the measured local calls, wall time of every call. Chain requests inside a score are in the score's row only.

| Stage | Calls | USD | % of USD | CPU s | % of CPU | Wall time | % of wall |
|---|---|---|---|---|---|---|---|
| to_text | 2 | n/a | - | 33.39 + 1 unmeasured | 94% | 10.2 s | 42% |
| score | 6 | n/a | - | 0.00 | 0% | 7.2 s | 30% |
| extraction | 3 | $0.0135 | 100% | n/a | - | 6.4 s | 27% |
| duplicate_check | 8 | n/a | - | 1.42 | 4% | 0.4 s | 1% |
| task_created | 3 | n/a | - | 0.81 | 2% | 0.1 s | 0% |

## Latency and cost, by stage and call

Wall time per call. A `chain` row made inside a score (same task, inside its window: 3 call(s) here) breaks that score down and is not added again below; the others are.

| Stage | Kind | Call | Calls | Errors | Degraded | p50 ms | p95 ms | Max ms | Total | CPU s | USD | Tokens in / out |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| to_text | asr | `whisper-base.en-int8-local` | 1 | 0 | 0 | 8,918 | 8,918 | 8,918 | 8.9 s | 33.39 | n/a | n/a |
| extraction | llm | `gemini-3.6-flash` | 3 | 0 | 0 | 2,185 | 2,243 | 2,249 | 6.4 s | n/a | $0.0135 | 15030 / 597 |
| score | scoring | `hierarchical-ranking` | 3 | 0 | 3 | 1,297 | 1,301 | 1,302 | 3.8 s | 0.00 | n/a | n/a |
| score | chain | `chain-ancestors` | 3 | 3 | 0 | 1,294 | 1,299 | 1,300 | 3.7 s | n/a | n/a | n/a |
| score | chain | `chain-children` | 3 | 3 | 0 | 1,145 | 1,145 | 1,145 | 3.4 s | n/a | n/a | n/a |
| to_text | ocr | `tesseract-psm11-ocr` | 1 | 0 | 0 | 1,235 | 1,235 | 1,235 | 1.2 s | unknown (1 unmeasured) | n/a | n/a |
| duplicate_check | embedding | `bge-small-en-v1.5-local` | 4 | 0 | 0 | 33.2 | 92.7 | 103 | 0.2 s | 1.42 | n/a | n/a |
| duplicate_check | vector_query | `pgvector-search` | 4 | 0 | 0 | 39.1 | 54.6 | 55.0 | 0.2 s | n/a | n/a | n/a |
| task_created | embedding | `bge-small-en-v1.5-local` | 3 | 0 | 0 | 44.1 | 44.3 | 44.3 | 0.1 s | 0.81 | n/a | n/a |

**All calls** (Chain requests inside a score counted once, in the score): 24.2 s of wall time, 35.62 + 1 unmeasured CPU seconds, $0.0135 in LLM calls.

## By model

Every call of a model, whatever stage made it. Per call: over the calls the measure is known for.

| Call | Kind | Calls | Errors | p50 ms | p95 ms | Max ms | CPU s | CPU s per call | USD | USD per call | Tokens in / out |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `whisper-base.en-int8-local` | asr | 1 | 0 | 8,918 | 8,918 | 8,918 | 33.39 | 33.39 | n/a | n/a | n/a |
| `gemini-3.6-flash` | llm | 3 | 0 | 2,185 | 2,243 | 2,249 | n/a | n/a | $0.0135 | $0.0045 | 15030 / 597 |
| `hierarchical-ranking` | scoring | 3 | 0 | 1,297 | 1,301 | 1,302 | 0.00 | 0.00 | n/a | n/a | n/a |
| `chain-ancestors` | chain | 3 | 3 | 1,294 | 1,299 | 1,300 | n/a | n/a | n/a | n/a | n/a |
| `chain-children` | chain | 3 | 3 | 1,145 | 1,145 | 1,145 | n/a | n/a | n/a | n/a | n/a |
| `tesseract-psm11-ocr` | ocr | 1 | 0 | 1,235 | 1,235 | 1,235 | unknown (1 unmeasured) | n/a | n/a | n/a | n/a |
| `bge-small-en-v1.5-local` | embedding | 7 | 0 | 35.7 | 85.2 | 103 | 2.23 | 0.32 | n/a | n/a | n/a |
| `pgvector-search` | vector_query | 4 | 0 | 39.1 | 54.6 | 55.0 | n/a | n/a | n/a | n/a | n/a |

## Scoring: full and degraded

- Scores: 3 -- **full path: 0**, **degraded (Chain could not be asked): 3**, failed: 0
- Chain requests: 6, of which failed: 6; made inside a score: 3, after it (the rescore cascade): 3
- Degraded scores took p50 1,297 ms, p95 1,301 ms.
- Failed Chain requests took p50 1,147 ms, p95 1,298 ms each.

> **Every score in this run is degraded.** Its latency is the fallback path's -- a failed Chain request, then the baseline alone -- not the full scoring path's, and its score has no ancestors in it. It is not a measurement of full scoring.

## Per item

Item: `item_ref` (a digest of tenant and source_ref, observability/context.py). Span: first call's start to last call's end, waits between stages included.

| Item | Type | Tasks | Calls | Span | Sum of calls | to_text | extraction | duplicate_check | task_created | score | CPU s | USD | Degraded scores |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `fafd7ee3c8ab45d2` | text | 1 | 6 | 6.6 s | 4.6 s | - | 2.0 s | 0.1 s | 0.0 s | 2.4 s | 0.88 | $0.0043 | 1 |
| `beffd4bd62c20847` | audio | 1 | 9 | 16.6 s | 13.8 s | 8.9 s | 2.2 s | 0.1 s | 0.0 s | 2.4 s | 34.30 | $0.0047 | 1 |
| `82e8a42cd6b82af8` | scan | 1 | 7 | 10.1 s | 5.8 s | 1.2 s | 2.2 s | 0.1 s | 0.0 s | 2.3 s | 0.45 + 1 unmeasured | $0.0045 | 1 |

Calls with no item: 0

## How reliable these numbers are

### Not measured

- Every expected stage has records.
- Calls whose CPU seconds were not measured: 1 (OCR on Windows reports no child-process CPU)
- LLM calls with no dollar price: 0
- LLM calls with no token counts: 0

### Read with care

- 3 of 3 scores are degraded (Chain could not be asked): their latency and CPU are the fallback path's, not full scoring's.
- Fewer than 20 calls, so p95 is close to the max and says little on its own: duplicate_check/`bge-small-en-v1.5-local` (4), duplicate_check/`pgvector-search` (4), extraction/`gemini-3.6-flash` (3), score/`chain-ancestors` (3), score/`chain-children` (3), score/`hierarchical-ranking` (3), task_created/`bge-small-en-v1.5-local` (3), to_text/`tesseract-psm11-ocr` (1), to_text/`whisper-base.en-int8-local` (1)
- The 10,000-item figure is the mean per item times 10,000: an estimate at this data's mix of item types, not a measurement.

## How to read it

- Dollars are LLM calls only, at the provider's price per token; local work costs CPU seconds -- machine time, not a bill -- never converted to dollars. The two are never added.
- A total beside an `unpriced` / `unmeasured` count, or marked >=, is a lower bound.
- CPU seconds: OCR/ASR are process-wide (Tesseract and Whisper use several cores, so CPU can exceed wall time); scoring is its own thread's, which Windows counts in 15.6 ms steps -- a 0.00 there is below that, not free.
- Not recorded at all: Kafka, MinIO, Inbox and the time between stages -- they show only in an item's span.
- Every line of the JSONL files is read: the in-memory ring buffer (last 10,000 calls) is not used, so a long run is not cut to its tail.
