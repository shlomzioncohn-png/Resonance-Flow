## RawObjectEvent

`RawObjectEvent` is the event sent by Bridge to MIND.

- Local mirror of Inbox's canonical `Task` schema (read-only conformance — Inbox owns and changes it, not us).
- Event payloads published to Inbox / Chain / Lens / Relay.
- Internal request/response models between MIND's own submodules.
- `errors.py` — `ErrorResponse`, MIND's standard error shape, plus
  `register_error_handlers(app)` which wires it into FastAPI once in
  `api/main.py`. Every endpoint, current and future, returns errors in
  this shape; do not invent a per-endpoint error format. Field reference
  and status-code table: `docs/API.md`.
- `health.py` — `HealthResponse`, the body of `GET /health`. Every
  response crossing the API boundary should be a defined model like
  this one, not a raw `dict` -- FastAPI then validates it and `/docs`
  reflects the real shape.
- `raw_object_event.py` / `raw_object_event_enums.py` — `RawObjectEvent`,
  the event Bridge sends MIND (see below).

## RawObjectEvent

`RawObjectEvent` is the event sent by Bridge to MIND, one per logical item (an
email, a meeting, a scanned document). Every MinIO object of the item arrives
together in `objects`. The schema was settled in
[Issue #31](https://github.com/KamaTechOrg/Resonance-Flow/issues/31).

### Event fields

| Field | Description | Status |
|---|---|---|
| `schema_version` | Version of the `RawObjectEvent` contract. | required |
| `source_type` | Business origin/system: `EMAIL`, `SLACK`, `WHATSAPP`, `DRIVE`, `CALENDAR`, `ZOOM`, `BIGBLUEBUTTON`, `SCANNED_DOC`. `MEETING` is rejected here; it stays in `SourceType` only for the task output. | required |
| `tenant_id` | Organization/tenant that owns the content. Non-empty. | required |
| `correlation_id` | UUID of the logical item, stable across all its revisions. | required |
| `ingested_at` | Time Bridge landed the item. UTC. | required |
| `scope_decision` | `INCLUDED` or `EXCLUDED`, derived from `classification`; `INCLUDED` by default when nothing classified the item. | required |
| `revision` | Strictly increasing per `correlation_id`. | required |
| `change_type` | `UPSERT` or `DELETE_REVIEW`. | required |
| `change_significance_score` | Integer 0-100. Bridge sends 100 until it computes a real score; MIND does not act on it yet. | required |
| `objects` | The item's MinIO objects (see `RawObjectRef`). At least one for `UPSERT`, none for `DELETE_REVIEW`. | required |
| `participants` | Coarse list of people involved, copied from the source export. | optional |
| `source_timestamp` | Original creation time at the source. UTC. | optional |
| `source_ref` | The source system's own identifier for the item, unique within `source_type`: Slack `channel_id:ts`, Zoom meeting `uuid`, BBB `recordID`, email Message-ID. An identifier, not a link; the UI builds links from `source_type` + `source_ref`. `null` when the source has none (WhatsApp). May change between revisions. | optional |
| `classification` | Bridge's triage of the item (see below). | optional |

### RawObjectRef: one MinIO object

| Field | Description | Status |
|---|---|---|
| `role` | `BODY`, `ATTACHMENT`, `TRANSCRIPT`, `RECORDING`, `SOURCE_METADATA`. | required |
| `object_key` | Exact MinIO location, `SOURCE/tenant/YYYY-MM-DD/file`. | required |
| `content_hash` | `sha256:` + 64 hex digits of the raw bytes. | required |
| `content_type` | MIME type of the stored object. | optional |
| `size_bytes` | Size in bytes, non-negative. | optional |
| `modality` | `TEXT`, `AUDIO`, `SCANNED_DOC`: which engine processes the object. Required, so a recording is never defaulted to `TEXT`. | required |

### classification

`decided_by` (`MODEL` or `RULE`), three labels with a value and a 0-1
confidence (`category`, `quality`, `sensitivity`), a free-text `reason` for
humans only, and `classifier { model, prompt_version }`.

- Unknown values do not break the event: an unknown `category` becomes
  `OTHER`, an unknown `sensitivity` becomes `HIGH` (fail safe), and an unknown
  `quality` drops the label. Every other enum in the event rejects an unknown
  value.
- MIND does not enforce Bridge's own classification rules (which categories a
  `source_type` may have, confidence 1.0 for `RULE`, `EXCLUDED` only with a
  classification). A mismatch is a Bridge bug, not a reason to drop the event.

### Resolved Bridge decisions

- Deduplication key: `correlation_id + ":" + revision`. `content_hash` is per
  object now, so no single hash represents the event.
- An object with `role: TRANSCRIPT` replaces `transcript_object_key`; its
  presence means ASR is not needed.
- `DELETE_REVIEW` means the item was deleted at the source. Consumers flag
  their outputs for review and do not delete them automatically.
- `SOURCE_METADATA` is the platform's own report, untouched, in a format per
  `source_type` (Zoom JSON, BBB XML). MIND does not need to read it.
- `language` is not supplied by Bridge; MIND determines language when needed.
- `source_type` represents the business/source system, while `modality`
  represents technical processing/routing.
- Not pinned down in #31: the timestamp format. Bridge currently sends
  `ingested_at` as epoch seconds (`"1791877808.974982000"`); Pydantic reads it
  correctly, but ISO-8601 is what our tests use.

Bridge contract discussion: [Issue #31](https://github.com/KamaTechOrg/Resonance-Flow/issues/31)
