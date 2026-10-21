# api

Outward service boundary — the only part of MIND other teams talk to.

- Inbox: publishes enriched task events; conforms to their canonical schema.
- Chain: publishes the per-task score (the exact API boundary); consumes their traversal API in week 7.
- Lens: publishes classification signals for manager-facing views.
- Relay: this image deploys on their platform; publish/consume on their event bus.

`main.py` is the FastAPI app and currently exposes `GET /health` for
Relay's liveness monitoring. Every route here uses the standard error
format from `contracts/errors.py`. Full contract (endpoints, error
format, versioning plan) is documented in `docs/API.md` — that's the
document to share with other teams, not this README.

`v1/` holds `/v1`-prefixed business routes (search/score once Phase 2
lands) — see `v1/README.md`. `/health` deliberately stays here in
`main.py`, outside `v1/`: infrastructure endpoints aren't versioned.

## Configuration (#481)

MIND reads its connection settings from two files, never from hardcoded
hosts or env vars. In the cluster, Relay's Helm chart mounts both and sets
the two variables naming them. One loader, `mind/config_file.py`, reads
them for the whole service.

| File | Named by | Holds | Required? |
|---|---|---|---|
| `config.yaml` | `CONFIG_FILE_PATH` | Non-secret settings (a ConfigMap in the cluster) | **Yes** |
| `secrets.yaml` | `SECRETS_FILE_PATH` | Credentials (a Secret in the cluster) | No: the chart mounts its Secret `optional: true` |

### Which settings come from the files

Key names are the chart's (`teams/relay/helm/resonance-flow/README.md`,
"MIND (`components.mind`)").

| Key | File | Read by | Notes |
|---|---|---|---|
| `vector_store.url`, `.user`, `.db` | config.yaml | `mind.config.Settings` | All three required. |
| `vector_store.password` | secrets.yaml | `mind.config.Settings` | Required. Never taken from config.yaml. |
| `service.log_level` | config.yaml | `Settings.log_level` | Optional; the API applies it at start-up. `MIND_LOG_LEVEL` is only a fallback. |
| `asr.whisper_model` | config.yaml | `ASRSettings` | Optional; `WHISPER_MODEL` is only a fallback, then `base.en` (#135). |
| `kafka.bootstrap_servers`, `.schema_registry_url`, `.producer.acks`, `.producer.retries` | config.yaml | `mind.messaging.config` | |
| `kafka.sasl.mechanism`, `.username`, `.password` | secrets.yaml | `mind.messaging.config` | Absent: no SASL. |

The vector-store DSN is built **only** from these files. A
`MIND_VECTOR_STORE__DSN` in the environment or `.env` is ignored. Tuning
knobs that aren't connection settings still come from env vars:
`MIND_ENVIRONMENT`, the `MIND_*_THRESHOLD`s, `MIND_VECTOR_STORE__TABLE` /
`__SEARCH_EFFORT`, `MIND_MONITORING__*` and `MODEL_CACHE_DIR`.

**Not aligned yet:**

- **LLM (`llm.*`).** The LLM reader keeps its pre-#481 lenient reading: a
  missing file is not an error, and it falls back to the chart's mount
  paths. Its keys don't match what the chart renders today (`llm.model`,
  `llm.cache_dir`, and a key in the external `resonance-flow-mind-llm`
  Secret). This is waiting on Relay to confirm the contract.
- **Object storage (`object_storage.*`, #349).** `ObjectStorageSettings`
  keeps the behaviour it landed with: env vars win over the file, there are
  local defaults, and missing files are lenient. Aligning it waits on
  coordination with #349's author.
- **Read by no code yet:** `ocr.provider`, `asr.provider`, and
  `kafka.producer.task_creation_requested_topic` (the topic name is fixed
  in `task_creation/send.py`).

### What happens when they're missing

Start-up stops with a `mind.config_file.ConfigFileError` that names the
problem. There is no fallback to a default path or to localhost. This
applies to the API, `alembic`, and the runners: `index_consumer_runner`
and `score_publisher_runner` at their first line, and
`landed_consumer_runner` when it builds its Kafka consumer.

| Situation | Result |
|---|---|
| `CONFIG_FILE_PATH` unset or blank | `$CONFIG_FILE_PATH is not set ...` |
| The file doesn't exist, or is a directory | `<path> (from $CONFIG_FILE_PATH) does not exist` / `is a directory` |
| Unreadable, not valid YAML, empty, or not a mapping | `cannot read ...` / `is not valid YAML` / `is empty` / `must be a YAML mapping` |
| `vector_store.url`/`.user`/`.db` missing | `vector_store.<key> is missing from config.yaml ($CONFIG_FILE_PATH)` |
| `vector_store.password` missing (or no secrets file) | `vector_store.password is missing from secrets.yaml ... resonance-flow-mind-secret Secret` |
| `SECRETS_FILE_PATH` unset, or its file absent or empty | Not an error: no secrets. Only a setting that needs one fails, naming it. |
| A secrets file that exists but is broken | Fails, the same way as config.yaml. |

The API's `GET /health` returns `200` only after start-up has completed
(config loaded, vector store opened and migrated), and `503` before that
or once shutdown begins. It doesn't re-check the database per request,
because the same path is the chart's liveness probe. See `docs/API.md`.

### Running locally

The committed example files hold placeholders only:

| File | Used by | Points at |
|---|---|---|
| `config.example.yml` + `secrets.example.yml` | `make run-local`, `make migrate-local` | `localhost:5432` |
| `config.compose.yml` (+ a secrets file Compose builds from `.env`'s `POSTGRES_PASSWORD`) | `docker compose up` | `vector-store:5432` |

```bash
cp .env.example .env               # first run only
docker compose up -d vector-store  # the database
make migrate-local                 # once per fresh database
make run-local                     # the API, on :8000
```

`make run` and `make migrate` take the two variables from your
environment, and refuse to start without them. To use other files (e.g. a
`secrets.local.yaml` with a real key; `*.local.yaml` is gitignored),
override either one on the command line:
`make run-local SECRETS_FILE_PATH=secrets.local.yaml`. If you change
`POSTGRES_*` in `.env`, change the example files to match:
`tests/test_example_config.py` keeps the committed copies in step.

### What has been verified (#481)

**Verified end-to-end** on 2026-10-21, on a real Postgres (Compose's
`vector-store` image, in an isolated project on port 15432). The commands
were the `make migrate-local` / `make run-local` recipes, run directly
(`make` itself wasn't installed on that machine), with `config.example.yml`
changed only in its port:

- `alembic upgrade head` read the files and migrated `0001` → `0003`.
- The API started, and `GET /health` returned `200`; so did `curl -sf` (as
  CI's smoke test) and the Dockerfile's `HEALTHCHECK` command.
- A request that queries the database succeeded.
- With the database stopped, `/health` stayed `200` while that request
  failed.
- Without the files, the API, `index_consumer_runner` and alembic each
  stopped with the errors above when actually run.
  `score_publisher_runner` is covered by a unit test only, and
  `landed_consumer_runner` only by reading its code.

**Not verified yet:**

- **A full Docker image build.** The build couldn't reach `deb.debian.org`
  from that network.
- **A full `docker compose up`.** Only the rendered config and the files
  mounted into a container were checked.
- **The chart in kind or Kubernetes.** Its probes and mounts were checked
  by reading the templates only.
- **The `make` targets themselves.**

## Instrumentation

`instrumentation.py` has `instrumented_call(name, cost=0.0)` — wrap
HTTP-level work in it instead of writing your own timing/logging:

```python
with instrumented_call("health_check"):
    ...
```

Model calls (OCR, ASR, LLM) are **not** wrapped in it — they are recorded
with `observability/tracking.py`'s `record_call()` instead (see below).

It measures wall-clock duration, logs one structured JSON line
(`call_name`, `duration_ms`, `cost`, `timestamp`, `status`), and folds
the result into an in-memory aggregate. A failure inside the wrapped
code is still recorded (`status: "error"`) and always re-raised — it is
never swallowed. `cost` has no real pricing model yet (see team README
"Open decisions") — pass a placeholder (e.g. `0.0`, or
`tokens * a stand-in price`) until one exists; every call must still be
recorded regardless.

Works as a context manager (any call site) or as a decorator
(synchronous call sites only — an `async def` route handler should use
the `with` form inside its body instead, as `/health` and
`/debug/simulate-call` do below).

`debug.py` demonstrates the wrapper end-to-end before any real model
call exists (extraction starts week 4):

- `GET /debug/simulate-call` — sleeps a short random duration and
  reports a placeholder cost, standing in for a real model call.
- `GET /debug/instrumentation` — the aggregate so far: `call_count`,
  `error_count`, `p95_duration_ms`, `cumulative_cost`. A minimal
  stand-in for the real monitoring dashboard Relay builds in week 8 —
  not a persistent metrics store, resets on process restart.
- `GET /debug/model-calls` — the model-call summary (#479): every OCR / ASR /
  LLM call recorded through `record_call()`, per call name — count, errors,
  p50/p95 latency, CPU seconds (OCR/ASR), tokens and US dollars (LLM), and
  slow / expensive limit crossings. A measure that doesn't apply to a kind
  has a `null` total and a `null` count; an unknown one a `null` total and a
  count of the calls that lacked it — never 0. See `observability/README.md`.

`/health` is wrapped too, purely to prove the mechanism end-to-end — a
health check isn't a model call.

Not the same thing as `observability/tracking.py`'s `record_call()`,
the one ledger every model call goes through (#479): it records what a
model call has and an HTTP handler does not — kind (OCR/ASR/LLM), CPU
seconds for local models, token usage and a dollar cost for LLM calls —
one that can be *unpriced* rather than a misleading 0.0.
