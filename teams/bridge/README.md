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

The Unstructured-intake lane (below) now runs against real local MinIO + Redpanda via Docker
Compose. The other 3 lanes (GitHub, CRM, meeting/doc/audio) haven't wired their infra yet.

## Project structure

```
src/main/java/com/rnd/bridge/
├── BridgeApplication.java   # Spring Boot entry point
├── controller/               # shared/cross-lane (currently empty)
├── service/                  # shared/cross-lane (currently empty)
├── repository/
├── dto/
├── config/
└── intake/unstructured/      # Malki's lane — see "Unstructured-intake lane" below
    ├── config/
    ├── controller/
    ├── dto/
    ├── fixture/
    └── service/
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

## Tests

```bash
mvn test
```
