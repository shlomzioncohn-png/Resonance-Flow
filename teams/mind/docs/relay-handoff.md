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
flagging so Relay isn't surprised by the size jump. (The OCR engine is not final: if the team picks PaddleOCR instead, the image would instead grow by `paddlepaddle` plus model weights — larger, and not yet measured; see `benchmarks/ocr-asr-benchmark.md` "OCR — final decision".)

## Ports

| Port | Service | Notes |
|---|---|---|
| `8000` | MIND API | HTTP. All routes in `docs/API.md` live here. |
| `5432` | `vector-store` (pgvector/Postgres) | Only relevant if Relay runs the full compose stack rather than just the `mind` image; MIND's own code doesn't use this port. |

## Environment variables

Source of truth: `.env.example` in this directory.

**Required today: none.** `/health` — the only endpoint that exists
right now — deliberately touches no downstream dependency, and no code
in `src/mind/` currently reads any environment variable (verified:
`grep -r os.environ\|os.getenv src/` finds nothing). The container runs
correctly with `.env` copied verbatim from `.env.example`, blanks
included.

Everything below is reserved for features landing in Phase 2
(extraction, embeddings/search, OCR/ASR) — safe to leave blank or at
its default until MIND's code actually starts reading it. This table
will need a real "required" column once that happens.

| Variable | Default | For |
|---|---|---|
| `VECTOR_STORE_URL` | `postgresql://mind:mind@vector-store:5432/mind` | Connection string for the pgvector service (see `docker-compose.yml`). |
| `VECTOR_STORE_USER` / `VECTOR_STORE_PASSWORD` / `VECTOR_STORE_DB` | `mind` / `mind` / `mind` | Also configure the `vector-store` container itself (Postgres env vars) — keep these three in sync with the values embedded in `VECTOR_STORE_URL`. |
| `LLM_API_KEY` / `LLM_MODEL` | blank | Not used yet — MIND runs local models only (see team README "Important Updates"). |
| `MODEL_CACHE_DIR` | `./models` | Where local model weights get mounted (see `docker-compose.yml`'s `models` volume). Never bake weights into the image. |
| `OCR_PROVIDER` / `ASR_PROVIDER` | `local` | Which provider extraction uses once built. |
| `WHISPER_MODEL` | `base` | Local Whisper size, once ASR is built. |
| `OBJECT_STORAGE_*` | blank | Bridge handoff credentials — not used until MIND reads from Bridge's object storage. |
| `MIND_API_PORT` | `8000` | Documented for completeness; the port MIND actually binds to today is hardcoded to `8000` in the `Dockerfile` (`EXPOSE`/`CMD`) and `docker-compose.yml`. Changing this variable alone does not currently move the port — flagged here as a known gap, not a working override. |
| `LOG_LEVEL` | `INFO` | Documented for completeness; not yet wired to `logging.basicConfig()` in `api/main.py`, which hardcodes `INFO`. Same gap as `MIND_API_PORT` above. |

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
