# Team Inbox - Service

Foundation module for Team Inbox (Backend Services & Shared Task/Data Model).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet - the
goal of this stage is a clean, working Maven/Spring Boot project that later work can build on.

## Stack

- Java 11
- Spring Boot 2.7.18 (Maven)
- PostgreSQL, with schema migrations managed by Flyway
- Docker / Docker Compose

## Database

Migrations live under [`shared/database/migrations`](../../shared/database/migrations) at the repo
root (shared across services) and are applied automatically by Flyway on application startup - no
manual step is needed.

Connection settings are read from environment variables (see `.env.example`): `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`, `DB_PASSWORD`.

## Project structure

```
src/main/java/com/rnd/inbox/
├── InboxApplication.java   # Spring Boot entry point
├── controller/
├── service/
├── repository/
├── model/
│   ├── entity/
│   └── enums/
├── dto/
├── mapper/
├── exception/
└── config/
src/main/resources/application.yml
src/test/java/com/rnd/inbox/
```

See [`CLAUDE.md`](./CLAUDE.md) for what each package is responsible for and the rules for
working in this module.

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 11, plus a running PostgreSQL instance
(e.g. `docker compose up postgres` from the repo root, or your own local instance matching the
`.env.example` settings).

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080

To just build the jar:

```bash
mvn clean package
java -jar target/inbox-0.1.0-SNAPSHOT.jar
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

## Kafka

Local Kafka (single-node, KRaft mode — no Zookeeper) plus a web UI for it are part of this
module's `docker-compose.yml`.

### Bringing Kafka up

```bash
docker compose up -d kafka kafka-ui
```

(omit `-d` to watch the logs, or leave it off `docker compose up --build` entirely to start
everything, including the `inbox` app, together). First start pulls the `apache/kafka` image,
which is a few hundred MB — expect that to take a while once, not on every subsequent start.

Other services in this same `docker-compose.yml` (the `inbox` app, `kafka-ui`) reach Kafka
internally at `kafka:29092`. From your own machine (a local script, an IDE Kafka plugin, `kcat`,
etc.) it's at `localhost:${KAFKA_PORT:-9092}`.

### Accessing Kafka UI

Open **http://localhost:8090** (or `${KAFKA_UI_PORT}` from your `.env`) in a browser. It's
connected to this Kafka out of the box — no login, no extra setup. From there you can browse
Topics, inspect Messages and their payloads, see Partitions, and see Consumer Groups.

### Verifying the connection is healthy

```bash
docker compose ps
```

`inbox-kafka` should show `(healthy)` once it's fully up (health check polls
`kafka-broker-api-versions` internally, usually ready within ~20-30s of first start). You can
also confirm end-to-end from the CLI:

```bash
docker exec inbox-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

An empty list (no error) means Kafka is up and accepting connections. Once Kafka UI is open in
the browser, its cluster page shows the broker as online — that's the simplest visual check.

## Bridge task ingestion (Kafka)

INBOX consumes structured-source task events published by BRIDGE on the single shared
`bridge.structured.tasks` topic (all sources — GitHub, CRM, email, Slack, ... — publish to this one
topic), validates them, and creates canonical `Task` rows.

### One consumer for every platform

There is no per-platform consumer, listener, validator or topic. A single `StructuredTaskConsumer`
handles the whole ingestion path; the platform a task came from travels inside the event as
`sourceType`, and is mapped straight onto the canonical `SourceType` enum.

- **Reusable / shared infrastructure** (`shared.messaging.kafka` package): `EventProcessingTemplate<T>`
  drives the full pipeline — deserialize, validate, persist with retry, ack, and route failures to
  `<topic>.errors` — plus `ErrorTopicPublisher`, the retry `RetryTemplate` bean and its
  `KafkaRetryProperties`, and the `EventValidator<T>` / `EventValidationException` /
  `TransientProcessingException` contracts. None of this knows anything about tasks or platforms.
- **Task-specific, platform-agnostic** (`structuredtask` package): `StructuredTaskConsumer`,
  `StructuredTaskEventDto`, `StructuredTaskMapper`, and (`task` package) `TaskIngestionPersistenceHandler`
  — the shared structured-task payload shape, its validation rules, and how it maps to a `Task`.
  Validation accepts any `sourceType` that is a `SourceType` constant, so it is not tied to a
  particular platform.
- **Adding a new source** (e.g. WhatsApp): add the constant to `SourceType` (and the DB enum), and
  it flows through the existing consumer as-is — no new DTO, validator, mapper, topic or
  `@KafkaListener`. Only a source whose payload genuinely differs from the shared structured-task
  shape would need more than that.

### Idempotency

The idempotency key is `(tenant_id, source_type, source_ref)`, where `source_ref` is the
deterministic identifier BRIDGE assigns to the source record (e.g. a GitHub issue/PR reference) —
not the Kafka message or the event's own `eventId` (which is only used as an audit correlation id
and may differ across redeliveries of the same underlying record).

A unique index (`uq_tasks_org_source_type_source_ref`) enforces this at the database level.
`TaskService.createTaskWithAudit` first checks for an existing task with that key; if two
deliveries race past that check, the second insert fails with a unique-constraint violation, which
`TaskIngestionPersistenceHandler` catches and resolves by looking up the row the other delivery just
created (no new `Task` and no duplicate `Audit` row are ever produced). Redelivery of the same
message therefore always converges on the single already-created task instead of failing or
duplicating it.

### Acknowledgment / offset commit

The consumer factory uses `AckMode.MANUAL_IMMEDIATE`: the Kafka offset is committed only after
`EventProcessingTemplate.process` finishes — i.e. after the task (and audit) are persisted, after a
non-retryable failure has been published to `.errors`, or after retries are exhausted and the
failure has been published to `.errors`. If the service crashes before that point (e.g. mid-retry),
the offset was never committed, so the message is redelivered to the consumer group on restart.
That redelivery is safe by construction: it goes through the same idempotency check described
above, so it either creates the task (if the earlier attempt never actually persisted it) or is
recognized as a duplicate and does nothing.

### Failure handling

- **Non-retryable** (malformed payload, validation failure): no DB write, no retry, message
  published to `<original-topic>.errors`, offset acknowledged immediately.
- **Transient technical failure** (`TransientProcessingException`, e.g. DB unavailable/timeout):
  retried with configurable exponential backoff (`inbox.kafka.retry.*` / `INBOX_KAFKA_RETRY_*` env
  vars); if all attempts fail, published to `.errors` and acknowledged.
- **Unexpected failure** (anything not classified as either of the above — a bug, an unmapped
  enum, a dangling foreign key, etc.): caught by the same template, published to `.errors` as
  `UNEXPECTED_FAILURE` without being retried, and acknowledged — so a single bad or unanticipated
  message can never block the partition indefinitely.

## Tests

```bash
mvn test
```
