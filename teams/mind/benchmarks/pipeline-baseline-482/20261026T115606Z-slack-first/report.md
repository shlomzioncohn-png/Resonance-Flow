# Demo run 20261026T115606Z-slack-first

## The run

- Files: 2 -- `mind-api` (pid 13160), `mind-landed` (pid 27696)
- Calls recorded: 14; lines not read (not `mind.model_call/1`): 0
- From 2026-10-26T11:58:44+00:00 to 2026-10-26T11:59:29+00:00

## Where the time and the cost go, by stage

Wall time per call. A `chain` row made inside a score (same task, inside its window: 3 call(s) here) breaks that score down and is not added again below; the others are.

| Stage | Kind | Call | Calls | Errors | Degraded | p50 ms | p95 ms | Total | CPU s | USD | Tokens in / out |
|---|---|---|---|---|---|---|---|---|---|---|---|
| to_text | asr | `whisper-base.en-int8-local` | 1 | 0 | 0 | 29,272 | 29,272 | 29.3 s | 43.80 | n/a | n/a |
| extraction | llm | `gemini-3.6-flash` | 3 | 0 | 0 | 1,942 | 2,150 | 6.0 s | n/a | $0.0133 | 15030 / 545 |
| score | scoring | `hierarchical-ranking` | 3 | 0 | 3 | 1,277 | 1,591 | 4.1 s | 0.02 | n/a | n/a |
| score | chain | `chain-ancestors` | 3 | 3 | 0 | 1,275 | 1,550 | 4.0 s | n/a | n/a | n/a |
| score | chain | `chain-children` | 3 | 3 | 0 | 1,150 | 1,259 | 3.6 s | n/a | n/a | n/a |
| to_text | ocr | `tesseract-psm11-ocr` | 1 | 0 | 0 | 1,341 | 1,341 | 1.3 s | unknown (1 unmeasured) | n/a | n/a |

**All calls** (Chain requests inside a score counted once, in the score): 44.3 s of wall time, 43.81 + 1 unmeasured CPU seconds, $0.0133 in LLM calls.

## Scoring: full and degraded

- Scores: 3 -- **full path: 0**, **degraded (Chain could not be asked): 3**, failed: 0
- Chain requests: 6, of which failed: 6; made inside a score: 3, after it (the rescore cascade): 3
- Degraded scores took p50 1,277 ms, p95 1,591 ms.
- Failed Chain requests took p50 1,218 ms, p95 1,504 ms each.

> **Every score in this run is degraded.** Its latency is the fallback path's -- a failed Chain request, then the baseline alone -- not the full scoring path's, and its score has no ancestors in it. It is not a measurement of full scoring.

## Per item

Item: `item_ref` (a digest of tenant and source_ref, observability/context.py). Span: first call's start to last call's end, waits between stages included.

| Item | Tasks | Calls | Span | Sum of calls | to_text | extraction | duplicate_check | task_created | score | CPU s | USD | Degraded scores |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `44cc318fcaf55724` | 1 | 3 | 10.7 s | 4.8 s | - | 1.9 s | - | - | 2.9 s | 0.02 | $0.0041 | 1 |
| `306e54c513b960cb` | 1 | 4 | 34.9 s | 33.9 s | 29.3 s | 2.2 s | - | - | 2.4 s | 43.80 | $0.0047 | 1 |
| `6de12fe6d27d7bc8` | 1 | 4 | 7.8 s | 5.6 s | 1.3 s | 1.9 s | - | - | 2.3 s | 0.00 + 1 unmeasured | $0.0045 | 1 |

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
