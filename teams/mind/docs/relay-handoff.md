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

Two MIND containers now exist, built from the same image (`Dockerfile`),
differing only in `command`:

| Service | Command | Exposes HTTP? |
|---|---|---|
| `mind` | `uvicorn mind.api.main:app` | Yes, port `8000`. |
| `mind-index-consumer` (#470) | `python -m mind.embeddings.index_consumer_runner` | No — consumes `task.created`/`task.updated`/`task.completed` from Kafka and writes to the vector store. No port to expose. |

**Open item:** `mind-index-consumer` has no positive liveness probe (no HTTP
port, so `mind`'s `/health` pattern doesn't apply). It does exit non-zero
if any of its three topic-consumer threads dies, so a restart policy can
act on a crash — but there's still no "I'm alive and keeping up" signal
for the healthy-but-stuck case. Flagging for Relay.

## Ports

| Port | Service | Notes |
|---|---|---|
| `8000` | MIND API | HTTP. All routes in `docs/API.md` live here. |
| `5432` | `vector-store` (pgvector/Postgres) | Only relevant if Relay runs the full compose stack rather than just the `mind` image; MIND's own code doesn't use this port. |

## Environment variables

Source of truth: `.env.example` in this directory.

**Required now: `MIND_ENVIRONMENT`, `POSTGRES_USER`/`POSTGRES_PASSWORD`/
`POSTGRES_DB`.** The app now opens a real vector-store connection pool at
startup (a FastAPI `lifespan` handler, not lazily on first request), so
`mind.config.Settings` validates these eagerly -- **the process refuses
to start at all if they're missing**, not just the routes that use them.
This is a real change from earlier: previously nothing read env vars at
startup.

`docker-compose.yml` computes `MIND_VECTOR_STORE__DSN` for the `mind`
service from `POSTGRES_USER`/`PASSWORD`/`DB`, pointed at the
`vector-store` service by name -- you only need to set the three
`POSTGRES_*` vars yourself; the DSN itself isn't something you set by
hand in Compose. Running the `mind` image outside Compose (no
`vector-store` service next to it) means setting
`MIND_VECTOR_STORE__DSN` directly instead, as a single connection
string -- see `.env.example`'s comment on why Compose builds one and a
standalone run needs one supplied directly.

| Variable | Default | For |
|---|---|---|
| `MIND_ENVIRONMENT` | *(required, no default)* | `local` / `ci` / `staging` / `production` -- fails fast on anything else. |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | *(required, no default)* | Configure the `vector-store` container itself, and are interpolated into `MIND_VECTOR_STORE__DSN` for the `mind` service -- see above. |
| `MIND_VECTOR_STORE__TABLE` | `task_embeddings` | Test-only knob (the test suite points it at `task_embeddings_test`). The Alembic migration always creates `task_embeddings` specifically -- changing this in a real deploy points the app at a table that doesn't exist, not a different real table. A genuinely different table name needs a new migration, not this var. |
| `MIND_VECTOR_STORE__SEARCH_EFFORT` | `400` | HNSW query-time accuracy/speed knob (pgvector's `hnsw.ef_search`). |
| `MIND_DUPLICATE_SIMILARITY_THRESHOLD` | `0.90` | Untuned placeholder -- see `src/mind/embeddings/README.md` "Known limitations". |
| `LLM_API_KEY` / `LLM_MODEL` | blank | Not used yet — MIND runs local models only (see team README "Important Updates"). |
| `MODEL_CACHE_DIR` | `./models` | Where local model weights get mounted (see `docker-compose.yml`'s `models` volume). Never bake weights into the image. |
| `OCR_PROVIDER` / `ASR_PROVIDER` | `local` | Which provider extraction uses once built. |
| `WHISPER_MODEL` | `base.en` | Local Whisper size, once ASR is built — decided in #135, see `benchmarks/ocr-asr-benchmark.md`. |
| `OBJECT_STORAGE_*` | blank | Bridge handoff credentials — not used until MIND reads from Bridge's object storage. |
| `MIND_API_PORT` | `8000` | Documented for completeness; the port MIND actually binds to today is hardcoded to `8000` in the `Dockerfile` (`EXPOSE`/`CMD`) and `docker-compose.yml`. Changing this variable alone does not currently move the port — flagged here as a known gap, not a working override. |
| `MIND_LOG_LEVEL` | `INFO` | Documented for completeness; not yet wired to `logging.basicConfig()` in `api/main.py`, which hardcodes `INFO`. Same gap as `MIND_API_PORT` above. |

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
| `status` | Always `"ok"` when this endpoint responds at all — no degraded state exists today. |
| `version` | MIND's package version, so Relay can tell which build is running. |
| `uptime_seconds` | Seconds since the process started. |

**Status codes**

| Code | Meaning |
|---|---|
| `200` | The process is up. The only code `/health` returns today. |
| *(no response / connection refused / timeout)* | The process isn't serving HTTP at all — Relay's own connection/timeout handling should treat this as down. There is no `5xx` case: `/health` has no code path that raises. |

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

Not yet wired into CI (no `mind-ci.yml`/CI pipeline exists in this repo
today) — run it manually as above until that's added.
