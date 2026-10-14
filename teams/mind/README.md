# MIND

**Team:** MIND | **Project:** Resonance Flow | **Program:** KamaTech × Gong Bootcamp

MIND is one of six teams building **Resonance Flow**, a work-prioritization
platform that answers one question: *"I have X minutes free right now —
what's the most important thing I can do?"*

> **MIND detects and scores. MIND does not decide.**
>
> Our output is a proposal — a scored task, a suggested dependency edge,
> tagged with a confidence level. Chain decides what actually gets done;
> Lens aggregates for managers. See the full context in
> [`MIND-01-System-Overview.md`](./MIND-01-System-Overview.md) and
> [`MIND-02-Roles.md`](./MIND-02-Roles.md) (add these docs here, or link to
> wherever the team keeps them in this repo).

## Mission

- **Phase 1 (weeks 1-3):** evaluation-harness scaffolding + vector-store
  infrastructure. We are not building models yet — we're building the
  infrastructure that will measure them.
- **Phase 2 (weeks 4-9):** ML — extraction, classification, embeddings-based
  search, and segmented ranking/scoring.

## Repo layout

```
teams/mind/
├── src/mind/
│   ├── ingestion/      # Bridge intake, corpus loading
│   ├── multimodal/     # local OCR + ASR
│   ├── extraction/     # LLM extraction + classification
│   ├── embeddings/     # vectors, search, duplicates
│   ├── scoring/        # segmented ranking + propagation
│   ├── evaluation/     # harness + metrics
│   ├── api/            # outward service boundary (FastAPI app, /health)
│   ├── contracts/      # Pydantic models + the standard error format
│   └── observability/  # record_call() — tracks time/cost of every model call
├── docs/
│   ├── API.md            # the API contract shared with other teams
│   └── relay-handoff.md  # what Relay needs to build/run/monitor the image
├── db/
│   └── init/            # vector-store (pgvector) bootstrap SQL, runs on first startup
├── eval/
│   ├── personas/       # the fixed scenario set
│   └── datasets/       # manifests + checksums only, never the corpora
├── benchmarks/         # comparison outputs (vector stores, local models)
├── tests/
├── models/             # local model weights — gitignored, never committed
├── pyproject.toml
├── Dockerfile
├── .dockerignore
├── docker-compose.yml
├── Makefile
├── .env.example
└── .gitignore
```

Each module under `src/mind/` has its own `README.md` describing its scope
and API boundary.

## Interfaces with the other teams

| With | What crosses | Direction |
|---|---|---|
| **Bridge** | Raw content landed in object storage + a pointer event | them → us |
| **Inbox** | The canonical `Task` schema our output conforms to; we publish enriched events | both ways |
| **Chain** | The per-task score — the exact API boundary | both ways |
| **Lens** | Classification signals for manager-facing views | us → them |
| **Relay** | Our image deploys on their platform; event bus | them → us |

## Continuous integration

[`.github/workflows/mind-ci.yml`](../../.github/workflows/mind-ci.yml) runs on every PR that touches `teams/mind/**`.

**Ownership split:** Relay owns the CI/CD *infrastructure* — the GitHub Actions
setup, runners, and the general pattern every team's workflow follows. MIND
owns the *content* of its own job: which `make` targets run, in what order,
and what counts as a failure. Changes to what the MIND job actually checks
(lint rules, test coverage, which persona set the harness runs against) are
MIND's call; changes to the shared CI platform itself go through Relay.

The job mirrors local development exactly — every step is a `make` target,
the same ones you'd run yourself:

```
make dev    # install
make lint   # ruff + mypy
make test   # pytest --cov=src/mind
make eval   # harness regression check against eval/personas/
```

`make eval` is not a smoke test against a placeholder — it validates and runs
the real `eval/personas/` scenario set (see `eval/personas/README.md`). A
broken scenario (a schema violation, a dangling `expected_top_task_id`, a
duplicate `scenario_id`, ...) fails the build. A role nobody has written a
scenario for yet, or a scenario the mentor hasn't approved yet, prints a
warning but does **not** fail the build — the set is expected to be built up
incrementally before the Foundation Gate.

## Cost and latency monitoring (#479)

Every OCR, ASR and LLM call goes through one ledger,
`observability.record_call()`, which logs its latency and its cost -- in a
unit per kind: **CPU seconds** for OCR/ASR (MIND does not pay for its machine
by the hour, so local models have no dollar price), **US dollars** for LLM
calls (the provider's price per token), plus the LLM's token usage. Every
field and log key names its unit (`cpu_seconds`, `cost_usd`, `duration_ms`).
`GET /debug/model-calls` (and a `model_call_summary` log line every 100 calls)
shows the aggregate per call name -- count, errors, p50/p95, CPU seconds,
tokens, dollars -- and a call over its kind's duration limit (or, for LLM, its
dollar limit) logs a `MODEL_CALL_SLOW` / `MODEL_CALL_EXPENSIVE` WARNING. A
measure that does not apply to a kind is `n/a` (null total, null count); one
that applies but is unknown is never shown as 0 -- its total is `null`, with a
count of how many calls lacked it.

LLM calls are recorded in R2's `BasicExtractor._call`, with her agreement.
Gemini has no price until her `yonit/mind-gemini-pricing` branch lands, so its
calls are recorded as unpriced in the meantime.

Metrics, limits, configuration, warnings and known limitations:
[`src/mind/observability/README.md`](./src/mind/observability/README.md#status-of-479-cost-and-latency-monitoring).
Run the acceptance suite with `pytest tests/test_cost_latency_monitoring.py -v`.

## Data policy

**No real Gong data at any point.** Real corpora (AMI, RVL-CDIP/FUNSD,
Enron, SAMSum, ICSI/QMSum/MeetingBank) are used for training and
measurement only; anything shown in a live demo is synthetic. CRM data is
synthetic by construction. See `eval/datasets/README.md` for per-source
licensing constraints.

## Open decisions (track and update as they close)

- ~~OCR: managed API vs. local model~~ Resolved: **local Tesseract** (`--psm 11`),
  chosen over PaddleOCR with the mentor on latency and memory (~0.5s / ~34MB vs.
  ~45s / ~600MB per page). A trade made knowingly — PaddleOCR mobile has ~2.7x
  fewer character errors on FUNSD (grouped CER 0.074 vs. 0.200). See
  [`benchmarks/ocr-asr-benchmark.md`](./benchmarks/ocr-asr-benchmark.md).
- ~~ASR: which local Whisper size~~ Resolved: **Whisper `base.en`** (faster-whisper,
  int8, CPU), chosen over `tiny.en` / `small.en` on two AMI meetings: rough WER
  ~0.26 at ~10x real time, vs. `small.en` 0.9–1.6 points better but ~5x slower,
  and `tiny.en` within noise on WER but less precise. n=2 and a rough word
  diff — known risks documented in
  [`benchmarks/ocr-asr-benchmark.md`](./benchmarks/ocr-asr-benchmark.md).
- Evaluation metric: top-1 accuracy vs. MRR/nDCG
- How the score is published to Chain: separate event vs. field on `Task`
- Which real dataset slice to validate against first: FUNSD vs. AMI
- MIND's exact location/build integration in the monorepo (Python, not Maven — affects Relay's CI and the root `docker-compose.yml`)
- ~~Compute cost of OCR/ASR in dollars~~ Resolved (#479, with the team lead):
  MIND does not pay for its machine, so OCR/ASR cost is measured in **CPU
  seconds**, not dollars; dollars apply only to LLM calls. No CPU-hour rate.
  A CPU-seconds limit for OCR/ASR waits for a measured baseline.
- ~~LLM call instrumentation (#479)~~ Resolved with R2: `extractor.py` records
  `kind`, status, token counts and an unknown cost as `None`. Gemini's price is
  on her `yonit/mind-gemini-pricing` branch.

## Getting started

```bash
cp .env.example .env             # already filled with working defaults today
make dev                         # install with dev dependencies
make lint                        # ruff + mypy
docker compose up vector-store -d # bring up just the DB -- make test/make run need it
make migrate                     # apply database migrations (alembic upgrade head)
make test                        # run the test suite
make eval                        # run the evaluation harness against eval/personas/
make run                         # run the API locally
make docker-up                   # or run everything via docker compose (API + vector-store)
```

`make test` and `make run` connect to Postgres over `MIND_VECTOR_STORE__DSN`
(already set in `.env.example` against `localhost:5432`), so the
`vector-store` container above must be up first even when you're running
`mind` itself outside Docker. `make migrate` must then run once against a
fresh database -- the app itself no longer creates its own schema (see
[`migrations/`](./migrations) and issue #441); it only checks the schema
is already there, and fails fast with a clear message if it isn't.
`make docker-up` doesn't need either step -- it brings up `vector-store`
and runs the migration itself.

`make docker-up` (`docker compose up`) now brings up the full stack —
migrations, the API, **and** its `vector-store` (pgvector) dependency —
with one command: `vector-store` becomes healthy, a one-shot `migrate`
service runs against it and exits, then `mind` starts. On a clean
checkout: `docker compose down -v && docker compose up -d --build`.

Once running, `curl -f http://localhost:8000/health` should return `200`
with `{"status": "ok", ...}`, and `docker compose ps` should show both
services as `(healthy)`. Full API contract (endpoints, error format,
versioning plan) is in [`docs/API.md`](./docs/API.md) — that's the
document shared with other teams. Everything Relay specifically needs to
build, run, and monitor the image (ports, env vars, health check
decoding) is in [`docs/relay-handoff.md`](./docs/relay-handoff.md).

CI ([`mind-ci.yml`](../../.github/workflows/mind-ci.yml)) runs `make dev` /
`make lint` / `make test` plus a harness smoke check on every PR — see
[Continuous integration](#continuous-integration) below for what each one
checks and who owns what.

## Foundation Gate checklist (end of week 3)

- [ ] Harness and vector store run on toy data **and** on one small real
      dataset slice (e.g. a handful of AMI recordings or a few scanned
      FUNSD/RVL-CDIP pages)
- [ ] Both are runnable by someone outside the MIND team
- [ ] Persona evaluation set complete and mentor-approved
- [ ] Vector-store choice documented and reasoned
- [ ] Automated tests passing; a README per component; one-command deploy
