# Full-flow latency and cost baseline (#482) -- PRELIMINARY

> **Preliminary baseline: one run per arrival order, two runs in all. Not complete, not
> representative.** Two runs of the #359 demo -- natural order and slack-first, three items
> each -- on one Windows laptop, with no Chain. Embedding and vector queries are **not
> measured** (not zero), and **every score is degraded** (Chain was unreachable), so the scoring
> numbers are not the full scoring path's. The two runs' ASR timings differ by 1.7x on the
> same recording (below), which is what one run per order cannot settle. Still pending before
> this can serve as week 8's baseline: R3's embedding/vector-query instrumentation, and several
> runs per order.

## The runs

| | Run 1 | Run 2 |
|---|---|---|
| Run id | `20261026T110028Z-natural` | `20261026T115606Z-slack-first` |
| Command | `python -m demo_flow.run --order natural` | `python -m demo_flow.run --order slack-first` |
| Items fed (UTC, 2026-10-26) | meeting 11:02:43, Slack 11:04:13, email 11:05:43 (gap 90 s) | Slack 11:58:44, meeting 11:58:49, email 11:58:54 (gap 5 s) |
| Mind code | `7d902f66` | `7d902f66` (branch at `6c4df5d7`, which changed only this baseline) |
| Demo result | 1/6 -- as documented for this order (finding 1: Inbox keeps one task per `source_ref`, so the meeting's T1 is never created) | 4/6 -- as documented for this order: the meeting's T1 found as a duplicate of Slack's (similarity 0.784) and not sent; 3 requested, 3 created |

Both runs, the same: branch `michal-brecher/serving-under-flow-482` (on `main` `29aaca0f`),
the demo runner and report as committed in `72d7b26d`; Windows 11 Pro, Intel Core i7-13620H
(10 cores, 16 threads), 15.7 GB RAM, Python 3.12.4, Docker 29.8.2, Mind as two local
processes and the stack in Docker; ASR `whisper-base.en-int8-local`, OCR
`tesseract-psm11-ocr`, LLM `gemini-3.6-flash` (paid key); **Chain not running** --
`MIND_CHAIN__BASE_URL` unset, so the default `http://resonance-flow-chain:80`, which does not
resolve on this machine. The monitoring did not change either result.

Raw data, kept as recorded, under `pipeline-baseline-482/<run-id>/` (sha256 of the files as
committed, LF line endings; a checkout that converts to CRLF hashes differently):

| Run | File | Lines | sha256 |
|---|---|---|---|
| natural | `calls/model-calls-mind-landed-2360.jsonl` | 5 | `832728c1...a93632` |
| natural | `calls/model-calls-mind-api-22828.jsonl` | 9 | `a105af02...8d440d5` |
| slack-first | `calls/model-calls-mind-landed-27696.jsonl` | 5 | `07fd41cc...1a8eb5` |
| slack-first | `calls/model-calls-mind-api-13160.jsonl` | 9 | `0f592ea3...35feb23` |

Beside each: `report.md` (`python -m mind.observability.report <run>/calls`, regenerated from
the copied files and identical to the one the run wrote) and `demo-result.txt` (the demo's
own PASS/FAIL output). Every line of all four files is `mind.model_call/1`, and every one
carries an item and a stage.

## The items

The same three inputs in both runs. Bridge sends no `source_ref` in the demo, so the `item_ref`
comes from the per-run correlation id and differs between runs.

| Item | `item_ref` natural / slack-first | Input | Tasks scored natural / slack-first |
|---|---|---|---|
| Meeting (Zoom) | `df0c4e4aa56cd405` / `306e54c513b960cb` | 67.5 s of audio; 778 chars of text for extraction | 1 (T1 dropped by Inbox) / 1 (T1 merged into Slack's) |
| Slack thread | `61957176ab79969a` / `44cc318fcaf55724` | 213 chars of text for extraction | 1 / 1 |
| Email + scanned form | `ab89e32bc4e544f1` / `6de12fe6d27d7bc8` | body + one scanned document (OCR); 1,050 chars of text for extraction | 1 / 1 |

## Per stage

Wall time of each recorded call, from the JSONL. Three-call lists are in the order meeting /
Slack / email.

| Stage | Call | Run | Wall, each (ms) | Wall total | Compute cost | Dollar cost |
|---|---|---|---|---|---|---|
| to_text | ASR `whisper-base.en-int8-local` | natural | 16,945.8 | 16.9 s | **34.28 CPU s** (RTF 0.244) | n/a |
| | | slack-first | 29,272.0 | 29.3 s | **43.80 CPU s** (RTF 0.427) -- see "ASR, 1.7x apart" | n/a |
| to_text | OCR `tesseract-psm11-ocr` | natural | 903.1 | 0.9 s | **unmeasured** (Windows reports no child-process CPU) | n/a |
| | | slack-first | 1,340.5 | 1.3 s | **unmeasured** | n/a |
| extraction | LLM `gemini-3.6-flash` | natural | 2,423.8 / 1,986.3 / 2,266.9 | 6.7 s | n/a | **$0.0132825**, all 3 priced; 15,030 tokens in / 536 out |
| | | slack-first | 2,172.7 / 1,942.0 / 1,935.2 | 6.0 s | n/a | **$0.01331625**, all 3 priced; 15,030 in / 545 out |
| duplicate_check | embedding + vector query | both | -- | -- | **not measured** | -- |
| task_created | embedding (indexing) + dependency resolution | both | -- | -- | **not measured** | -- |
| score | scoring `hierarchical-ranking` -- **degraded** | natural | 1,405.8 / 1,274.1 / 1,367.6 | 4.0 s | 0.02 CPU s (thread CPU, 15.6 ms steps on Windows) | n/a |
| | | slack-first | 1,277.1 / 1,626.1 / 1,165.4 | 4.1 s | 0.02 CPU s | n/a |
| score | Chain `/ancestors`, inside each score -- **failed** | natural | 1,398.4 / 1,271.8 / 1,365.4 | (inside scoring) | n/a | n/a |
| | | slack-first | 1,275.3 / 1,580.5 / 1,163.9 | (inside scoring) | n/a | n/a |
| score | Chain `/children`, after each score (rescore cascade) -- **failed** | natural | 1,151.2 / 1,150.2 / 1,141.7 | 3.4 s | n/a | n/a |
| | | slack-first | 1,144.1 / 1,271.5 / 1,150.0 | 3.6 s | n/a | n/a |

All recorded calls, each counted once: **32.0 s** (natural), **44.3 s** (slack-first) -- the
difference is almost all the meeting's ASR.

## Per item

| Item | Run | Span (first call start -> last call end) | Sum of calls | to_text | extraction | score (incl. `/children`) | CPU s | LLM $ |
|---|---|---|---|---|---|---|---|---|
| Meeting | natural | 25.5 s | 21.9 s | 16.9 s | 2.4 s | 2.6 s | 34.30 | $0.0047 |
| | slack-first | 34.9 s | 33.9 s | 29.3 s | 2.2 s | 2.4 s | 43.80 | $0.0047 |
| Slack | natural | 5.4 s | 4.4 s | -- | 2.0 s | 2.4 s | 0.00 | $0.0041 |
| | slack-first | 10.7 s | 4.8 s | -- | 1.9 s | 2.9 s | 0.02 | $0.0041 |
| Email | natural | 10.6 s | 5.7 s | 0.9 s | 2.3 s | 2.5 s | 0.00 + 1 unmeasured | $0.0045 |
| | slack-first | 7.8 s | 5.6 s | 1.3 s | 1.9 s | 2.3 s | 0.00 + 1 unmeasured | $0.0045 |

The span includes what records nothing: Kafka, MinIO, Inbox creating the task, the wait for
`task.created`, and -- in slack-first, where the items arrive 5 s apart -- waiting behind the
item before it. The sum includes no embedding or vector query, so it is a lower bound.

## Cost, known and unknown

Two units, never added together.

- **Dollars, measured:** LLM calls only. **$0.0132825 (natural) and $0.01331625
  (slack-first), ~$0.0044 per item**, every call priced (none unpriced), at `pricing.py`'s
  price for `gemini-3.6-flash` -- which doubles on 2027-01-01. Steady across the two runs:
  the same 15,030 input tokens, 536 vs 545 output.
- **CPU seconds, measured:** ASR 34.28 / 43.80 s; scoring 0.02 / 0.02 s. The slack-first ASR
  figure is probably an overcount (next section).
- **CPU seconds, not measured:** OCR (1 call per run, Windows); embedding and vector queries
  (not instrumented yet).
- **Not measured at all, in any unit:** the infrastructure -- Kafka, MinIO, Postgres/pgvector,
  Inbox -- and the machine itself. No dollar figure here covers them.

## What dominates

- **The meeting's ASR**, in both runs: 16.9 s of the item's 21.9 s of calls (77%) in natural,
  29.3 s of 33.9 s (86%) in slack-first, and nearly all of each run's measured CPU. Taking the
  natural run, where nothing else was running beside it: 0.244 x real time and ~0.51 CPU s
  per second of audio. Extrapolated linearly -- one 67.5 s sample, not a measurement -- the
  longest recording ASR decodes (`MAX_DURATION_S` = 600 s) would take ~2.4 min of wall time
  and ~5 CPU min. That is the stage to watch for week 8's 1,000-item target (#483) if the mix
  has much audio -- and, from slack-first, it slows when other work shares its process.
- **LLM extraction: ~1.9-2.4 s and ~$0.004-0.005 per item**, steady across both runs and all
  three items.
- **In both runs, Chain's failure costs more than scoring itself** -- see below. That is the
  demo environment's, not the full path's.

## ASR, 1.7x apart on the same recording

The same 67.5 s recording took **16.9 s / 34.3 CPU s** in natural and **29.3 s / 43.8 CPU s**
in slack-first (RTF 0.244 vs 0.427). Measured, and in the files:

- In slack-first the meeting's ASR ran from 11:58:49.8 to 11:59:19.0. Slack's task was
  scored in the API from 11:58:52.1, so Slack's `task.created` had arrived inside that window
  -- and the landed runner's listener thread, in the same process as Whisper, indexes the task
  and resolves its dependencies on that event, both with embedding calls.
- In natural nothing else was in flight: the meeting came first, and its own `task.created`
  came after its ASR.

The likely cause, **not proven**: that indexing ran beside Whisper in one process. It would
slow Whisper's wall time (shared cores), and `CpuClock` is process-wide, so the listener's
CPU is charged to the ASR call too -- the known limitation in
`src/mind/observability/README.md` ("overlapping calls in one process are each charged for
both"). How much of the 12.3 s and 9.5 CPU s it explains cannot be read off this run:
embedding is not instrumented, and other load on the laptop was not recorded. Read the
slack-first ASR figures as an upper bound, the natural ones as the uncontended case, and
treat the gap itself as a week-8 question (#483: items do arrive close together).

## Scoring: all six degraded

All three scores in each run are **degraded**: Chain could not be asked, so each is the
baseline's own score, with no ancestors in it (`hierarchy` contribution 0.0), and its latency
is the fallback path's. **These are not measurements of full scoring** and must not be used as
its baseline.

What the timings do show: of each 1.17-1.63 s score, all but a few milliseconds was the
failed `/ancestors` request -- 7.4, 2.3, 2.2 ms in natural; 1.8, 45.6, 1.5 ms in slack-first
(the 45.6 ms is the API's first score of its run). The scoring model itself is a few
milliseconds; the full path's cost is whatever a live Chain adds, which these runs cannot say.

## Open issue for R3: the 0.2 s Chain timeout does not bound a DNS failure

**Recorded here, not changed** -- scoring logic and timeouts are R3's (#474), and outside this
baseline.

- `ChainHierarchyClient` sets `DEFAULT_TIMEOUT_SECONDS = 0.2` "to fail fast on a struggling
  one". In both runs every request failed with `ConnectError: [Errno 11001] getaddrinfo
  failed` after **1.14-1.58 s** -- 12 of 12, i.e. 6-8x the timeout.
- The name lookup happens before the connection, and httpx's timeout does not cover it, so a
  name that does not resolve costs whatever the resolver takes.
- Each task paid ~2.3-2.9 s for it: `/ancestors` inside the score, then `/children` for the
  rescore cascade, one after the other on the thread that handles the task's event.
- In the cluster `resonance-flow-chain` resolves, so this does not happen there while Chain's
  Service exists. It does wherever Chain's name is missing: the demo, local runs, a
  misconfigured `MIND_CHAIN__BASE_URL`.
- For R3 to decide: whether a failed lookup should be bounded too (or Chain's absence noticed
  once rather than per request). Not started in #482.

## Known limitations

- **One run per order.** No variance, no p95 worth the name: three calls per stage at most,
  and the one stage seen twice under different conditions (ASR) differs by 1.7x.
- **Windows laptop, not the cluster.** No container CPU limits; OCR CPU unmeasured; thread CPU
  in 15.6 ms steps; other load on the machine not recorded.
- **Embedding and vector queries not measured** -- waiting for R3's approval of the
  instrumentation (`embeddings.py`, `pgvector_store.py`). Their absence is a gap, not a zero:
  `duplicate_check` and `task_created` show no calls at all, and the listener's work that
  likely slowed the slack-first ASR is invisible.
- **Scoring degraded throughout** (above).
- **Demo correctness is 1/6 and 4/6** (`demo_flow/README.md`): in natural one of the meeting's
  tasks is never created, so never indexed or scored; in neither run is a dependency published.
- **Rescores not attributed.** None happened in these runs; a re-score from the rescore worker
  would carry no item or task.
- **Warm processes.** Whisper is loaded at start-up (`asr.warm_up()`), so model loading is not
  in these numbers; the embedding model's first load is in no number at all.

## Reproduce

From `teams/mind`, with Docker and `demo_flow/stack/secrets.yaml`:

```bash
python -m demo_flow.run --order natural
python -m demo_flow.run --order slack-first
python -m mind.observability.report demo_flow/runs/<run-id>/calls
```

The demo writes `runs/<run-id>/report.md` itself. To re-read this baseline:
`python -m mind.observability.report benchmarks/pipeline-baseline-482/<run-id>/calls`.
