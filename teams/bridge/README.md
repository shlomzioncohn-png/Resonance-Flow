# Team Bridge - Service

Foundation module for Team Bridge (source adapters & communication ingestion).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet - the
goal of this stage is a clean, working Maven/Spring Boot project that later work can build on.

## Sub-roles (4 developers)

| Role | Owner | Owns |
|---|---|---|
| GitHub-adapter engineer | Hadas | GitHub ETL adapter end to end, including incremental sync |
| CRM-adapter engineer | Ruth | CRM ETL adapter end to end, including deal-stage sync |
| Unstructured-intake engineer | Malki | Gmail/Drive/Slack/WhatsApp/calendar intake, scoping enforcement |
| Meeting/doc/audio hand-off engineer | Esther | BigBlueButton/Zoom + scanned-document intake, hand-off contract with Mind |

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Docker / Docker Compose

The Unstructured-intake and meeting/doc/audio lanes (below) now run against real local MinIO +
Redpanda via Docker Compose, sharing the same MinIO bucket/Kafka topic. The other 2 lanes
(GitHub, CRM) haven't wired their infra yet.

## Project structure

```
src/main/java/com/rnd/bridge/
├── BridgeApplication.java   # Spring Boot entry point
├── controller/               # shared/cross-lane (currently empty)
├── service/                  # shared/cross-lane (currently empty)
├── repository/
├── dto/
├── config/
├── core/                      # shared pipeline contract (SourceReader/PayloadMapper/…)
├── raw/                       # shared RawObjectEvent model + validator (both unstructured lanes)
├── storage/                   # shared MinIO client (both unstructured lanes)
├── scoping/                   # shared scoping filter (Malki's lane; media lane skips it)
├── intake/                    # Malki's lane — see "Unstructured-intake lane" below
└── media/                     # Esther's lane — see "Meeting/doc/audio hand-off" below
src/main/resources/application.yml
src/main/resources/fixtures/unstructured/   # synthetic Gmail/Drive/Slack/WhatsApp/Calendar data
src/test/java/com/rnd/bridge/
```

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 21.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080

To just build the jar:

```bash
mvn clean package
java -jar target/bridge-0.1.0-SNAPSHOT.jar
```

## Running with Docker (no local Maven/JDK needed)

1. Copy the env file:

   ```bash
   cp .env.example .env
   ```

2. Build and run:

   ```bash
   docker compose up --build
   ```

The app will be available at http://localhost:8080 (or whatever `SERVER_PORT` is set to in `.env`).

To stop:

```bash
docker compose down
```

## Unstructured-intake lane (Malki)

Gmail · Drive · Slack · WhatsApp · Calendar → scoping decision → land tagged raw object in
MinIO → publish a pointer event to Kafka topic `bridge.raw.landed`. This lane never builds a
`Task` — Mind consumes the pointer later and extracts the eventual Task from the landed object.
No live source credentials are used yet; static fixtures under
`src/main/resources/fixtures/unstructured/` stand in for the 5 real sources.

### Running it locally

1. Start MinIO + Redpanda only (not the `bridge` container itself — run the app on the host
   for a faster dev loop):

   ```bash
   docker compose up -d minio redpanda
   ```

2. Run the app on the host (needs local Maven + JDK 21 — see "Running locally with Maven"
   above):

   ```bash
   mvn spring-boot:run
   ```

3. Trigger ingestion for a source:

   ```bash
   curl -X POST http://localhost:8080/api/bridge/intake/EMAIL/run
   ```

   Valid `{sourceType}` values: `EMAIL`, `SLACK`, `WHATSAPP`, `DRIVE_DOC`, `CALENDAR`. Returns
   the `RawObjectEvent` list that was landed + published.

4. Inspect results: MinIO console at http://localhost:9001 (user/pass: `minioadmin`/`minioadmin`
   by default, see `.env.example`) shows landed objects tagged `source_type`/`tenant_id`/
   `ingested_at`/`scope_decision`. Kafka topic `bridge.raw.landed` receives one pointer event
   per landed object.

Running the full stack in Docker (`docker compose up --build`, including the `bridge`
container itself) also works — the compose file wires MinIO/Kafka service-name addresses for
that case — but the host+Docker-infra split above is the faster loop for day-to-day work.

### Slack real-time ingestion (Events API)

Alongside the polling trigger above, Slack messages can also be ingested in near-real-time via
`POST /api/bridge/slack/events` (`SlackEventsController`) — Slack's Events API webhook, covering
new messages, edits (`message_changed`), and threaded replies, with retry-safe publication and
idempotent attachment handling. See `plan.md`'s "Slack real-time ingestion" section for the full
design and current limitations. Exposing this endpoint to Slack (a public URL, signing secret,
event subscription setup) isn't documented here yet — not yet exercised against real Slack
traffic.

### Fixture format

Realism was chosen per source, not uniformly (see `plan.md` for the full reasoning): Gmail
stays a simplified JSON stand-in (real Gmail export is `.mbox` — parsing that is its own
connector-sized task). WhatsApp fixtures use the real export line format, one `.txt` file per
chat (`whatsapp-*-sample.txt` — chat identity comes from the filename, matching how WhatsApp
actually exports). Drive, Calendar, and Slack fixtures use trimmed real API/export field names
(Google Drive API v3 File resource, Google Calendar API v3 Event resource, Slack export message
object) since those are genuinely JSON in production.

Chat messages (Slack, WhatsApp) are grouped into **sessions** before landing — a run of
consecutive messages with no gap larger than `bridge.intake.chat.session-gap-minutes` (default
45) becomes one landed object + one pointer event, not one per message. See `plan.md` for why.

### Scoping

`ScopingFilterService` exists as its own class (a deliberate seam) but currently always returns
`INCLUDED` — per direct instruction, no work-related-only filtering runs yet. Swapping in real
filtering logic later should only touch this one class.

### RawObjectEvent schema

`RawObjectEvent` matches the official JSON Schema exactly (`schemaVersion`, `objectKey`,
`sourceType`, `tenantId`, `ingestedAt`, `scopeDecision`, `dedupKey`, `modality`, `traceId`,
plus optional `contentType`/`sizeBytes`/`sourceTimestamp`; `language` is omitted — real
language detection is out of scope for this skeleton). Two fields worth knowing:
- `dedupKey` is a real `sha256:<64 hex>` digest of `sourceType + tenantId + naturalId` —
  deterministic, so a retried publish produces the same key.
- `traceId` is one UUID per intake run (one `POST .../run` call), shared by every event that
  call produces — not one per individual event.

## Meeting/doc/audio hand-off (Esther)

Lands Zoom/BBB recordings and scanned documents in MinIO, tagged for Mind's ASR/OCR pipeline, and
publishes a pointer event per object to Kafka topic `bridge.raw.landed` — the same MinIO
bucket/Kafka topic the Unstructured-intake lane uses, via the shared `raw`/`storage`/`core`
packages above. Bridge never builds a `Task` for this content itself - Mind reads the pointer
event and extracts the eventual Task from the object.

**Code:** `media/MediaIntakeService.java` (lands already-read bytes through the shared
`storage.ObjectStorageClient` and builds a `raw.model.RawObjectEvent`, plus
`landTranscript(...)` for a transcript sidecar), `media/MediaEventPublisher.java` (validates
via the shared `raw.RawObjectValidator`, then publishes), `media/MediaDedupIndex.java`
(content-hash dedup index stored in MinIO, see below), `media/MediaIntakeOrchestrator.java`
(manual test intake, see below - holds a declarative list of `{directory, sourceType,
modality}` mappings, so adding a new intake folder is a new list entry, not a new method),
`media/controller/MediaIntakeController.java` (triggers it). `source_type` for this lane is
`MEETING` (Zoom/BBB, `modality: audio`) or `SCANNED_DOC` (`modality: scanned_doc`) - both
values on the shared `raw.model.RawSourceType` enum (`SCANNED_DOC` isn't in Mind's confirmed
contract yet - tracked in [#188](https://github.com/KamaTechOrg/Resonance-Flow/issues/188)).

### Running it locally

Same local MinIO + Redpanda as the Unstructured-intake lane above (`docker compose up -d minio
redpanda`), no separate infra to start. Drop files into your own local folders (paths configured
via `.env` - see `BRIDGE_MEDIA_*` in `.env.example`), then hit the endpoint:

```bash
curl -X POST http://localhost:8080/api/bridge/media/run
```

| Env var | Uploads as |
|---|---|
| `BRIDGE_MEDIA_ZOOM_RECORDINGS_DIR` | `source_type=MEETING`, `modality=audio` |
| `BRIDGE_MEDIA_BBB_RECORDINGS_DIR` | `source_type=MEETING`, `modality=audio` |
| `BRIDGE_MEDIA_SCANNED_DOCS_DIR` | `source_type=SCANNED_DOC`, `modality=scanned_doc` |

Zoom and BBB are split into separate folders only to keep sample files organized by where they
came from - the schema has no separate platform field, both land as `MEETING`.

Each successfully uploaded file is moved into an `uploaded/` subfolder of wherever it came from,
so re-triggering the endpoint only picks up genuinely new files instead of re-landing (and
re-publishing an event for) the same file every time.

### Junk filtering, dedup, and transcripts

Per file, before it lands:

1. **Junk filter** - an empty file, or one below `bridge.media.min-file-size-bytes` (default
   1024 bytes; `BRIDGE_MEDIA_MIN_FILE_SIZE_BYTES`), is moved to a `rejected/` subfolder instead
   of `uploaded/` - never landed, never published. This is a file-size proxy for "empty/corrupt
   recording", not real audio-duration parsing (that needs a codec-aware library this lane
   doesn't depend on) - tune the threshold based on what garbage actually shows up.
2. **Dedup** - `MediaDedupIndex` keeps a set of already-landed `content_hash` values per
   `(sourceType, tenantId)` as a small JSON file in MinIO (`_dedup/{sourceType}/{tenantId}.json`
   - same "MinIO as lightweight state store" approach `plan.md` uses for the Unstructured-intake
   lane's sync cursor). A file whose content hash is already in the index is moved to
   `uploaded/` but not re-landed or re-published.
3. **Transcript pairing** (`MEETING` only) - a same-basename sidecar file (`.vtt`, `.srt`, or
   `.txt`) next to a recording is landed alongside it and linked via the recording's
   `transcript_object_key` - it does not get its own `RawObjectEvent`. Both files move to
   `uploaded/` together.

**Known gaps (tracked in issue #45):**
- Test samples aren't yet drawn from the real public corpora named in the spec (AMI/ICSI,
  RVL-CDIP/FUNSD) - current samples are placeholder files.
- Still local-only: this lane proves against the local MinIO/Redpanda started via this repo's
  own `docker-compose.yml`, not a separately-hosted shared instance - see issue #45 for whether
  a separate shared environment is still needed now that local infra is shared across lanes.

## Tests

```bash
mvn test
```
