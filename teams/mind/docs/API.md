# MIND API

MIND's outward-facing HTTP API — the only door other teams use to talk to
MIND (health check and related-task search today; scoring once Phase 2
lands). This document is the contract: Relay, Chain, Inbox and Lens
should be able to integrate against it without reading MIND's source.

Implementation: `src/mind/api/main.py` (routes) and
`src/mind/contracts/errors.py` (error format). Keep this document and
those two files in sync — if you change one, update the other in the
same change.

## Base URL

- Local dev: `http://localhost:8000`
- Deployed: wherever Relay's platform points at MIND's image — same
  routes, same port (`8000`) unless Relay's platform remaps it.

## Endpoints

### `GET /health`

Liveness/readiness check for Relay to monitor (#481). A `200` means
start-up completed successfully: the config file loaded, and the vector
store opened and was found migrated. It doesn't re-check those
dependencies per request — Relay's chart uses this same path for the
liveness probe, so a check that failed on a database blip would restart
every MIND pod. A start-up that fails (missing or broken config,
unreachable or unmigrated database) stops the process with a clear error
instead of serving, so Relay sees no answer at all in that case.

**Response — `200 OK`**

```json
{
  "status": "ok",
  "version": "0.1.0",
  "uptime_seconds": 12.4
}
```

| Field | Type | Meaning |
|---|---|---|
| `status` | string | `"ok"` with `200`; `"not_ready"` with `503`. |
| `version` | string | MIND's package version (`mind.__version__`), so Relay can tell which build is running. |
| `uptime_seconds` | number | Seconds since the process started. |

**Status codes**

| Code | When |
|---|---|
| `200` | Start-up completed successfully; MIND accepts work. |
| `503` | Start-up hasn't completed, or shutdown has begun. Same body, with `"status": "not_ready"`. |
| *(no response)* | The process isn't serving HTTP — including a start-up that failed and exited. Relay's own connection/timeout handling detects that case. |

### `GET /v1/tasks/{task_id}/related`

Up to 5 tasks related to `task_id` (#472), ranked by similarity, `task_id`
itself never included. Always `200` — an unindexed `task_id`, an empty
index, and "nothing clears the relatedness floor" all come back as
`related: []`, never a `404` or `500`. See
`src/mind/embeddings/README.md` for the relatedness threshold and how
it's calibrated, and its "Confidence" section (#476) for `confidence`
below.

**Response — `200 OK`**

```json
{
  "related": [
    { "task_id": "task-456", "similarity": 0.83, "confidence": 0.41 }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `related` | array | Ranked, best match first. Empty array for "nothing related" — see above. |
| `related[].task_id` | string | A related task's id. |
| `related[].similarity` | number | Cosine similarity, `-1.0`–`1.0`. |
| `related[].confidence` | number | `0.0`–`1.0`, distance above the decision threshold — not the raw similarity above. |

**Status codes**

| Code | When |
|---|---|
| `200` | Always, for any `task_id` — see above. |
| `422` | `task_id` fails request validation (e.g. wrong type in the path). |
| `500` | Unhandled exception — the standard error format below applies. |

## Error format

Every non-2xx response from any MIND endpoint — today and in the future —
has this shape (`ErrorResponse` in `src/mind/contracts/errors.py`):

```json
{
  "error_code": "validation_error",
  "message": "Request failed validation.",
  "details": { "errors": ["..."] },
  "request_id": "b3f1c2d4-...",
  "timestamp": 1755678900.12
}
```

| Field | Type | Meaning |
|---|---|---|
| `error_code` | string | Stable, machine-readable. Safe to branch on in calling code. See table below for the current set. |
| `message` | string | Human-readable summary. Safe to log or surface to a human; never contains secrets, stack traces, or internal paths. |
| `details` | object or `null` | Optional extra structured context (e.g. which fields failed validation). Omitted when there is nothing to add. |
| `request_id` | string | Echoes the caller's `X-Request-Id` request header if one was sent, otherwise a generated UUID. Also returned as an `X-Request-Id` response header on every request. Use it to correlate an error with MIND's server-side logs. |
| `timestamp` | number | Unix timestamp (seconds) when the error was produced. |

**`error_code` values and their HTTP status codes**

| `error_code` | HTTP status | Meaning |
|---|---|---|
| `bad_request` | 400 | Malformed request MIND can't act on. |
| `unauthorized` | 401 | Missing/invalid credentials (once auth exists). |
| `forbidden` | 403 | Authenticated but not allowed. |
| `not_found` | 404 | No such resource / route. |
| `conflict` | 409 | Request conflicts with current state. |
| `validation_error` | 422 | Request shape/types are wrong — `details.errors` holds the Pydantic validation errors. |
| `rate_limited` | 429 | Caller is being throttled (once rate limiting exists). |
| `internal_error` | 500 | Unhandled exception on MIND's side. `message` is intentionally generic — check `request_id` against MIND's logs for the real cause. |
| `service_unavailable` | 503 | A downstream dependency MIND needs is down (once MIND has downstream dependencies to be down). |

Every future MIND endpoint must reuse this exact shape — do not invent a
new error format per endpoint. If a new `error_code` is needed, add it to
the table in `errors.py` and to this table in the same change.

## API versioning plan

`/health` is unversioned because there is nothing to break there. Every
business endpoint (`/v1/tasks/{task_id}/related` today, search/scoring
once Phase 2 lands) lives under `src/mind/api/v1/router.py`, mounted
into `app` in `main.py`.

Plan for future business endpoints:

1. **All new business endpoints go under a `/v1/` prefix** — e.g.
   `/v1/search`, `/v1/score`. `/health` stays unversioned and
   un-prefixed: infrastructure endpoints like health checks are excluded
   from API versioning since their contract must stay maximally stable,
   and Relay is already polling it at its current path.
2. **Version the URL, not the payload.** No `Accept`-header negotiation,
   no version field inside the JSON body — simplest thing every
   consuming team (Chain, Inbox, Lens, Relay) can rely on.
3. **A breaking change to a `/v1/` route ships as `/v2/` alongside it** —
   `/v1/` is never mutated in place. "Breaking" means: removing or
   renaming a field, changing a field's type or meaning, changing what a
   status code means, or removing an endpoint.
4. **Additive, backward-compatible changes don't need a version bump** —
   a new optional response field or a brand-new endpoint can land under
   the existing version.
5. **Deprecation:** announce a removal in this document *and* notify
   Relay/Chain/Inbox/Lens directly (not just a changelog entry) before
   taking an old version away. Exact notice window is TBD with the other
   teams — track it here once agreed. The old version keeps working
   until the window closes.
6. **Implementation:** `src/mind/api/v1/` holds the `/v1` router,
   mounted into `app` in `main.py`. When `/v2` is needed, add a sibling
   `src/mind/api/v2/` package the same way — one package per version, so
   an old and a new version can run side by side during a deprecation
   window.

## Open decisions this document will need updating for

- Auth scheme for cross-team calls (none yet — every endpoint above
  assumes no auth).
- Whether `/health` splits into liveness vs. readiness (e.g.
  `/health/live` vs. `/health/ready`), so readiness could re-check
  dependencies per request without liveness restarting pods. Needs the
  chart's `components.mind` probes changed too (Relay).
- Deprecation notice window (see versioning plan, step 5).

This list is scoped to the API contract specifically (things Relay and
the other consuming teams need to know about). The team `README.md` has
its own, separate "Open decisions" list for broader project decisions
(vector store choice, eval metric, etc.) that don't affect this contract
-- the two aren't duplicates of each other, so there's nothing to keep
in sync between them beyond each staying accurate on its own.
