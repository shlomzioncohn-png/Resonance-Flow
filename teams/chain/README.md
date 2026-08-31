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

No database is configured yet - the DB choice for the project has not been decided. All state
(including the idempotency store described below) is in-memory only and does not survive a
restart.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

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
│   ├── ChainKafkaErrorHandlingConfig.java
│   ├── ProcessedEventStore.java / InMemoryProcessedEventStore.java
│   └── payloads/
└── config/
src/main/resources/application.yml
src/test/java/com/rnd/chain/
```

## Kafka event infrastructure

Chain can publish and consume Kafka events using a shared `EventEnvelope<T>` (event type,
event ID, timestamp, correlation ID, payload).

**Publishing** goes through `EventPublisher` / `KafkaEventPublisher`, which serializes an
`EventEnvelope` and sends it to the topic configured for its event type under
`chain.kafka.topics` in `application.yml`. Publishing is currently fire-and-forget - a failed
publish is logged, not retried or persisted for a later attempt (no Outbox pattern yet; see
`KafkaEventPublisher`'s TODO for the tradeoff and when this should be revisited).

**Consuming** is handled by `ChainEventConsumer`, which routes each incoming message to the
`EventHandler<T>` bean registered for its `eventType`. It is disabled by default
(`@ConditionalOnProperty` on `chain.kafka.consumed-topics`) until a real topic is configured -
today, Chain doesn't consume from anywhere in any environment.

**Error handling**: malformed or unroutable messages (bad JSON, missing/invalid fields, a payload
that doesn't match the target handler's type) fail fast with `NonRetryableEventException` and go
straight to the topic's dead-letter topic (`<topic>.DLT`). Any other failure (e.g. a future
handler's transient dependency issue) is retried up to 3 times, 2 seconds apart, before falling
back to the DLT - see `ChainKafkaErrorHandlingConfig`.

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
| `chain.events.processed-store` | Which `ProcessedEventStore` implementation is active | `in-memory` |

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
