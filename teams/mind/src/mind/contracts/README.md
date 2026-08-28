## RawObjectEvent

`RawObjectEvent` is the event sent by Bridge to MIND.

The event points MIND to the raw content stored in MinIO.

### Required Bridge fields

| Field | Description | Status |
|---|---|---|
| `object_key` | Exact MinIO location of the raw object. The format is guaranteed by the Bridge contract. | Confirmed — required |
| `source_type` | Business origin/system that produced the item: `EMAIL`, `SLACK`, `WHATSAPP`, `DRIVE`, `CALENDAR`, or `MEETING`. | Confirmed — required |
| `tenant_id` | Organization/tenant that owns the content. Guaranteed to be non-empty. | Confirmed — required |
| `ingested_at` | Time Bridge landed the object. Always normalized to UTC. | Confirmed — required |
| `scope_decision` | Bridge work-relevance decision: `INCLUDED` or `EXCLUDED`. | Confirmed — required |
| `content_hash` | SHA-256 hash of the raw bytes, used as a deterministic deduplication key. | Confirmed — required |
| `schema_version` | Version of the `RawObjectEvent` contract. | Confirmed — required |
| `attachment_index` | Position of the object within its logical source item. `0` represents the body or standalone item; `1+` represents attachments. | Confirmed — required |

### Optional Bridge fields

| Field | Description | Status |
|---|---|---|
| `participants` | Coarse list of people involved in the source interaction, copied from the source export. | Confirmed — optional |
| `content_type` | Exact MIME/content type of the stored object. | Confirmed — optional |
| `size_bytes` | Size of the stored object in bytes. | Confirmed — optional |
| `transcript_object_key` | MinIO pointer to a platform-generated transcript, when one exists. It follows the same object-key format as `object_key`. | Confirmed — optional |
| `source_timestamp` | Original creation time at the source. Always normalized to UTC. | Confirmed — optional |
| `modality` | Technical routing hint for MIND processing. Current values: `audio`, `scanned_doc`. | Confirmed — optional |
| `correlation_id` | UUID shared by events that belong to the same logical source item, such as an email body and its attachments. Standalone items may omit it. | Confirmed — optional |

### Resolved Bridge decisions

- `dedup_key` is not part of `RawObjectEvent` v1. `content_hash` is the deterministic deduplication key.
- `language` is not supplied by Bridge; MIND determines language when needed.
- `trace_id` was removed from Bridge v1.
- `source_type` represents the business/source system, while `modality` represents technical processing/routing.
- The exact business rules that cause `scope_decision=EXCLUDED` are still being finalized by Bridge. In v1, Bridge currently defaults objects to `INCLUDED`.

Bridge contract discussion: [Issue #31](https://github.com/KamaTechOrg/Resonance-Flow/issues/31)
