# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet, aside
from the Kafka event infrastructure described below - the goal of this stage is a clean, working
Maven/Spring Boot project that later work can build on.
The dependency graph persistence layer (`DependencyService`, `TaskRepository`,
`TaskDependencyRepository` and related exceptions) is tracked separately under issue #66 and is
not part of this module yet.

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Spring Kafka
- Docker / Docker Compose

No database is live yet in any environment - the DB choice for the project's own persistence
(beyond the outbox groundwork below) has not been decided. All state (including the idempotency
store described below) is in-memory only and does not survive a restart. Postgres/JPA/Flyway are
on the classpath (see `pom.xml`) and `spring.autoconfigure.exclude` in `application.yml` disables
them by default for exactly this reason - see "Outbox groundwork" below.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

## Project structure

```
src/main/java/com/rnd/chain/
├── ChainApplication.java   # Spring Boot entry point
├── controller/
├── service/
├── repository/
├── dto/
│   └── ProcessedEvent.java # idempotency claim record
├── events/                 # Kafka publish/consume infrastructure - see below
│   ├── EventEnvelope.java
│   ├── EventPublisher.java / KafkaEventPublisher.java
│   ├── EventHandler.java
│   ├── ChainEventConsumer.java
│   ├── ChainEventTopicsProperties.java / ChainKafkaRetryProperties.java
│   ├── ChainKafkaErrorHandlingConfig.java
│   ├── ProcessedEventStore.java / InMemoryProcessedEventStore.java
│   ├── ChainOutboxEvent.java / ChainOutboxStatus.java / ChainOutboxEventRepository.java
│   ├── ChainOutboxWorker.java / ChainOutboxCleanupJob.java / ChainOutboxProperties.java
│   └── payloads/
└── config/
src/main/resources/application.yml
src/main/resources/db/migration/       # Flyway - chain_outbox table
src/test/java/com/rnd/chain/
```

## Kafka event infrastructure

Chain can publish and consume Kafka events using a shared `EventEnvelope<T>` (event type,
event ID, timestamp, correlation ID, payload).

**Publishing** goes through `EventPublisher` / `KafkaEventPublisher`, which serializes an
`EventEnvelope` and sends it to the topic configured for its event type under
`chain.kafka.topics` in `application.yml`. `EventPublisher.publish(event)` itself is still
fire-and-forget by design - a failed publish is logged, not retried or persisted for a later
attempt, and business logic is not expected to know or care.

### Outbox groundwork

A future `@Transactional` business service that cannot tolerate `publish()`'s fire-and-forget risk
(state committed, publish fails, no compensating action) can instead save a `ChainOutboxEvent` via
`ChainOutboxEventRepository` in the same transaction as its own state change. `ChainOutboxWorker`
then claims `PENDING` rows (`FOR UPDATE SKIP LOCKED`) on a schedule and publishes each one through
`KafkaEventPublisher`'s internal synchronous path, marking the row `PUBLISHED` or scheduling a
backed-off retry - all inside one transaction, so the business write and its outbound event can
never diverge. `ChainOutboxCleanupJob` periodically deletes old `PUBLISHED`/`FAILED` rows.

This is currently **dormant groundwork, not a live feature**: Chain has no database in production
and no business entity of its own to write alongside an outbox row yet (see the note above). The
entity, repository, migration, worker and cleanup job all exist and are tested (Testcontainers -
see Tests below), but `spring.autoconfigure.exclude` in `application.yml` keeps DataSource/JPA/
Flyway off by default, so nothing here activates - and `ChainOutboxWorker`/`ChainOutboxCleanupJob`
are themselves `@ConditionalOnBean(ChainOutboxEventRepository.class)`, so they don't even get
constructed while that's the case. An environment that does have Postgres clears that exclusion
and sets `spring.datasource.*` (see `application.yml`'s comment) to turn it on for real.

**Consuming** is handled by `ChainEventConsumer`, which routes each incoming message to the
`EventHandler<T>` bean registered for its `eventType`. It is disabled by default
(`@ConditionalOnProperty` on `chain.kafka.consumed-topics`) until a real topic is configured -
today, Chain doesn't consume from anywhere in any environment.

**Error handling**: malformed or unroutable messages (bad JSON, missing/invalid fields, a payload
that doesn't match the target handler's type) fail fast with `NonRetryableEventException` and go
straight to the topic's dead-letter topic (`<topic>.DLT`). Any other failure (e.g. a future
handler's transient dependency issue) is retried before falling back to the DLT, per
`chain.kafka.retry.max-attempts` / `chain.kafka.retry.interval-ms` (default: 3 attempts total,
2 seconds apart) - see `ChainKafkaErrorHandlingConfig`.

**Idempotency**: each event is claimed via `ProcessedEventStore` before its handler runs, so a
duplicate delivery (retry, redelivery, or multiple partitions) is only processed once. The claim
is released if the handler fails, so a genuine retry can still process the event again. The
current implementation, `InMemoryProcessedEventStore`, only guards a single running instance and
loses all claim history on restart - it is not safe for production or for multiple instances of
this service running concurrently. See its Javadoc for what a claim does and doesn't guarantee.

### Configuration

| Property | Purpose | Default |
| --- | --- | --- |
| `chain.kafka.topics.<eventType>` | Topic to publish a given event type to | none - required per event type |
| `chain.kafka.consumed-topics` | Topic(s) `ChainEventConsumer` listens on | unset (consumer disabled) |
| `chain.kafka.consumer-group-id` | Consumer group ID | `${spring.application.name}` |
| `chain.kafka.retry.max-attempts` | Retries after the initial delivery attempt, before falling back to the DLT | `2` |
| `chain.kafka.retry.interval-ms` | Milliseconds between retry attempts | `2000` |
| `chain.events.processed-store` | Which `ProcessedEventStore` implementation is active | `in-memory` |
| `spring.autoconfigure.exclude` | Set (empty) to turn on DataSource/JPA/Flyway - see "Outbox groundwork" above | DataSource/Hibernate/Flyway autoconfig excluded |
| `chain.outbox.worker.batch-size` | Max rows `ChainOutboxWorker` claims per poll | `20` |
| `chain.outbox.worker.poll-interval-ms` | Delay between `ChainOutboxWorker` polls | `5000` |
| `chain.outbox.worker.max-retries` | Publish attempts before a row is marked `FAILED` | `3` |
| `chain.outbox.worker.backoff-base-seconds` / `backoff-max-seconds` | Exponential retry backoff bounds | `10` / `120` |
| `chain.outbox.cleanup.interval-ms` | Delay between `ChainOutboxCleanupJob` runs | `3600000` |
| `chain.outbox.cleanup.retention-days` | How long `PUBLISHED`/`FAILED` rows are kept before deletion | `7` |

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 21.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8081

To just build the jar:

```bash
mvn clean package
java -jar target/chain-0.1.0-SNAPSHOT.jar
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

The app will be available at http://localhost:8081 (or whatever `SERVER_PORT` is set to in `.env`).

To stop:

```bash
docker compose down
```

## Tests

```bash
mvn test
```

Kafka-related tests use `@EmbeddedKafka` (`spring-kafka-test`) - no external Kafka broker is
needed to run the test suite.

The outbox groundwork's `*IntegrationTest` classes under `com.rnd.chain.events`
(`ChainOutboxEventRepositoryIntegrationTest`, `ChainOutboxCleanupJobIntegrationTest`) use
Testcontainers to start a real Postgres - **Docker must be running locally** for those two
specifically; every other test, including `ChainOutboxWorkerTest`'s mocked unit tests, does not
need it.
