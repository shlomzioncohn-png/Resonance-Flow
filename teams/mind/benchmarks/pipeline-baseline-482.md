# Full-flow latency and cost baseline (#482) -- PRELIMINARY

> **Preliminary, single-run baseline. Not complete, not representative.** One run of the
> #359 demo, natural order, three items, on one Windows laptop, with no Chain. Embedding and
> vector queries are **not measured** (not zero), and **every score is degraded** (Chain was
> unreachable), so the scoring numbers are not the full scoring path's. Read every number
> below with those limits. Still pending before this can serve as week 8's baseline: a
> slack-first run, R3's embedding/vector-query instrumentation, and more than one run per order.

## The run

| | |
|---|---|
| Run id | `20261026T110028Z-natural` (`python -m demo_flow.run --order natural`, gap 90 s) |
| When | 2026-10-26, items fed 11:02:43-11:05:43 UTC |
| Mind code | branch `michal-brecher/serving-under-flow-482` at `7d902f66` (on `main` `29aaca0f`); the demo runner and report as committed in `72d7b26d` |
| Machine | Windows 11 Pro, Intel Core i7-13620H (10 cores, 16 threads), 15.7 GB RAM, Python 3.12.4, Docker 29.8.2; Mind ran as two local processes, the stack in Docker |
| Models | ASR `whisper-base.en-int8-local`; OCR `tesseract-psm11-ocr`; LLM `gemini-3.6-flash` (paid key) |
| Chain | **not running**: `MIND_CHAIN__BASE_URL` unset, so the default `http://resonance-flow-chain:80`, which does not resolve on this machine |
| Demo result | 1/6 criteria -- as documented for the natural order (`demo_flow/README.md`, finding 1: Inbox keeps one task per `source_ref`, so the meeting's T1 is never created). Not caused by the monitoring |

Raw data, kept as recorded: `pipeline-baseline-482/20261026T110028Z-natural/` (sha256 of
the files as committed, LF line endings; a checkout that converts to CRLF hashes differently)

- `calls/model-calls-mind-landed-2360.jsonl` (5 lines, sha256 `832728c1...a93632`)
- `calls/model-calls-mind-api-22828.jsonl` (9 lines, sha256 `a105af02...8d440d5`)
- `report.md` -- `python -m mind.observability.report <that>/calls`; regenerated from the
  copied files, identical to the one the run wrote
- `demo-result.txt` -- the demo's own PASS/FAIL output

## The items

| Item | `item_ref` | Input | Tasks scored |
|---|---|---|---|
| Meeting (Zoom) | `df0c4e4aa56cd405` | 67.5 s of audio; 778 chars of text for extraction | 1 (the second, T1, was dropped by Inbox -- finding 1) |
| Slack thread | `61957176ab79969a` | 213 chars of text for extraction | 1 |
| Email + scanned form | `ab89e32bc4e544f1` | body + one scanned document (OCR); 1,050 chars of text for extraction | 1 |

## Per stage

Wall time of each recorded call, from the JSONL.

| Stage | Call | Calls | Wall, each (ms) | Wall total | Compute cost | Dollar cost |
|---|---|---|---|---|---|---|
| to_text | ASR `whisper-base.en-int8-local` | 1 | 16,945.8 | 16.9 s | **34.28 CPU s** (RTF 0.244 on 67.5 s of audio) | n/a |
| to_text | OCR `tesseract-psm11-ocr` | 1 | 903.1 | 0.9 s | **unmeasured** (Windows reports no child-process CPU) | n/a |
| extraction | LLM `gemini-3.6-flash` | 3 | 2,423.8 / 1,986.3 / 2,266.9 | 6.7 s | n/a | **$0.0132825**, all 3 priced; 15,030 tokens in / 536 out |
| duplicate_check | embedding + vector query | -- | -- | -- | **not measured** | -- |
| task_created | embedding (indexing) + dependency resolution | -- | -- | -- | **not measured** | -- |
| score | scoring `hierarchical-ranking` -- **degraded** | 3 | 1,405.8 / 1,274.1 / 1,367.6 | 4.0 s | 0.02 CPU s (thread CPU, 15.6 ms steps on Windows) | n/a |
| score | Chain `/ancestors`, inside each score -- **failed** | 3 | 1,398.4 / 1,271.8 / 1,365.4 | (inside scoring) | n/a | n/a |
| score | Chain `/children`, after each score (rescore cascade) -- **failed** | 3 | 1,151.2 / 1,150.2 / 1,141.7 | 3.4 s | n/a | n/a |

All recorded calls, each counted once: **32.0 s** of wall time.

## Per item

| Item | Span (first call start -> last call end) | Sum of calls | to_text | extraction | score (incl. `/children`) | CPU s | LLM $ |
|---|---|---|---|---|---|---|---|
| Meeting | 25.5 s | 21.9 s | 16.9 s | 2.4 s | 2.6 s | 34.30 | $0.0047 |
| Slack | 5.4 s | 4.4 s | -- | 2.0 s | 2.4 s | 0.00 | $0.0041 |
| Email | 10.6 s | 5.7 s | 0.9 s | 2.3 s | 2.5 s | 0.00 + 1 unmeasured | $0.0045 |

The span includes what records nothing: Kafka, MinIO, Inbox creating the task, and the wait
for `task.created`. The sum includes no embedding or vector query, so it is a lower bound.

## Cost, known and unknown

Two units, never added together.

- **Dollars, measured:** LLM calls only. **$0.0132825 for the run, ~$0.0044 per item**, every
  call priced (none unpriced), at `pricing.py`'s price for `gemini-3.6-flash` -- which
  doubles on 2027-01-01.
- **CPU seconds, measured:** ASR 34.28 s; scoring 0.02 s.
- **CPU seconds, not measured:** OCR (1 call, Windows); embedding and vector queries (not
  instrumented yet).
- **Not measured at all, in any unit:** the infrastructure -- Kafka, MinIO, Postgres/pgvector,
  Inbox -- and the machine itself. No dollar figure here covers them.

## What dominates

- **The meeting's ASR: 16.9 s of the item's 21.9 s of calls (77%), and 34.28 of the run's
  34.30 measured CPU seconds.** On this machine Whisper base.en ran at 0.244 x real time and
  ~0.51 CPU s per second of audio. Extrapolated linearly -- one 67.5 s sample, not a
  measurement -- the longest recording ASR decodes (`MAX_DURATION_S` = 600 s) would take
  ~2.4 min of wall time and ~5 CPU min. That is the stage to watch for week 8's 1,000-item
  target (#483) if the mix has much audio.
- **LLM extraction: ~2.0-2.4 s and ~$0.004-0.005 per item**, steady across the three items.
- **In this run, Chain's failure costs more than scoring itself** -- see below. That is the
  demo environment's, not the full path's.

## Scoring: all three degraded

All three scores are **degraded**: Chain could not be asked, so each is the baseline's own
score, with no ancestors in it (`hierarchy` contribution 0.0), and its latency is the fallback
path's. **These are not measurements of full scoring** and must not be used as its baseline.

What the timings do show: of each 1.27-1.41 s score, all but 2-7 ms (1,405.8 - 1,398.4 =
7.4 ms; 2.3 ms; 2.2 ms) was the failed `/ancestors` request. The scoring model itself is a few
milliseconds; the full path's cost is whatever a live Chain adds, which this run cannot say.

## Open issue for R3: the 0.2 s Chain timeout does not bound a DNS failure

**Recorded here, not changed** -- scoring logic and timeouts are R3's (#474), and outside this
baseline.

- `ChainHierarchyClient` sets `DEFAULT_TIMEOUT_SECONDS = 0.2` "to fail fast on a struggling
  one". In this run every request failed with `ConnectError: [Errno 11001] getaddrinfo
  failed` after **1.14-1.40 s** -- 6 of 6, i.e. 6-7x the timeout.
- The name lookup happens before the connection, and httpx's timeout does not cover it, so a
  name that does not resolve costs whatever the resolver takes.
- Each task paid ~2.5 s for it: `/ancestors` inside the score, then `/children` for the
  rescore cascade, one after the other on the thread that handles the task's event.
- In the cluster `resonance-flow-chain` resolves, so this does not happen there while Chain's
  Service exists. It does wherever Chain's name is missing: the demo, local runs, a
  misconfigured `MIND_CHAIN__BASE_URL`.
- For R3 to decide: whether a failed lookup should be bounded too (or Chain's absence noticed
  once rather than per request). Not started in #482.

## Known limitations

- **One run.** No variance, no p95 worth the name: three calls per stage at most.
- **One order.** The slack-first run (which merges the meeting's T1 into Slack's, and so
  scores differently) is not measured yet.
- **Windows laptop, not the cluster.** No container CPU limits; OCR CPU unmeasured; thread CPU
  in 15.6 ms steps.
- **Embedding and vector queries not measured** -- waiting for R3's approval of the
  instrumentation (`embeddings.py`, `pgvector_store.py`). Their absence is a gap, not a zero:
  `duplicate_check` and `task_created` show no calls at all.
- **Scoring degraded throughout** (above).
- **Demo correctness is 1/6** (finding 1 in `demo_flow/README.md`): one of the meeting's two
  tasks is never created by Inbox, so it is never indexed or scored.
- **Rescores not attributed.** None happened in this run; a re-score from the rescore worker
  would carry no item or task.
- **Warm processes.** Whisper is loaded at start-up (`asr.warm_up()`), so model loading is not
  in these numbers; the embedding model's first load is in no number at all.

## Reproduce

From `teams/mind`, with Docker and `demo_flow/stack/secrets.yaml`:

```bash
python -m demo_flow.run --order natural
python -m mind.observability.report demo_flow/runs/<run-id>/calls
```

The demo writes `runs/<run-id>/report.md` itself. To re-read this baseline:
`python -m mind.observability.report benchmarks/pipeline-baseline-482/20261026T110028Z-natural/calls`.
