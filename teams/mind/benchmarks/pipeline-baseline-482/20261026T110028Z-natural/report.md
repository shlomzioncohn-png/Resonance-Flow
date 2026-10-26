# Demo run 20261026T110028Z-natural

## The run

- Files: 2 -- `mind-api` (pid 22828), `mind-landed` (pid 2360)
- Calls recorded: 14; lines not read (not `mind.model_call/1`): 0
- From 2026-10-26T11:02:43+00:00 to 2026-10-26T11:05:53+00:00

## Where the time and the cost go, by stage

Wall time per call. A `chain` row made inside a score (same task, inside its window: 3 call(s) here) breaks that score down and is not added again below; the others are.

| Stage | Kind | Call | Calls | Errors | Degraded | p50 ms | p95 ms | Total | CPU s | USD | Tokens in / out |
|---|---|---|---|---|---|---|---|---|---|---|---|
| to_text | asr | `whisper-base.en-int8-local` | 1 | 0 | 0 | 16,946 | 16,946 | 16.9 s | 34.28 | n/a | n/a |
| extraction | llm | `gemini-3.6-flash` | 3 | 0 | 0 | 2,267 | 2,408 | 6.7 s | n/a | $0.0133 | 15030 / 536 |
| score | scoring | `hierarchical-ranking` | 3 | 0 | 3 | 1,368 | 1,402 | 4.0 s | 0.02 | n/a | n/a |
| score | chain | `chain-ancestors` | 3 | 3 | 0 | 1,365 | 1,395 | 4.0 s | n/a | n/a | n/a |
| score | chain | `chain-children` | 3 | 3 | 0 | 1,150 | 1,151 | 3.4 s | n/a | n/a | n/a |
| to_text | ocr | `tesseract-psm11-ocr` | 1 | 0 | 0 | 903 | 903 | 0.9 s | unknown (1 unmeasured) | n/a | n/a |

**All calls** (Chain requests inside a score counted once, in the score): 32.0 s of wall time, 34.30 + 1 unmeasured CPU seconds, $0.0133 in LLM calls.

## Scoring: full and degraded

- Scores: 3 -- **full path: 0**, **degraded (Chain could not be asked): 3**, failed: 0
- Chain requests: 6, of which failed: 6; made inside a score: 3, after it (the rescore cascade): 3
- Degraded scores took p50 1,368 ms, p95 1,402 ms.
- Failed Chain requests took p50 1,212 ms, p95 1,390 ms each.

> **Every score in this run is degraded.** Its latency is the fallback path's -- a failed Chain request, then the baseline alone -- not the full scoring path's, and its score has no ancestors in it. It is not a measurement of full scoring.

## Per item

Item: `item_ref` (a digest of tenant and source_ref, observability/context.py). Span: first call's start to last call's end, waits between stages included.

| Item | Tasks | Calls | Span | Sum of calls | to_text | extraction | duplicate_check | task_created | score | CPU s | USD | Degraded scores |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `df0c4e4aa56cd405` | 1 | 4 | 25.5 s | 21.9 s | 16.9 s | 2.4 s | - | - | 2.6 s | 34.30 | $0.0047 | 1 |
| `61957176ab79969a` | 1 | 3 | 5.4 s | 4.4 s | - | 2.0 s | - | - | 2.4 s | 0.00 | $0.0041 | 1 |
| `ab89e32bc4e544f1` | 1 | 4 | 10.6 s | 5.7 s | 0.9 s | 2.3 s | - | - | 2.5 s | 0.00 + 1 unmeasured | $0.0045 | 1 |

Calls with no item: 0

## Not measured in this run

- embedding (`embeddings.embed`) -- needs R3's change: no record -- **not measured, not zero**
- vector query (`PgTenantStore.search`) -- needs R3's change: no record -- **not measured, not zero**
- Calls whose CPU seconds were not measured: 1 (OCR on Windows reports no child-process CPU)
- LLM calls with no dollar price: 0

## How to read it

- Dollars are LLM calls only, at the provider's price per token; local work costs CPU seconds, never converted to dollars. The two are never added.
- A total beside an `unpriced` / `unmeasured` count is a lower bound.
- CPU seconds: OCR/ASR are process-wide (Tesseract and Whisper use several cores, so CPU can exceed wall time); scoring is its own thread's, which Windows counts in 15.6 ms steps -- a 0.00 there is below that, not free.
- Not recorded at all: Kafka, MinIO, Inbox and the time between stages -- they show only in an item's span.
