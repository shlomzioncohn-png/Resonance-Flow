# MIND — Relay handoff

Everything Relay needs to build, run, and monitor MIND's container,
without reading MIND's source. See `docs/API.md` for the full API
contract (endpoints, error format, versioning) — this document is just
the deployment side: image, ports, env vars, health.

## Image

No registry/CI publishing is set up yet — there is no `mind:<tag>`
image sitting anywhere to pull. Today, building from source is the
only path:

```bash
cd teams/mind
docker build -t mind:latest .
```

or, for the full stack (MIND + its `vector-store` dependency):

```bash
docker compose up -d --build
```

`docker compose up` runs schema migrations automatically (the `migrate`
one-shot service `mind`/`mind-index-consumer` wait on). Running the
built image standalone (no compose) needs `alembic upgrade head` (or
`make migrate`) against it first -- `mind` refuses to start otherwise.
Either way the container needs its two config files (see "Configuration
files" below); Compose mounts `config.compose.yml` for them.

`docker-compose.yml`'s `mind` service builds from the `Dockerfile` in
this directory (multi-stage; the final image has no compiler, no dev
dependencies — only what's needed to run `uvicorn`). **Open item:**
once CI exists (see "Smoke test" below), publishing a tagged image to
a registry so Relay doesn't need to build from source is the natural
next step — track it here when it happens.

**Open item:** the runtime image does not install the `tesseract-ocr`
system package today — verified: no `apt-get install` for it anywhere
in the `Dockerfile`, only in local dev setups (the OCR benchmark
scripts run against a locally-installed Tesseract, not inside this
image). That's fine for now (OCR isn't wired into `src/mind/` yet —
Phase 2), but expect the image to grow next week when `apt-get install
tesseract-ocr` gets added to the runtime stage for real OCR support —
flagging so Relay isn't surprised by the size jump. (Decided: Tesseract. PaddleOCR was evaluated and not chosen — partly to avoid the larger image it would need, `paddlepaddle` plus model weights; see `benchmarks/ocr-asr-benchmark.md`.)

## Containers

Three MIND containers exist, built from the same image (`Dockerfile`),
differing only in `command`:

| Service | Command | Exposes HTTP? |
|---|---|---|
| `mind` | `uvicorn mind.api.main:app` | Yes, port `8000`. Also runs the score publisher (#920) as threads in the same process. |
| `mind-index-consumer` (#470) | `python -m mind.embeddings.index_consumer_runner` | No — consumes `task.created`/`task.updated`/`task.completed` from Kafka and writes to the vector store. No port to expose. |
| `mind-landed-consumer` (#919) | `python -m mind.ingestion.landed_consumer_runner` | No — consumes `bridge.raw.landed`, publishes `task.creation.requested`, and, from Inbox's `task.created` reply, `task.dependencies.proposed`. Its pending requests are in memory: one replica only until #918. |

Both embedding and Whisper weights are baked into the image
(`/opt/models/`), so no container downloads a model at start-up or needs
egress to Hugging Face.

**Open item:** neither consumer has a positive liveness probe (no HTTP
port) — each exits non-zero on a thread crash or a Kafka error, but can't
signal "stuck but alive". Flagging for Relay.

## Ports

| Port | Service | Notes |
|---|---|---|
| `8000` | MIND API | HTTP. All routes in `docs/API.md` live here. |
| `5432` | `vector-store` (pgvector/Postgres) | Only relevant if Relay runs the full compose stack rather than just the `mind` image; MIND's own code doesn't use this port. |

## Configuration files (#481)

MIND reads every connection setting from the two files your chart already
mounts, and nothing else: the vector store, Kafka and the schema registry.
There are no hardcoded hosts, and no connection settings in env vars.

| File | Path in the pod | Env var naming it | Required? |
|---|---|---|---|
| `config.yaml` (ConfigMap) | `/etc/resonance-flow/config/config.yaml` | `CONFIG_FILE_PATH` | **Yes**: MIND, its runners and `alembic` refuse to start without it |
| `secrets.yaml` (Secret) | `/etc/resonance-flow/secret/secrets.yaml` | `SECRETS_FILE_PATH` | No, it's mounted `optional: true`; but `vector_store.password` must be there before MIND can start |

Keys are the ones `templates/services/mind/configmap.yaml` renders. Which
ones MIND reads, the exact error for each missing piece, and the two parts
not aligned yet (the LLM keys, waiting on your confirmation, and object
storage, #349) are in `src/mind/api/README.md`, "Configuration (#481)".
The migration initContainer needs the same two files; the chart already
mounts them into it.

## Environment variables

Source of truth: `.env.example` in this directory. None of these is a
connection setting.

| Variable | Default | For |
|---|---|---|
| `MIND_ENVIRONMENT` | `local` | `local` / `ci` / `staging` / `production` -- fails fast on anything else. |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | *(none)* | **Compose only.** They configure the `vector-store` container; Compose also builds MIND's local secrets.yaml from `POSTGRES_PASSWORD`. Not read by MIND itself. |
| `MIND_VECTOR_STORE__TABLE` | `task_embeddings` | Test-only knob. The migration always creates `task_embeddings` -- changing this in a real deploy points at a table that doesn't exist. |
| `MIND_VECTOR_STORE__SEARCH_EFFORT` | `400` | HNSW query-time accuracy/speed knob (pgvector's `hnsw.ef_search`). |
| `MIND_DUPLICATE_SIMILARITY_THRESHOLD` (and the other `MIND_*_THRESHOLD`s) | calibrated | See `src/mind/embeddings/README.md` "Threshold calibration". |
| `MODEL_CACHE_DIR` | `./models` | Local dev only: where hand-placed model weights get mounted (see `docker-compose.yml`'s `models` volume, `models/README.md`). In the image the baked copies win. |
| `WHISPER_MODELS_DIR` / `EMBEDDING_MODELS_DIR` | set by the `Dockerfile` | Where the weights baked into the image live (`/opt/models/whisper`, `/opt/models/embeddings`). Don't override in the chart. |
| `WHISPER_MODEL` | `base.en` | Fallback only: config.yaml's `asr.whisper_model` wins. Decided in #135, see `benchmarks/ocr-asr-benchmark.md`. |
| `MIND_LOG_LEVEL` | `INFO` | Fallback only: config.yaml's `service.log_level` wins, and the API applies it at start-up. |
| `MIND_API_PORT` | `8000` | Documented for completeness; the port MIND actually binds to is hardcoded to `8000` in the `Dockerfile` (`EXPOSE`/`CMD`) and `docker-compose.yml`. Changing this variable alone does not move the port -- a known gap, not a working override. |

A `MIND_VECTOR_STORE__DSN` set anywhere is ignored. The `LLM_*`,
`OCR_PROVIDER`/`ASR_PROVIDER` and `OBJECT_STORAGE_*` vars that used to be
listed here are gone: no code read them.

## Schema migrations

The app itself never runs DDL. `db/init/01-enable-pgvector.sql` enables
the `vector` extension (needs superuser, runs once via Postgres's own
`docker-entrypoint-initdb.d` in Compose); everything after that --
creating `task_embeddings` and its HNSW index -- is an Alembic migration
under `migrations/` (`alembic upgrade head`, or `make migrate`), meant to
run once as its own deploy step before `mind` starts, not baked into the
app's own startup path.

`docker-compose.yml` models this as a one-shot `migrate` service that
`mind` depends on via `service_completed_successfully`. **The equivalent
in your deployment (a Kubernetes Job, an initContainer, a separate
pipeline step -- whichever fits the existing pattern) is Relay's call**;
MIND's only requirement is that `alembic upgrade head` completes before
the `mind` container starts, against the same database `mind` will
connect to.

At normal runtime the service only ever issues `SELECT`/`INSERT`/
`UPDATE`/`DELETE` against `task_embeddings` -- no DDL, and no superuser.
If that's reflected in a DB role/grant somewhere in the deploy setup, the
runtime role needs nothing beyond those four, ever.

If `mind` starts against a database that hasn't been migrated yet, it
fails fast at startup (in the `lifespan` handler, via `PgVectorStore`'s
own readiness check) with a clear `RuntimeError` naming the missing
table and the fix (`alembic upgrade head`) -- not a bare `UndefinedTable`
surfacing on whichever request happens to hit it first.

## Health check

### `GET /health`

`200` only once start-up has completed successfully (config loaded, vector
store opened and migrated) — #481. It doesn't re-check dependencies per
request, since your chart uses this path for the liveness probe too.

**Response — `200 OK`**

```json
{
  "status": "ok",
  "version": "0.1.0",
  "uptime_seconds": 12.4
}
```

| Field | Meaning |
|---|---|
| `status` | `"ok"` with `200`; `"not_ready"` with `503`. |
| `version` | MIND's package version, so Relay can tell which build is running. |
| `uptime_seconds` | Seconds since the process started. |

**Status codes**

| Code | Meaning |
|---|---|
| `200` | Start-up completed successfully; MIND accepts work. |
| `503` | Start-up hasn't completed, or shutdown has begun (`"status": "not_ready"`). |
| *(no response / connection refused / timeout)* | The process isn't serving HTTP at all — including a start-up that failed (missing or broken config, unreachable or unmigrated database), which exits with a clear error. Treat as down. |

The chart's startup probe covers the start-up window; the Docker
`HEALTHCHECK` and CI's `smoke-test.sh` (`curl -sf`) count a `503` as
not healthy, the same as no answer.

Full error-response format (for every other, non-2xx endpoint) is in
`docs/API.md`.

### Docker-native health check

The image also has a `HEALTHCHECK` baked into the `Dockerfile` — it
polls `GET /health` internally every 10s (3s timeout, 3 retries, 5s
grace period on startup) and reports `healthy`/`unhealthy` /
`starting` to Docker directly. Visible via:

```bash
docker compose ps          # STATUS column shows (healthy) / (unhealthy)
docker inspect --format='{{.State.Health.Status}}' <container>
```

Relay's orchestrator can key off this instead of polling `/health`
itself, if it understands Docker's native health state.

## Smoke test

Verifies the whole stack comes up clean and `/health` responds, from
nothing (no cached layers, no old containers/volumes):

```bash
cd teams/mind
cp .env.example .env        # .env is gitignored -- a fresh clone has none;
                             # docker compose's env_file: .env fails outright without this
docker compose down -v      # full cleanup -- no old state
docker compose up -d --build
sleep 5
curl -f http://localhost:8000/health
```

Expected: `curl` prints the JSON body above and exits `0`; `docker
compose ps` shows both `mind` and `vector-store` as `(healthy)`.

**Status for #481:** this full smoke test has **not** been re-run since
MIND moved to the config files. The image didn't build on the network it
was tried on (`apt-get` couldn't reach `deb.debian.org`). What *was*
verified end-to-end on a real Postgres was the migration, then MIND
starting and `/health` returning `200`, run on the host -- see
`src/mind/api/README.md`, "What has been verified (#481)". The chart in
kind or Kubernetes is also unverified for #481.

Not yet wired into CI (no `mind-ci.yml`/CI pipeline exists in this repo
today) — run it manually as above until that's added.
