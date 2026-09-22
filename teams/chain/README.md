# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

Implements the graph traversal API: given a task id, walk its direct or transitive dependencies,
dependents, ancestors, children, or descendants, or fetch a combined trace across both axes in
one call. See "Graph Traversal API" below for the full endpoint list.

`TaskEligibilityService` implements the dependency/constraint update flow (`addDependency`,
`removeDependency`, `onTaskStatusChanged`, `recalculateTaskEligibility`, `onConstraintAdded`,
`onConstraintStatusChanged`) against the same in-memory repositories. `config/MockDataConfig.java`
wires the in-memory `TaskRepository`/`TaskDependencyRepository` implementations as beans (seeded
empty) so both it and `TaskTraversalService` are constructible via normal Spring dependency
injection - not just unit-testable. Each bean is guarded with `@ConditionalOnMissingBean`, so once
a real DB-backed `TaskRepository`/`TaskDependencyRepository` is registered, it overrides the mock
instead of colliding with it - no manual removal of this config needed when that lands. Chain also
publishes and consumes Kafka events - see "Kafka event infrastructure" below.

The in-memory repositories (`TaskRepository`, `TaskDependencyRepository` and their
implementations) and the `Task`/`TaskDependency`/`TaskStatus` DTOs are sourced from
[`chain/ruthHoltzman/dependency-repository-66`](../../tree/chain/ruthHoltzman/dependency-repository-66)
(originally tracked under issue #66), taken as-is rather than authored in this module -
`InMemoryTaskRepository` is the one exception, hand-authored here (see its class Javadoc) after
no branch had an indexed `findByParentTaskId`. That branch also contributed `TaskDependencyService`
(existence/self-dependency/duplicate-edge validation), which lives alongside
`TaskEligibilityService` here and overlaps with some of what
`TaskEligibilityService.addDependency`/`removeDependency` already do (cycle detection,
`blockingCount`) - reconciling the two (having `TaskEligibilityService` delegate raw edge
validation/CRUD to `TaskDependencyService` and keep only cycle detection + counters) is still an
open follow-up, not done as part of this merge. No real database is wired up yet; these
repositories are in-memory only.

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Spring Kafka
- Docker / Docker Compose

Postgres, via plain JDBC (`spring-boot-starter-jdbc` + `JdbcTemplate`), not JPA/Hibernate -
team-lead decision, 2026-09-17: Chain's repositories are already hand-written interfaces, not
Spring Data, and the operations Chain needs (upsert, atomic counter updates, row locking) are raw
SQL anyway. So far this only covers `Constraint` (issue #378, see "Constraint persistence" below)
- `Task`/`TaskDependency` stay in the in-memory `TaskRepository`/`TaskDependencyRepository`
implementations until issue #377 lands, and the idempotency store described below is in-memory
only. See [CLAUDE.md](CLAUDE.md) for the full rationale and open items.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

## Project structure

```
src/main/java/com/rnd/chain/
├── ChainApplication.java   # Spring Boot entry point
├── controller/             # ChainController - the graph traversal API
├── service/                # TaskTraversalService (traversal), TaskEligibilityService
│                           # (dependency/constraint update flow), TaskDependencyService
│                           # (edge validation/CRUD, issue #66)
├── repository/             # TaskRepository / TaskDependencyRepository + in-memory impls;
│                           # ConstraintRepository + PostgresConstraintRepository (issue #378)
├── dto/                    # Task, TaskDependency, TaskStatus, Constraint, ApiError,
│                           # TaskResponse, TaskTraceResponse, ProcessedEvent (idempotency
│                           # claim record)
├── events/                 # Kafka publish/consume infrastructure - see below
│   ├── EventEnvelope.java
│   ├── EventPublisher.java / KafkaEventPublisher.java
│   ├── EventHandler.java
│   ├── ChainEventConsumer.java
│   ├── ChainEventTopicsProperties.java / ChainKafkaRetryProperties.java
│   ├── ChainKafkaErrorHandlingConfig.java
│   ├── ProcessedEventStore.java / InMemoryProcessedEventStore.java
│   └── payloads/
├── exception/              # GlobalExceptionHandler, NotFoundException/BadRequestException +
│                           # TaskNotFoundException/DuplicateDependencyException (adopted from
│                           # Riki's branch - see the 2026-09-08 fixes below), plus
│                           # DependencyNotFoundException/InvalidDependencyException (issue #66)
└── config/                 # MockDataConfig - empty-seeded repository beans;
                            # ConstraintRepositoryConfig - the real Postgres-backed
                            # ConstraintRepository bean (issue #378)
src/main/resources/application.yml
src/main/resources/db/migration/  # Flyway - V1__create_task_constraint_table.sql (issue #378)
src/test/java/com/rnd/chain/
├── controller/ChainControllerTest.java
├── service/                # TaskTraversalServiceTest, TaskTraversalPerformanceTest,
│                           # TaskEligibilityServiceTest, TaskEligibilityServiceConcurrencyTest
│                           # (real threads, real in-memory repos, no mocks), TaskDependencyServiceTest
├── repository/             # TaskRepositoryContractTest (shared contract, run against every
│                           # implementation) + InMemoryTaskRepositoryTest,
│                           # InMemoryTaskDependencyRepositoryTest, PostgresConstraintRepositoryTest
│                           # (issue #378 - see that section below for why it can't pass yet)
├── dto/TaskTest.java, TaskDependencyTest.java
├── events/                 # ChainEventConsumerTest, ChainEventConsumerIntegrationTest
│                           # (@EmbeddedKafka), EventEnvelopeTest, InMemoryProcessedEventStoreTest,
│                           # KafkaEventPublisherTest
├── config/MockDataConfigTest.java
└── exception/GlobalExceptionHandlerTest.java, ExceptionConstructorTest.java
```

## Graph Traversal API

All routes are read-only (`GET`) and take a task id as the path variable. A bare path is the
direct/one-level relationship; `/transitive` is the full closure over that same relation.
Ancestors has no `/transitive` variant - the ancestor chain already is the transitive closure.

| Method | Path                              | Returns                                    |
|--------|------------------------------------|---------------------------------------------|
| GET    | `/tasks/{id}/dependencies`         | Direct dependencies                          |
| GET    | `/tasks/{id}/dependencies/transitive` | All (transitive) dependencies             |
| GET    | `/tasks/{id}/dependents`           | Direct dependents                            |
| GET    | `/tasks/{id}/dependents/transitive`   | All (transitive) dependents                |
| GET    | `/tasks/{id}/ancestors`            | Full ancestor chain (hierarchy)              |
| GET    | `/tasks/{id}/children`             | Direct children (hierarchy)                  |
| GET    | `/tasks/{id}/descendants`          | All descendants (hierarchy)                  |
| GET    | `/tasks/{id}/trace`                | Combined dependency graph + hierarchy trace  |

Each route returns a `TaskResponse` list (or, for `/trace`, a `TaskTraceResponse` combining
both axes) - see `dto/TaskResponse.java` and `dto/TaskTraceResponse.java` for the exact shape.
An unknown task id returns `404` with an `ApiError` body; an invalid (non-UUID) id returns `400`.

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

## Dependency graph persistence (Issue #66)

`TaskDependencyRepository` / `InMemoryTaskDependencyRepository` (in `repository/`) are a pure
CRUD store for dependency edges (`taskId` depends on `dependsOnTaskId`): `findByTaskId`,
`findByDependsOnTaskId`, `save`, `delete`. `save` is idempotent - saving the same edge twice does
not create a duplicate row - and `delete` is a no-op if the edge doesn't exist. This layer does not
validate task existence or reject self-dependencies; it is a thin storage abstraction, currently
backed by an in-memory dual adjacency map (`ConcurrentHashMap`) until a real DB is chosen.

`TaskDependencyService` (in `service/`) sits on top of it and enforces the business rules from
issue #66:

```java
addDependency(taskId, dependsOnTaskId)   // throws InvalidDependencyException on self-dependency,
                                          // TaskNotFoundException if either task doesn't exist,
                                          // DuplicateDependencyException if the edge already exists
removeDependency(taskId, dependsOnTaskId) // throws DependencyNotFoundException if the edge doesn't exist
getDependencies(taskId)                   // ids of tasks that block taskId (empty list if none)
getDependents(taskId)                     // ids of tasks that taskId blocks (empty list if none)
```

Callers that need those business rules enforced (duplicate edges, self-dependency, missing tasks)
should go through `TaskDependencyService` rather than calling the repository directly.

`TaskRepository` / `InMemoryTaskRepository` are also included here (a compatible implementation
matching the interface used elsewhere in the module) since `TaskDependencyService` needs task
existence checks.

## Constraint persistence (Issue #378)

`ConstraintRepository` / `PostgresConstraintRepository` (in `repository/`) give `Constraint` a
real Postgres-backed store: `findByTaskId`, `findById`, `save`, `updateStatus`. Plain JDBC, not
JPA - see "Stack" above. `reason` (`Map<String, Object>`) is stored as `jsonb`, converted via
Jackson + `PGobject` (`toJsonb`/`fromJsonb`). `type`/`status` are `VARCHAR` + a `CHECK` constraint
listing the valid enum values, not a native Postgres enum type - works identically regardless of
persistence technology, and is simpler to extend later (`DROP`/`ADD CONSTRAINT` vs.
`ALTER TYPE ... ADD VALUE`). Registered as a bean via `config/ConstraintRepositoryConfig.java` (a
`@Bean` factory method, not `@Repository` directly on the class - the latter was tried first and
silently failed to self-register; see [CLAUDE.md](CLAUDE.md) "Open items").

**Depends on issue #377 (Task/TaskDependency Postgres persistence), which is not done yet.**
`V1__create_task_constraint_table.sql`'s `task_id` column references `task(id)`, a table #377 is
supposed to create - team decision: build the Constraint side now rather than wait, accepting
that the migration won't apply (and `PostgresConstraintRepositoryTest` won't pass) until #377
lands. Verified this is the actual failure mode (`Connection to localhost:5432 refused` locally,
with no Postgres running yet either) - not a wiring bug.

**Wired into `TaskEligibilityService`** (2026-09-22). `onConstraintAdded`/`onConstraintStatusChanged`
persist through `ConstraintRepository` and derive whether `Task.openConstraintCount` actually needs
adjusting from a DB-verified fact about the write itself, not a `SELECT COUNT(*)`, not the
`+1`/`-1` delta pattern `blockingCount` uses, and not an in-memory idempotency ledger:

- `save` reports whether Postgres genuinely inserted a new row, via `RETURNING (xmax = 0)` -
  Postgres's own signal for "this row is new, not the result of the `ON CONFLICT` branch" - at no
  extra query cost, since it rides along on the write that already has to happen.
- `updateStatus` atomically returns the row's status immediately before this call (a CTE under
  `FOR UPDATE`, read and written in one statement), so a redelivered status-change event is
  detected from real state instead of a caller-supplied `previousStatus`.

Write order is Postgres first, then the in-memory `Task` counter, never the reverse - a
crash/failure between the two steps means a redelivered event re-runs the (idempotent) repository
call, which correctly reports "already there," and the counter update proceeds correctly on
retry. The reverse order is worse: skipping the repository call once a caller believes an event
was "already handled" can permanently lose a `Constraint` row, not just delay a counter update.

## Code review fixes (2026-09-10, PR #251)

Addressed 5 of 13 findings from this round's review (discussed and planned one-by-one with the
reviewer before implementation - see [PLAN.md](PLAN.md) for the full write-up, including the 8
findings deliberately not acted on here, documented-only, or handed off to the branch that
originated the file in question):

- **`GlobalExceptionHandler.handleValidationError` no longer assumes a field-level error exists.**
  `MethodArgumentNotValidException` can carry only class-level errors (a cross-field constraint),
  leaving `getFieldErrors()` empty - the unconditional `.get(0)` would throw
  `IndexOutOfBoundsException` inside the handler itself, falling through to the generic 500
  handler instead of the intended 400. Now falls back to `getAllErrors()` (guaranteed non-empty
  whenever the exception exists) when there's no field-level error.
- **`InMemoryTaskRepository.childrenByParentId` is no longer keyed by `Optional<UUID>`** - a
  textbook `Optional` misuse (return-type-only by convention, never a collection key), used only
  to work around `ConcurrentHashMap`'s no-null-keys restriction. Root tasks turn out to never need
  indexing at all - nothing in this codebase queries `findByParentTaskId(null)` - so the map is
  now a plain `Map<UUID, Set<UUID>>` and root tasks are simply not indexed.
- **Empty adjacency-map entries are now pruned** in both `InMemoryTaskDependencyRepository.delete`
  and `InMemoryTaskRepository.reindexChild`, instead of leaking one permanent empty-`Set` entry
  per id ever touched under write churn.
- **The performance benchmark (`TaskTraversalPerformanceTest`) now asserts its p95 target**
  instead of only printing pass/fail - it previously ran on every default `mvn test` for zero
  regression protection, since nothing ever failed the build on a miss.
- **`TaskTraversalService.collectReachableIds`'s `boolean directOnly` flag is gone**, replaced by
  two named methods (`collectDirectNeighborIds`/`collectAllReachableIds`) sharing a private
  BFS-step helper - each of the 6 call sites now reads self-documenting instead of requiring a
  check of the method signature to know what `true`/`false` meant.

Full test suite (47 tests, `mvn clean test`) passes.
