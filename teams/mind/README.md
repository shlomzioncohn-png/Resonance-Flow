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
│   └── API.md          # the API contract shared with other teams
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

## Data policy

**No real Gong data at any point.** Real corpora (AMI, RVL-CDIP/FUNSD,
Enron, SAMSum, ICSI/QMSum/MeetingBank) are used for training and
measurement only; anything shown in a live demo is synthetic. CRM data is
synthetic by construction. See `eval/datasets/README.md` for per-source
licensing constraints.

## Open decisions (track and update as they close)

- Vector store: pgvector / Qdrant / OpenSearch k-NN / Milvus / Chroma+FAISS
- OCR/ASR: managed API vs. local model (e.g. local Whisper)
- Evaluation metric: top-1 accuracy vs. MRR/nDCG
- How the score is published to Chain: separate event vs. field on `Task`
- Which real dataset slice to validate against first: FUNSD vs. AMI
- MIND's exact location/build integration in the monorepo (Python, not Maven — affects Relay's CI and the root `docker-compose.yml`)

## Getting started

```bash
cp .env.example .env       # fill in as decisions close
make dev                   # install with dev dependencies
make test                  # run the test suite
make run                   # run the API locally
make docker-up             # or run everything via docker compose
```

Once running, `curl http://localhost:8000/health` should return `200` with
`{"status": "ok", ...}`. Full API contract (endpoints, error format,
versioning plan) is in [`docs/API.md`](./docs/API.md) — that's the document
shared with other teams, in particular Relay.

## Foundation Gate checklist (end of week 3)

- [ ] Harness and vector store run on toy data **and** on one small real
      dataset slice (e.g. a handful of AMI recordings or a few scanned
      FUNSD/RVL-CDIP pages)
- [ ] Both are runnable by someone outside the MIND team
- [ ] Persona evaluation set complete and mentor-approved
- [ ] Vector-store choice documented and reasoned
- [ ] Automated tests passing; a README per component; one-command deploy
