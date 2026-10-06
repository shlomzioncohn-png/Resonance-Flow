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
| `mind-index-consumer` (#470) | `python scripts/index_consumer.py` | No — consumes `task.created`/`task.updated`/`task.completed` from Kafka and writes to the vector store. No port to expose. |

**Open item:** `mind-index-consumer` has no liveness/health signal today
(stdout logging per message only) — flagging for Relay since `mind`'s own
`/health` pattern below doesn't apply to it.

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
| `MIND_VECTOR_STORE__TABLE` | `task_embeddings` | Vector-store table name. |
| `MIND_VECTOR_STORE__SEARCH_EFFORT` | `400` | HNSW query-time accuracy/speed knob (pgvector's `hnsw.ef_search`). |
| `MIND_VECTOR_STORE__EMBEDDING_DIM` | `256` | Must match whatever `embed()` actually returns -- the pgvector column is created at this width. |
| `MIND_DUPLICATE_SIMILARITY_THRESHOLD` | `0.90` | Untuned placeholder -- see `src/mind/embeddings/README.md` "Known limitations". |
| `LLM_API_KEY` / `LLM_MODEL` | blank | Not used yet — MIND runs local models only (see team README "Important Updates"). |
| `MODEL_CACHE_DIR` | `./models` | Where local model weights get mounted (see `docker-compose.yml`'s `models` volume). Never bake weights into the image. |
| `OCR_PROVIDER` / `ASR_PROVIDER` | `local` | Which provider extraction uses once built. |
| `WHISPER_MODEL` | `base` | Local Whisper size, once ASR is built. |
| `OBJECT_STORAGE_*` | blank | Bridge handoff credentials — not used until MIND reads from Bridge's object storage. |
| `MIND_API_PORT` | `8000` | Documented for completeness; the port MIND actually binds to today is hardcoded to `8000` in the `Dockerfile` (`EXPOSE`/`CMD`) and `docker-compose.yml`. Changing this variable alone does not currently move the port — flagged here as a known gap, not a working override. |
| `MIND_LOG_LEVEL` | `INFO` | Documented for completeness; not yet wired to `logging.basicConfig()` in `api/main.py`, which hardcodes `INFO`. Same gap as `MIND_API_PORT` above. |

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
