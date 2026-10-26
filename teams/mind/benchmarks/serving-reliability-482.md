# Does the serving layer hold under the flow? (#482)

Two measurements on one Windows laptop, 2026-10-26, branch `michal-brecher/serving-under-flow-482`
at `192c6426`: Mind's longest job (ASR at and past its cap, three times each in one process, as
the landed runner would), and Mind's API -- the one process Relay's chart deploys -- under
concurrent requests with `/health` probed throughout. Every measured value is in the tables
below; the raw JSON is not kept (this directory's convention: READMEs, not raw dumps) --
reproduce it with `benchmarks/serving_reliability.py` (its docstring says how).

> **Laptop numbers, not the cluster's.** Intel i7-13620H, 16 logical CPUs, no CPU or memory
> limits, Windows. Where a conclusion is about the cluster, it is marked as an estimate.

## Summary

| Question (#482) | Answer from these runs |
|---|---|
| Does a long ASR job finish? | **Yes.** 600 s of audio, 6 of 6 calls, none hit the 240 s timeout; worst 117.6 s |
| Is a recording past the cap handled? | **Yes.** 900 s recordings were transcribed to 600 s and came back `truncated`, `capped` |
| Does memory grow over repeated jobs? | **No.** 250.6-257.1 MB after each of 6 calls |
| Does the API survive concurrent requests? | **Yes.** 32 clients for 30 s: 0 errors, still running |
| Does `/health` stay truthful under load? | **It stays fast and correct** (max 26.6 ms at 32 clients, never over 1 s). It does **not** report the score publisher -- the contract today, see below |
| Anything at risk? | **The API's memory: 453 MB idle against a 512Mi limit**, and an estimated ASR timeout if the landed runner is ever deployed with the chart's default CPU limit |

## ASR: the longest job (`serving_reliability.py asr-long`)

Whisper `base.en` (int8), the demo meeting tiled to length, `MAX_DURATION_S` = 600,
`TRANSCRIBE_TIMEOUT_S` = 240. Loading the model: 1.7 s, and the process from 38 to 213 MB.

| Recording | Run | Wall | CPU s | RTF | Transcribed | Flags | RSS after | Peak RSS |
|---|---|---|---|---|---|---|---|---|
| 600 s | 1 | 107.6 s | 295.9 | 0.179 | 600 s | -- | 257.0 MB | 394.7 MB |
| 600 s | 2 | 50.1 s | 169.8 | 0.084 | 600 s | -- | 257.1 MB | 394.9 MB |
| 600 s | 3 | 45.5 s | 174.5 | 0.076 | 600 s | -- | 257.0 MB | 394.9 MB |
| 900 s | 1 | 59.5 s | 201.9 | 0.099 | 600 s | truncated, capped | 250.6 MB | 432.7 MB |
| 900 s | 2 | 117.6 s | 312.6 | 0.196 | 600 s | truncated, capped | 256.2 MB | 432.7 MB |
| 900 s | 3 | 115.7 s | 309.1 | 0.193 | 600 s | truncated, capped | 256.3 MB | 432.7 MB |

- **Survives:** every call succeeded, 144 segments each; none timed out -- the worst used 49% of
  the 240 s budget. The landed runner's Kafka poll interval is 30 minutes, far above it.
- **The cap works as designed:** a 900 s recording is transcribed to 600 s and says so. Its peak
  is ~38 MB higher (the longer file is read whole), and it is released afterwards.
- **No memory growth:** RSS after each call stays within 250.6-257.1 MB across all six.
- **Wide variance on identical work:** 45.5-117.6 s of wall and 169.8-312.6 CPU s for the same
  600 s. The CPU seconds vary too, so it is not only waiting -- most likely the laptop's
  power/thermal state, which was not controlled. **Every laptop timing in #482, the demo
  baseline's included, carries this ~2x noise.** 0.28-0.52 CPU s per second of audio.

**Estimate for the cluster -- not measured.** The landed runner is not in Relay's chart today.
Deployed with the chart's default limits (`cpu: 500m`, `memory: 512Mi`), the 170-313 CPU s a
600 s recording took here would need ~340-630 s of wall time at half a core: past the 240 s
timeout, so the transcript would come back truncated. `TRANSCRIBE_TIMEOUT_S` was sized on a
laptop (asr.py says so). Its memory -- 433 MB peak for ASR alone, before the embedding model
the same process loads -- would not fit 512Mi either. For whoever deploys the runner (week 8).

## The API under concurrent requests (`serving_reliability.py api-load --concurrency 8 / 32`)

`uvicorn mind.api.main:app` as the Dockerfile runs it, against a disposable pgvector with 200
indexed tasks, **no Kafka** (so the score publisher did not start). Clients call
`/v1/tasks/{id}/related` for 30 s; a prober on its own thread and connection calls `/health`
every 250 ms, as the kubelet would.

| | 8 clients | 32 clients |
|---|---|---|
| `/related` requests, errors | 2,511, 0 | 1,653, 0 |
| `/related` throughput | 83.7 /s | 55.1 /s |
| `/related` p50 / p95 / max | 85 / 129 / 495 ms | 353 / 1,810 / 4,289 ms |
| `/health` idle p50 / p95 / max | 4.3 / 10.0 / 26.3 ms | 4.1 / 7.1 / 7.5 ms |
| `/health` under load p50 / p95 / max | 10.2 / 20.3 / 326 ms | 5.1 / 12.3 / 26.6 ms |
| `/health` over 1 s, failed | 0 of 109, 0 | 0 of 112, 0 |
| API memory idle / max under load | 453.7 / 458.6 MB | 453.0 / 459.8 MB |
| Ready after start | 21.6 s | 21.9 s |

- **Survives:** no errors, still running after both.
- **`/health` holds:** the endpoints are async and DB-bound, so the event loop is never held;
  `/health` answered in milliseconds under both loads, far inside the 1 s probe timeout.
  (A first version of the script probed from the load generator's own event loop and saw
  0.7-2 s -- the client's queue, not the API's; it was rewritten and those numbers dropped.)
- **`/related` saturates:** more clients gave *less* throughput (84 -> 55 /s) and a 1.8 s p95.
  Not diagnosed -- the connection pool, the single process, or the load generator sharing the
  machine. It is R3's endpoint and #475 is her latency target: reported to her, not changed.
- **Start-up 22 s** is well inside the startup probe's 200 s (40 x 5 s).

### The API's memory against its limit

**453 MB idle, against `memory: 512Mi` (537 MB) in Relay's chart: 85% before the first
request**, 86% under load. Where it comes from, importing the API in a fresh process:

| After importing | RSS |
|---|---|
| Python | 17.8 MB |
| + fastapi, pydantic | 42.2 MB |
| + torch | 210.0 MB |
| + sentence_transformers | 424.9 MB |
| + `mind.api.main` | 447.6 MB |

**~380 MB of it is torch and sentence-transformers, imported because `mind.embeddings` imports
`embeddings.py`, whose module level imports `SentenceTransformer` -- yet the API never loads a
model:** `/related` reads stored vectors, and scoring needs no embeddings. Measured on Windows;
a Linux container's figure was not measured and may differ (`kubectl top` would say). The
score publisher, which this run did not start, adds its own on top.

**A cheap win, confirmed by measurement, not applied:** importing `sentence_transformers` only
when a model is first loaded (inside `_get_model()`) would take that ~380 MB out of every
process that never embeds -- the API above all. It is R3's file (`embeddings/embeddings.py`), so
it is proposed to her alongside the instrumentation patch.

## `/health`: what it reports (tests: `tests/api/test_health_under_load.py`)

- **Stays truthful under load:** answers within milliseconds while 50 slow requests are in
  flight and while a publisher thread scores in pure Python for seconds; never touches the
  vector store (a database blip must not restart the pod); 503 before start-up completes and
  once shutdown begins.
- **Does not report the score publisher**, and a test pins that as today's contract: a dead or
  retrying publisher thread leaves `/health` at 200. `RunningScorePublisher.healthy` and
  `.retrying` exist (#920) but nothing reads them. Reporting them on the one path Relay uses
  for startup, liveness *and* readiness would restart the pod for a publisher problem, not just
  take it out of rotation. **Proposal, not done:** `/health/live` (the process answers) and
  `/health/ready` (start-up done, publisher healthy), with Relay pointing each probe at its own.
  A change to Relay's chart, so for the team and Relay to decide.
- **Not measured here:** the publisher running under load inside the API (no Kafka in
  `api-load`); a long score in a publisher thread is covered by the test only.

## For week 8

1. **API memory headroom** (Relay, R3): 453 MB of 512Mi idle; the lazy import above is the cheap
   fix. Check the real pod's usage in the cluster.
2. **ASR on a deployed runner** (whoever deploys it, #483): at the default 500m CPU a 10-minute
   recording would exceed `TRANSCRIBE_TIMEOUT_S` (estimate). Size CPU, or the timeout, for it.
3. **`/related` saturation** (R3, #475): throughput falls from 8 to 32 clients.
4. **`/health/live` + `/health/ready`** (Relay): if the publisher's health should reach the probes.
5. **Laptop noise:** ~2x on identical ASR work. Week 8's targets need several runs, or a machine
   whose clock is pinned.
