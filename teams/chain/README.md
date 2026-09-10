# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

`TaskEligibilityService` implements the dependency/constraint update flow (`addDependency`,
`removeDependency`, `onTaskStatusChanged`, `recalculateTaskEligibility`, `onConstraintAdded`,
`onConstraintStatusChanged`) against in-memory repositories. It's a registered `@Service` bean -
`config/RepositoryConfig.java` wires the in-memory `TaskRepository`/`TaskDependencyRepository`
implementations as beans (seeded empty) so the whole thing is actually constructible by Spring,
not just unit-testable. No REST controllers are wired up yet (`controller/` is still empty) -
this stage is the eligibility engine, the Kafka event infrastructure described below, and their
tests.

`Task`/`TaskDependency`/`TaskStatus` and the `TaskRepository`/`TaskDependencyRepository`
contracts (with in-memory implementations) come from issue #66's branch
(`chain/ruthHoltzman/dependency-repository-66`). That branch also has a `TaskDependencyService`
(existence/self-dependency/duplicate-edge validation) that isn't merged here yet and overlaps
with some of what `TaskEligibilityService.addDependency`/`removeDependency` already do
(cycle detection, `blockingCount`) - reconcile the two once #66 lands.

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Spring Kafka
- Docker / Docker Compose

The DB choice for the project has not been decided yet. An in-memory H2 database is configured
as a placeholder purely so a `DataSource`/transaction manager exists for `@Transactional` -
`Task`/`TaskDependency` are plain DTOs (not JPA entities) and are **not** persisted to H2; real
data still lives in the in-memory `TaskRepository`/`TaskDependencyRepository` implementations,
and the idempotency store described below is in-memory only. None of this state survives a
restart. See [CLAUDE.md](CLAUDE.md) for the full rationale and open items.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

## Project structure

```
src/main/java/com/rnd/chain/
├── ChainApplication.java   # Spring Boot entry point
├── controller/             # empty - no REST endpoints yet
├── service/
│   └── TaskEligibilityService.java
├── repository/
│   ├── TaskRepository.java / InMemoryTaskRepository.java
│   └── TaskDependencyRepository.java / InMemoryTaskDependencyRepository.java
├── dto/
│   ├── Task.java, TaskDependency.java, TaskStatus.java, Constraint.java
│   └── ProcessedEvent.java # idempotency claim record
├── events/                 # Kafka publish/consume infrastructure - see below
│   ├── EventEnvelope.java
│   ├── EventPublisher.java / KafkaEventPublisher.java
│   ├── EventHandler.java
│   ├── ChainEventConsumer.java
│   ├── ChainEventTopicsProperties.java / ChainKafkaRetryProperties.java
│   ├── ChainKafkaErrorHandlingConfig.java
│   ├── ProcessedEventStore.java / InMemoryProcessedEventStore.java
│   └── payloads/
└── config/
    └── RepositoryConfig.java
src/main/resources/application.yml
src/test/java/com/rnd/chain/
├── service/
│   ├── TaskEligibilityServiceTest.java
│   └── TaskEligibilityServiceConcurrencyTest.java   # real threads, real in-memory repos, no mocks
└── repository/
    ├── InMemoryTaskRepositoryTest.java
    └── InMemoryTaskDependencyRepositoryTest.java
```

## Kafka event infrastructure

Chain can publish and consume Kafka events using a shared `EventEnvelope<T>` (event type,
event ID, timestamp, correlation ID, payload).

**Publishing** goes through `EventPublisher` / `KafkaEventPublisher`, which serializes an
`EventEnvelope` and sends it to the topic configured for its event type under
`chain.kafka.topics` in `application.yml`. `EventPublisher.publish(event)` itself is still
fire-and-forget by design - a failed publish is logged, not retried or persisted for a later
attempt, and business logic is not expected to know or care. A stronger delivery guarantee (e.g. a
transactional outbox) is out of scope for this infrastructure layer.

**Consuming** is handled by `ChainEventConsumer`, which routes each incoming message to the
`EventHandler<T>` bean registered for its `eventType`. It is disabled by default
(`@ConditionalOnProperty` on `chain.kafka.consumed-topics`) until a real topic is configured -
today, Chain doesn't consume from anywhere in any environment. `chain.kafka.consumed-topics`
accepts a comma-separated list (e.g. `mind.dependency.proposed,inbox.task.status.updated`) to
subscribe to more than one topic at once - needed since Chain consumes from multiple services
(Mind, Inbox).

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
| `chain.kafka.consumed-topics` | Topic(s) `ChainEventConsumer` listens on - comma-separated for more than one | unset (consumer disabled) |
| `KAFKA_BOOTSTRAP_SERVERS` (env var, not `chain.*`) | Kafka broker(s) address | `localhost:9092` |
| `chain.kafka.consumer-group-id` | Consumer group ID | `${spring.application.name}` |
| `chain.kafka.retry.max-attempts` | Retries after the initial delivery attempt, before falling back to the DLT | `2` |
| `chain.kafka.retry.interval-ms` | Milliseconds between retry attempts | `2000` |
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
Set `KAFKA_BOOTSTRAP_SERVERS` in `.env` to point the container at a real broker - `localhost:9092`
(the default) only works if Kafka runs on the same host network as this container.

To stop:

```bash
docker compose down
```

## Tests

```bash
mvn test
```

Kafka-related tests use `@EmbeddedKafka` (`spring-kafka-test`) - no external Kafka broker or
Docker is needed to run the test suite.
