# Full-flow latency and cost baseline (#482) -- PRELIMINARY

> **Preliminary baseline: one run per arrival order with every stage measured. Not
> representative.** Two runs of the #359 demo -- natural order and slack-first, three items
> each -- on one Windows laptop, with no Chain. Every stage of the flow is now recorded,
> embedding and vector queries included (R3's change, approved). But **every score is
> degraded** (Chain was unreachable), so the scoring numbers are not the full scoring path's,
> and **identical ASR work varies up to ~4x between runs on this laptop** (below), so one run
> per order is a snapshot, not a distribution. Before this can serve as week 8's baseline: several
> runs per order, ideally on a machine whose clock is pinned, and full scoring with a live Chain.

## The runs

The current baseline is the 2026-10-27 pair. The 2026-10-26 pair is kept as history (no
embedding or vector-query records then); two runs in between are not a baseline (see "Runs not
used").

| | natural | slack-first |
|---|---|---|
| Run id | `20261027T094205Z-natural` | `20261027T094907Z-slack-first` |
| Items fed (UTC, 2026-10-27) | meeting 09:43:53, Slack 09:45:23, email 09:46:53 (gap 90 s) | Slack 10:00:59, meeting 10:01:04, email 10:01:09 (gap 5 s) |
| Mind code | `3e8505db` | `3e8505db` |
| Demo result | 1/6 -- as documented for this order (finding 1: Inbox keeps one task per `source_ref`, so the meeting's T1 is never created) | 4/6 -- as documented: the meeting's T1 found as a duplicate of Slack's and not sent; 3 requested, 3 created |

Both: branch `michal-brecher/serving-under-flow-482` (on `main` `1a8b59db`), the commits that
record embedding and vector queries (`53456e33`), import sentence-transformers lazily
(`d2d134a2`) and warm the model at the landed runner's start-up (`3e8505db`); Windows 11 Pro,
Intel Core i7-13620H (10 cores, 16 threads), 15.7 GB RAM, Python 3.12.4, Docker 29.8.2, Mind as
two local processes and the stack in Docker; ASR `whisper-base.en-int8-local`, OCR
`tesseract-psm11-ocr`, LLM `gemini-3.6-flash` (paid key), embedding `bge-small-en-v1.5-local`;
**Chain not running** -- `MIND_CHAIN__BASE_URL` unset, so the default
`http://resonance-flow-chain:80`, which does not resolve here. Nothing else ran on the machine
during the two runs. The monitoring did not change either result.

Raw data, kept as recorded, under `pipeline-baseline-482/<run-id>/` (sha256 of the files as
committed, LF line endings; a checkout that converts to CRLF hashes differently):

| Run | File | Lines | sha256 |
|---|---|---|---|
| 2026-10-27 natural | `calls/model-calls-mind-landed-22672.jsonl` | 16 | `60ecf0d1...0b0b18` |
| 2026-10-27 natural | `calls/model-calls-mind-api-31768.jsonl` | 9 | `1c0eb931...7ad9ce` |
| 2026-10-27 slack-first | `calls/model-calls-mind-landed-29660.jsonl` | 16 | `75271928...a5b6a4` |
| 2026-10-27 slack-first | `calls/model-calls-mind-api-31428.jsonl` | 9 | `03d6c49d...33d338` |
| 2026-10-26 natural | `calls/model-calls-mind-landed-2360.jsonl` | 5 | `832728c1...a93632` |
| 2026-10-26 natural | `calls/model-calls-mind-api-22828.jsonl` | 9 | `a105af02...8d440d5` |
| 2026-10-26 slack-first | `calls/model-calls-mind-landed-27696.jsonl` | 5 | `07fd41cc...1a8eb5` |
| 2026-10-26 slack-first | `calls/model-calls-mind-api-13160.jsonl` | 9 | `0f592ea3...35feb23` |

Beside each: `report.md`, as the run wrote it (`python -m mind.observability.report
<run>/calls` writes it again), and `demo-result.txt`, the demo's own PASS/FAIL output. Every
line of every file is `mind.model_call/1` and carries an item and a stage.

## The items

The same three inputs in every run. Bridge sends no `source_ref` in the demo, so the `item_ref`
comes from the per-run correlation id and differs between runs.

| Item | `item_ref` 10-27 natural / slack-first | Input | Tasks scored |
|---|---|---|---|
| Meeting (Zoom) | `160ef010ac437a17` / `beffd4bd62c20847` | 67.5 s of audio; 778 chars of text for extraction | 1 / 1 (natural: T1 dropped by Inbox; slack-first: T1 merged into Slack's) |
| Slack thread | `e78aadc5058ccb5c` / `fafd7ee3c8ab45d2` | 213 chars of text for extraction | 1 / 1 |
| Email + scanned form | `d0916ccd2b9981c2` / `82e8a42cd6b82af8` | body + one scanned document (OCR); 1,050 chars of text for extraction | 1 / 1 |

## Per stage (2026-10-27)

Wall time of each recorded call, from the JSONL. Lists are in the order meeting / Slack /
email; the meeting's duplicate check is two calls (its two candidate tasks).

| Stage | Call | Run | Wall, each (ms) | Wall total | Compute cost | Dollar cost |
|---|---|---|---|---|---|---|
| to_text | ASR `whisper-base.en-int8-local` | natural | 6,928.4 | 6.9 s | **25.94 CPU s** (RTF 0.101) | n/a |
| | | slack-first | 8,918.3 | 8.9 s | **33.39 CPU s** (RTF 0.130) | n/a |
| to_text | OCR `tesseract-psm11-ocr` | natural | 801.3 (email) | 0.8 s | **unmeasured** (Windows reports no child-process CPU) | n/a |
| | | slack-first | 1,234.6 (email) | 1.2 s | **unmeasured** | n/a |
| extraction | LLM `gemini-3.6-flash` | natural | 3,057.6 / 1,954.9 / 2,048.3 | 7.1 s | n/a | **$0.0135**, all 3 priced; 15,030 tokens in / 594 out |
| | | slack-first | 2,248.9 / 1,999.9 / 2,184.6 | 6.4 s | n/a | **$0.01351125**, all 3 priced; 15,030 in / 597 out |
| duplicate_check | embedding `bge-small-en-v1.5-local` | natural | 132.3 + 14.5 / 25.2 / 54.5 | 0.2 s | 1.38 CPU s | n/a |
| | | slack-first | 35.7 + 28.3 / 102.8 / 30.6 | 0.2 s | 1.42 CPU s | n/a |
| duplicate_check | vector query `pgvector-search` | natural | 27.5 + 11.5 / 51.1 / 49.8 | 0.1 s | n/a | n/a |
| | | slack-first | 25.4 + 55.0 / 20.6 / 52.8 | 0.2 s | n/a | n/a |
| task_created | embedding (indexing the created task) | natural | 23.2 / 56.4 / 44.6 | 0.1 s | 0.73 CPU s | n/a |
| | | slack-first | 44.3 / 28.3 / 44.1 | 0.1 s | 0.81 CPU s | n/a |
| score | scoring `hierarchical-ranking` -- **degraded** | natural | 1,280.3 / 1,303.1 / 1,281.6 | 3.9 s | 0.02 CPU s (thread CPU, 15.6 ms steps on Windows) | n/a |
| | | slack-first | 1,301.5 / 1,297.4 / 1,151.7 | 3.8 s | 0.00 CPU s (below one step) | n/a |
| score | Chain `/ancestors`, inside each score -- **failed** | natural | 1,277.4 / 1,301.5 / 1,279.5 | (inside scoring) | n/a | n/a |
| | | slack-first | 1,299.8 / 1,294.5 / 1,149.9 | (inside scoring) | n/a | n/a |
| score | Chain `/children`, after each score (rescore cascade) -- **failed** | natural | 1,142.2 / 1,139.4 / 1,146.3 | 3.4 s | n/a | n/a |
| | | slack-first | 1,144.0 / 1,144.9 / 1,144.8 | 3.4 s | n/a | n/a |

All recorded calls, each counted once: **22.6 s** of wall time, 28.06 + 1 unmeasured CPU s
(natural); **24.2 s**, 35.62 + 1 unmeasured CPU s (slack-first).

## Per item (2026-10-27)

| Item | Run | Span (first call start -> last call end) | Sum of calls | to_text | extraction | duplicate_check | task_created | score (incl. `/children`) | CPU s | LLM $ |
|---|---|---|---|---|---|---|---|---|---|---|
| Meeting | natural | 18.3 s | 12.6 s | 6.9 s | 3.1 s | 0.2 s | 0.0 s | 2.4 s | 27.12 | $0.0050 |
| | slack-first | 16.6 s | 13.8 s | 8.9 s | 2.2 s | 0.1 s | 0.0 s | 2.4 s | 34.30 | $0.0047 |
| Slack | natural | 8.4 s | 4.5 s | -- | 2.0 s | 0.1 s | 0.1 s | 2.4 s | 0.53 | $0.0041 |
| | slack-first | 6.6 s | 4.6 s | -- | 2.0 s | 0.1 s | 0.0 s | 2.4 s | 0.88 | $0.0043 |
| Email | natural | 8.4 s | 5.4 s | 0.8 s | 2.0 s | 0.1 s | 0.0 s | 2.4 s | 0.41 + 1 unmeasured | $0.0044 |
| | slack-first | 10.1 s | 5.8 s | 1.2 s | 2.2 s | 0.1 s | 0.0 s | 2.3 s | 0.45 + 1 unmeasured | $0.0045 |

The span includes what records nothing: Kafka, MinIO, Inbox creating the task, the wait for
`task.created`, and -- in slack-first, where items arrive 5 s apart -- waiting behind the item
before it.

## Cost, known and unknown

Two units, never added together.

- **Dollars, measured:** LLM calls only. **$0.0135 per run, ~$0.0041-0.0050 per item**, every
  call priced, at `pricing.py`'s price for `gemini-3.6-flash` -- which doubles on 2027-01-01.
  Steady across all four runs: the same 15,030 input tokens; 536-597 output.
- **CPU seconds, measured:** ASR 25.9 / 33.4 s; embedding 2.1 / 2.2 s (7 calls per run, 0.13-0.89
  s each); scoring 0.02 / 0.00 s.
- **CPU seconds, not measured:** OCR (1 call per run, Windows).
- **Not measured at all, in any unit:** the infrastructure -- Kafka, MinIO, Postgres/pgvector,
  Inbox -- and the machine itself. No dollar figure here covers them.

## What dominates

- **The meeting's ASR**: 6.9 of the item's 12.6 s of calls (55%) in natural, 8.9 of 13.8 s
  (65%) in slack-first, and 92-94% of each run's measured CPU. 0.38-0.49 CPU s per second of
  audio here; across all runs, 0.28-0.65 (`serving-reliability-482.md` measured the longest job).
  The stage to watch for week 8's 1,000-item target (#483) if the mix has much audio.
- **LLM extraction: ~2-3 s and ~$0.0041-0.0050 per item**, the only dollar cost.
- **Chain's failure: ~2.4 s per task** -- `/ancestors` inside the score, `/children` after it.
  In the demo environment more than any other stage but ASR; not the full path's cost.
- **Embedding and vector queries are small**: 0.1-0.3 s of wall and up to ~1.2 CPU s per item
  (the meeting, two candidates and one created task), the first embedding of a process (132 ms)
  included.

## ASR: the same recording, 6.9 to 29.3 s

| Run | Wall | CPU s | RTF |
|---|---|---|---|
| 2026-10-26 natural | 16.9 s | 34.3 | 0.244 |
| 2026-10-26 slack-first | 29.3 s | 43.8 | 0.427 |
| 2026-10-27 natural | 6.9 s | 25.9 | 0.101 |
| 2026-10-27 slack-first | 8.9 s | 33.4 | 0.130 |

**Correction.** The previous version of this baseline gave a likely cause for the 2026-10-26
slack-first ASR being 1.7x slower: Slack's task being indexed, with embedding calls, in the
same process during it. **Now that embedding is measured, that explanation does not hold:**
indexing a task takes 23-56 ms and 0.16-0.41 CPU s, nowhere near the 12.3 s and 9.5 CPU s gap,
and in the 2026-10-27 slack-first run the indexing had finished before the ASR began. The
measured fact remains -- identical ASR work varies ~4x in wall time and ~1.7x in CPU across these
runs, and 2.6x in the long-job measurement -- and the most likely cause is the laptop itself
(power and thermal state, other work), which was not controlled. Read single-run ASR numbers
with that in mind.

## Scoring: all degraded

All three scores in each run are **degraded**: Chain could not be asked, so each is the
baseline's own score, with no ancestors in it (`hierarchy` contribution 0.0), and its latency is
the fallback path's. **These are not measurements of full scoring** and must not be used as its
baseline. Of each 1.15-1.30 s score, all but 1.6-2.9 ms was the failed `/ancestors` request: the
scoring model itself is a few milliseconds; the full path's cost is whatever a live Chain adds.

## Findings

### For R3: the 0.2 s Chain timeout does not bound a DNS failure (open, unchanged)

`ChainHierarchyClient` sets `DEFAULT_TIMEOUT_SECONDS = 0.2` "to fail fast on a struggling one".
In all four runs every request failed with `ConnectError: [Errno 11001] getaddrinfo failed`
after **1.14-1.58 s** -- 24 of 24, 6-8x the timeout: the name lookup happens before the
connection, and httpx's timeout does not cover it. Each task paid ~2.4 s for it. It does not
happen in the cluster while Chain's Service resolves; it does wherever Chain's name is missing
(the demo, local runs, a misconfigured `MIND_CHAIN__BASE_URL`). Recorded, not changed: R3's to
decide.

### Fixed during these runs: the lazy import stalled the first item

Importing `sentence_transformers` only when a model is loaded (`d2d134a2`) took ~380 MB off Mind's
API (`serving-reliability-482.md`). But in the landed runner, which embeds, it moved the import
into the first item to reach the duplicate check: in the two runs right after it
(`20261027T092505Z-natural`, `20261027T093406Z-slack-first`) that item stalled ~100 s and ~33 s
before its first embedding, unrecorded, with the items behind it waiting. The import alone
measured 20 s warm. `3e8505db` warms the model at the landed runner's start-up, beside
`asr.warm_up()`; in both runs above the first embedding follows the LLM call within ~0.15 s.

### Each created task is embedded twice (week 8, not changed)

The duplicate check embeds a candidate's text; when Inbox creates the task, indexing embeds the
same text again (`task_text(title, description)`). Measured: 23-56 ms and 0.16-0.41 CPU s per
repeat, one per created task -- about 1% of an item's calls at most. A cache would have to cross from
the sender's loop to the listener's thread; not worth it at this size. Noted for week 8, when
volume might make it matter.

## Runs not used

- `20261027T092505Z-natural`, `20261027T093406Z-slack-first` -- the first runs with embedding
  recorded, before `3e8505db`: each has the first-item import stall above, and the second
  overlapped other work on the machine. They are evidence for that fix, not a baseline; not kept.

## History: the 2026-10-26 runs

Kept under `pipeline-baseline-482/20261026T*/`, recorded before embedding and vector queries
were instrumented (both stages show no records there -- not measured, not zero), on code
`7d902f66`. Their ASR is in the table above; otherwise:

| | 10-26 natural | 10-26 slack-first |
|---|---|---|
| LLM extraction, 3 calls | 6.7 s, $0.0132825 | 6.0 s, $0.01331625 |
| OCR (email) | 0.9 s | 1.3 s |
| Scoring, 3 calls, all degraded | 4.0 s | 4.1 s |
| Chain requests, all failed | 1.14-1.40 s each | 1.14-1.58 s each |
| All recorded calls | 32.0 s | 44.3 s |
| Demo result | 1/6 | 4/6 |

## Known limitations

- **One run per order** with every stage measured. No variance, no p95 worth the name, and ASR
  alone varies ~4x between runs.
- **Windows laptop, not the cluster.** No container CPU limits; OCR CPU unmeasured; thread CPU in
  15.6 ms steps; the machine's power and thermal state not controlled.
- **Scoring degraded throughout** (above).
- **Demo correctness is 1/6 and 4/6** (`demo_flow/README.md`): in natural one of the meeting's
  tasks is never created, so never indexed or scored; in neither run is a dependency published.
- **Not recorded:** `upsert` into the index, Kafka, MinIO, Inbox -- the last three show only in
  an item's span. A re-score from the rescore worker carries no item or task (none happened here).
- **Warm processes.** Whisper and the embedding model are loaded at start-up, so model loading is
  in no number here.

## Reproduce

From `teams/mind`, with Docker and `demo_flow/stack/secrets.yaml`:

```bash
python -m demo_flow.run --order natural
python -m demo_flow.run --order slack-first
python -m mind.observability.report demo_flow/runs/<run-id>/calls
```

The demo writes `runs/<run-id>/report.md` itself. To re-read this baseline:
`python -m mind.observability.report benchmarks/pipeline-baseline-482/<run-id>/calls`.
