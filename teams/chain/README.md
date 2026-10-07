
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
open follow-up, not done as part of this merge. **Postgres-backed implementations now exist**
(`Postgres*Repository` in `repository/`) alongside the in-memory ones - see "Dependency graph
persistence" below.

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Spring Kafka, with Confluent Protobuf (de)serialization against a Schema Registry
- Lombok (`Task`/`TaskDependency` accessors/builder/equals-hashCode; `Constraint`'s
  equals/hashCode are hand-written, see the 2026-09-08 fixes below)
- Docker / Docker Compose

**PostgreSQL** is the chosen DB (issue #384) - own `chain` database and own `chain` DB user on the
shared cluster/local Postgres instance (not the shared `postgres` superuser, not Inbox's
`resonance_flow` database - see PR #417). Flyway owns the schema
(`src/main/resources/db/migration/`); `Task`/`TaskDependency`/`DependencyHistoryEntry` stay plain
DTOs, mapped to/from JPA entities entirely inside the `Postgres*Repository` adapters - no entity
ever crosses a service boundary. `MockDataConfig`'s in-memory beans back off automatically
(`@ConditionalOnMissingBean`) once a real `DataSource` is reachable. The `Constraint` repository
is the exception to the JPA approach: plain JDBC (`JdbcTemplate`), per the team-lead decision of
2026-09-17 (see "Constraint persistence" below). See [CLAUDE.md](CLAUDE.md) for prior
rationale/open items. The idempotency store described below is still in-memory only, unaffected
by this.

The DB password is not a `values.yaml` literal - it's an auto-generated `chain-db-credentials`
Secret (`templates/services/chain/configmap.yaml`, kept stable across upgrades via `lookup`),
mounted as a file into both Chain's own pod (at `/etc/resonance-flow/secrets/chain-db/password`)
and the Postgres primary as `CHAIN_DB_PASSWORD` (which consumes it in the `CREATE USER` init
script). Chain's mounted `config.yaml` uses Spring's `configtree:` support
(`spring.config.import`) to bind that file to the `${password}` placeholder - not an env var, no
app-code change needed. Inbox's own Postgres password isn't wired this way yet (still
`postgresql.auth.postgresPassword` as a plaintext value) - same gap, not addressed here.

**Kafka topics** (also wired in `components.chain.config.data.kafka` / the same configmap):

| Topic | Direction | Drives |
|---|---|---|
| `task.dependencies.proposed` | consume (Mind) | `addDependency` |
| `task.dependencies.removed` | consume (Mind) | `removeDependency` |
| `task.status.updated` | consume (Inbox) | `onTaskStatusChanged` |
| `task.scheduling.updated` | produce | consumed by Inbox |

`task.dependencies.removed` and `task.status.updated` are still **PROPOSED**, not reviewed
contracts yet - see the draft `.proto` schemas from the Chain team on #384. `task.status.updated`
in particular assumes Inbox's full 8-value status enum, mapped internally to Chain's simplified
3-value model - not yet confirmed with Inbox.

`onConstraintAdded`/`onConstraintStatusChanged` stay REST-triggered through Chain's own API for
now - draft `task.constraints.proposed`/`task.constraints.status.updated` schemas exist in case
this becomes event-driven later, but there's no topic wired for them today.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

`TaskEligibilityService` is registered as a Spring bean (`@Service`); `config/RepositoryConfig`
wires the in-memory repositories as the beans it needs, so `@Transactional` has an actual AOP
proxy to attach to (still a no-op-in-practice transaction today, since `Task`/`TaskDependency`
aren't JPA entities - see CLAUDE.md).

## Project structure

```
src/main/java/com/rnd/chain/
├── ChainApplication.java   # Spring Boot entry point
├── controller/             # ChainController - the graph traversal API
├── service/                # TaskTraversalService (traversal), TaskEligibilityService
│                           # (dependency/constraint update flow), TaskDependencyService
│                           # (edge validation/CRUD, issue #66)
├── repository/             # TaskRepository / TaskDependencyRepository / DependencyHistoryRepository
│                           # / TransactionRunner + in-memory impls, Postgres*Repository /
│                           # JpaTransactionRunner (real impls), entity/ (JPA entities), jpa/
│                           # (Spring Data interfaces); ConstraintRepository +
│                           # PostgresConstraintRepository (JDBC, issue #378)
├── dto/                    # Task, TaskDependency, TaskStatus, Constraint, ApiError,
│                           # TaskResponse, TaskTraceResponse, ProcessedEvent (idempotency
│                           # claim record)
├── events/                 # Kafka consume/publish infrastructure - see below
│   ├── TaskCreatedEventHandler.java / TaskUpdatedEventHandler.java /
│   │   DependencyProposedEventHandler.java   # one @KafkaListener per inbound topic
│   ├── ChainEventProcessingTemplate.java     # shared claim/logging skeleton
│   ├── EventFields.java                      # UUID / Timestamp parsing shared by the handlers
│   ├── ChainProtobufKafkaConfig.java         # Protobuf consumer/producer factories
│   ├── EventPublisher.java / KafkaEventPublisher.java
│   ├── ChainEventTopicsProperties.java
│   ├── ChainTransientProcessingException.java / ReferencedTaskNotFoundException.java
│   ├── NonRetryableEventException.java / InboxTaskStatusMapper.java
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
src/main/resources/db/migration/  # Flyway migrations (V1 schema, V2 dependency_history,
                                  # V3 task_constraint - issue #378)
src/test/java/com/rnd/chain/
├── controller/ChainControllerTest.java
├── service/                # TaskTraversalServiceTest, TaskTraversalPerformanceTest,
│                           # TaskEligibilityServiceTest, TaskEligibilityServiceConcurrencyTest /
│                           # TaskEligibilityServicePostgresConcurrencyTest (real threads, no
│                           # mocks), TaskDependencyServiceTest / TaskDependencyServicePostgresIntegrationTest
├── repository/             # TaskRepositoryContractTest (shared contract, run against every
│                           # implementation) + InMemoryTaskRepositoryTest,
│                           # InMemoryTaskDependencyRepositoryTest, InMemoryDependencyHistoryRepositoryTest,
│                           # InMemoryTransactionRunnerTest, and the Postgres-backed equivalents
│                           # (PostgresTaskRepositoryContractTest/Test, PostgresTaskDependencyRepositoryTest,
│                           # PostgresDependencyHistoryRepositoryTest - need a real Postgres reachable, see
│                           # "Tests" below); PostgresConstraintRepositoryTest and
│                           # PostgresConstraintRepositoryConcurrencyTest (issue #378, @Tag("postgres"))
├── dto/ConstraintTest.java, TaskTest.java, TaskDependencyTest.java,
│                           # TaskTestFixtures.java (shared Task.Builder defaults)
├── events/                 # *HandlerTest and *HandlerIntegrationTest (@EmbeddedKafka) per inbound
│                           # topic, ChainEventProcessingTemplateTest, InboxTaskStatusMapperTest,
│                           # KafkaEventPublisherTest, ChainProtobufKafkaConfigTest
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

Chain consumes three Protobuf topics and publishes one flat-JSON topic. Each inbound topic has its
own `@KafkaListener` handler, and the handlers share the claim/logging skeleton in
`ChainEventProcessingTemplate`.

| Topic | Producer | Handler | Idempotency | Retries before `.errors` |
| --- | --- | --- | --- | --- |
| `task.created` | Inbox | `TaskCreatedEventHandler` | state-based (no event id) | 3 attempts, 2s apart |
| `task.updated` | Inbox | `TaskUpdatedEventHandler` | state-based, last-write-wins on `updated_at` | 3 attempts, 2s apart |
| `task.dependencies.proposed` | Mind | `DependencyProposedEventHandler` | `event_id` claim in `ProcessedEventStore` | 5 attempts, 5s apart |

**Retry and dead-lettering**: retries are non-blocking, via `@RetryableTopic`, so a message waiting
to retry never stalls the rest of its partition. Every exception is retried except
`NonRetryableEventException` (malformed messages, cycles), which goes straight to `<topic>.errors`.
Retries are bounded (see the table), so an unexpected bug costs a few delayed attempts before it
lands on `<topic>.errors`. A typical retried case is `ReferencedTaskNotFoundException`, raised when
a referenced task has not arrived yet.

**Offsets**: auto-commit is disabled and each listener uses `AckMode.RECORD`. An offset is committed
only after its record is handled or handed to its retry/DLT topic, so a crash mid-processing
redelivers the record instead of losing it.

**Idempotency**: `task.dependencies.proposed` is deduplicated by `event_id`. The claim is released if
the handler fails, so a retry can process the event again. `task.created` and `task.updated` carry no
event id, so they rely on their state changes being safe to repeat. The current
`InMemoryProcessedEventStore` guards a single running instance only and loses all claims on restart,
so it is not safe for production or for several instances of this service. See its Javadoc for what
a claim does and doesn't guarantee.

**Status mapping**: `InboxTaskStatusMapper` collapses Inbox's 8 task statuses into Chain's 3.
`DONE`, `CANCELLED` and `REJECTED` map to `DONE`, because all three release dependents. `IN_PROGRESS`
maps to `ACTIVE`, and every other status maps to `PENDING`.

**Publishing**: the only publisher is `KafkaSchedulingUpdatePublisher` (`com.rnd.chain.events`, the
same package as the consumers), used by `SchedulingUpdateService`, for `task.scheduling.updated`. It
sends `TaskSchedulingUpdatedEvent` as JSON through the `KafkaTemplate` defined in
`ChainProtobufKafkaConfig`. That JSON is interim until the Protobuf contract is settled (#545).

**Schemas**: the `.proto` sources live in `src/main/proto/` and are committed. Messages are
validated against the Schema Registry at `SCHEMA_REGISTRY_URL`. Tests use a mock registry
(`mock://...`), so no registry process is needed.

### Configuration

| Property | Purpose | Default |
| --- | --- | --- |
| `chain.kafka.topics.task-created` (env `TASK_CREATED_TOPIC`) | Topic consumed for `task.created` | `task.created` |
| `chain.kafka.topics.task-updated` (env `TASK_UPDATED_TOPIC`) | Topic consumed for `task.updated` | `task.updated` |
| `chain.kafka.topics.dependencies-proposed` (env `DEPENDENCIES_PROPOSED_TOPIC`) | Topic consumed for `task.dependencies.proposed` | `task.dependencies.proposed` |
| `chain.kafka.topics.scheduling-updated` (env `CHAIN_SCHEDULING_UPDATED_TOPIC`) | Topic published to for `task.scheduling.updated` | `task.scheduling.updated` |
| `KAFKA_BOOTSTRAP_SERVERS` (env var, not `chain.*`) | Kafka broker(s) address | `localhost:9092` |
| `chain.kafka.consumer-group-id` (env `KAFKA_CONSUMER_GROUP_ID`) | Consumer group ID | `chain-service` |
| `chain.kafka.schema-registry-url` (env `SCHEMA_REGISTRY_URL`) | Schema Registry URL (ccompat API) | `http://localhost:8082/apis/ccompat/v6` |
| `chain.events.processed-store` | Which `ProcessedEventStore` implementation is active | `in-memory` |

Retry attempts and backoff are not properties. Each handler sets them on its own `@RetryableTopic`
annotation.

Broker security (SASL, TLS) is set through Spring's `spring.kafka.properties.*`, for example
`spring.kafka.properties.security.protocol=SASL_PLAINTEXT`. Every consumer factory and producer
template starts from those properties, so the same settings apply everywhere.

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
(the default) only works if Kafka runs on the same host network as this container. Set
`SCHEMA_REGISTRY_URL` the same way, since the default `localhost:8082` is not reachable from inside
the container either.

To stop:

```bash
docker compose down
```

## Tests

```bash
mvn test
```

Includes `TaskEligibilityServiceConcurrencyTest`, which runs `TaskEligibilityService` against the
real in-memory repositories (not mocks) under actual concurrent load, so lock-related races have
a chance to surface.

## Code review fixes (2026-08-30, PR #114 second review round)

Addressed all findings from the review, directly on this branch:

- **`recalculateAndSave` now always saves.** It previously only called `taskRepository.save()`
  when `isReady` flipped, silently dropping counter updates (e.g. `blockingCount` 2 → 1) that
  don't cross the ready threshold. In-memory repos masked this because the stored instance was
  mutated in place regardless; a real persistence layer would have lost the update.
- **`addDependency`/`removeDependency` now also take the per-task lock** (`lockFor(taskId)`,
  nested inside `dependencyGraphLock`) around their counter mutation, closing a gap where they
  weren't mutually exclusive with `onTaskStatusChanged`/`onConstraintAdded`/
  `onConstraintStatusChanged` on the same task.
- **`TaskEligibilityService` is now a registered Spring bean** (`@Service`), with
  `config/RepositoryConfig` providing the `TaskRepository`/`TaskDependencyRepository` beans its
  constructor needs. Previously it had no component annotation, so Spring never built an AOP
  proxy and `@Transactional` had no effect at all.
- **Per-task locks now use fixed-size striping (256 stripes)** instead of an unbounded
  `ConcurrentHashMap<UUID, Object>` that grew forever, one entry per task id ever touched.
- **`TaskDependency` is now immutable** (`taskId`/`dependsOnTaskId` are `final`, no setters, no
  no-arg constructor) - both fields feed `equals`/`hashCode`, so a mutable instance could
  otherwise become unreachable in a hash-based collection after being stored.
- **Repositories now return defensive copies.** `InMemoryTaskRepository.findById`/`findAllById`/
  `findByParentTaskId` return `Task.copyOf(...)` rather than the stored instance, and `save()`
  stores a copy too - a caller can no longer mutate a `Task` it received and have that change
  silently take effect without an explicit `save()`. This is a stand-in for the isolation a real
  persistence layer gives for free; see `Task.copyOf` and CLAUDE.md "Open items".
- **Removed the duplicate ad-hoc dependency queries** in `addDependency`/`removeDependency`'s
  cycle/edge checks by extracting shared `requireTask(UUID)` and `edgeExists(UUID, UUID)` helpers
  (cosmetic - no behavior change).
- **Closed a narrower race between `addDependency`/`removeDependency` and `onTaskStatusChanged`
  on a dependency's target-completion status.** Both now read/mutate the target's `DONE` status
  under the same `dependencyGraphLock`, so a new edge to a task that completes at the same moment
  is guaranteed to be counted by exactly one of the two code paths - never both (double-counted)
  and never neither (which would leave the dependent permanently stuck, since no future
  `onTaskStatusChanged` call would ever come along to decrement it).
- **Added `TaskEligibilityServiceConcurrencyTest`** and **`InMemoryTaskRepositoryTest`**, both
  exercising the real in-memory implementations (not mocks) under concurrent/mutation scenarios
  that the previous mock-only test suite couldn't catch, including a dedicated regression test
  for the target-completion race above
  (`concurrentAddDependency_raceWithTargetCompletion_neverLeavesDependentPermanentlyStuck`).

## Code review fixes (2026-08-31, PR #114 third review round)

Addressed all findings from this round's audit ([PLAN.md](PLAN.md) has the full write-up),
directly on this branch:

- **`addDependency`/`removeDependency` now validate that both tasks exist *before* touching
  `TaskDependencyRepository` at all.** Previously `taskDependencyRepository.save()`/`delete()`
  ran before the existence check, so a call with a nonexistent `taskId`/`dependsOnTaskId` could
  throw while still leaving a phantom edge saved (or a real edge deleted) behind it.
- **Added test coverage for the missing-task paths above** (`addDependency_taskDoesNotExist_...`,
  `addDependency_dependsOnTaskDoesNotExist_...`, `addDependency_bothTasksDoNotExist_...`,
  `removeDependency_taskDoesNotExist_...`), plus null-argument tests for every public method's
  UUID parameters.
- **`TaskEligibilityService`'s public methods and `TaskDependency`'s constructor now validate
  their UUID parameters with `Objects.requireNonNull`**, matching the existing convention used
  elsewhere in the codebase (`Task.copyOf`, the in-memory repositories) instead of failing later
  with a confusing `NullPointerException` deep inside a repository call.
- **`Constraint` now has id-based `equals`/`hashCode`** (matching `Task`'s pattern) **and
  defensive-copies its `reason` map** in the constructor and `getReason()`, so a caller can no
  longer mutate a `Constraint`'s internal state without going through a setter - the same
  protection `Task.copyOf` already gives `Task`.
- **`InMemoryTaskDependencyRepository`'s duplicate-edge check now reuses
  `TaskDependency.equals()`** instead of a separate, redundant `isSameEdge` helper doing the
  same field comparison.
- **The DB decision referenced throughout this module as pending is now resolved**: a real
  database will be available for Chain. No code changes as a result (the placeholder
  `@Transactional`/JPA/H2 setup stays exactly as-is until the actual migration happens), but
  `CLAUDE.md`, this file, `pom.xml`, `application.yml`, and `.env.example` are updated so
  nothing in the repo still describes this as an open question. The concrete recommended
  migration approach (JPA entities, atomic counter updates, retiring the in-process locking)
  is recorded in `PLAN.md`'s Phase 3 for when that work actually starts.
- **Added the concurrency regression test that a PR comment and this file previously claimed
  already existed but didn't**
  (`concurrentAddDependency_raceWithTargetCompletion_neverLeavesDependentPermanentlyStuck`,
  50 trials against the real in-memory repositories) - it now actually covers the race between
  a new dependency edge and its target completing at the same moment.
- **Added `TaskDependencyTest` and `ConstraintTest`**, giving `TaskDependency` and `Constraint`
  the same direct unit coverage of their null-validation/immutability/equals-hashCode behavior
  that this round added, rather than only exercising it indirectly through
  `TaskEligibilityServiceTest`.

See [PLAN.md](PLAN.md) for the full audit this round is based on, including which findings were
confirmed as-is, which were already mitigated, and which are deliberately deferred (and why).

## Code review fixes (2026-09-07, PR #114 fourth review round)

Addressed all findings from this round's review comments (discussed and planned one-by-one
directly with the reviewer before implementation, then implemented as a batch of separate commits
on this branch):

- **Closed a lost-update race in `onTaskStatusChanged`.** It mutated and saved the task's status
  under `dependencyGraphLock` only, while `onConstraintAdded`/`onConstraintStatusChanged` mutate
  the same task's counters under `lockFor(taskId)` only - two different monitors gave zero mutual
  exclusion between them, so a concurrent status change and constraint change on the same task
  could race, with whichever `save()` ran last silently clobbering the other's update.
  `onTaskStatusChanged` now nests `lockFor(taskId)` inside `dependencyGraphLock` for its
  read-modify-write, matching the locking discipline already used elsewhere (graph lock always
  acquired before the per-task lock, never after, so this can't deadlock).
- **`onTaskStatusChanged`/`onConstraintStatusChanged` now validate `newStatus`/`previousStatus`**
  with `Objects.requireNonNull`, closing a gap where the "guard every parameter" convention the
  other public methods follow had silently been dropped for these two.
- **`InMemoryTaskDependencyRepository`'s reads are now guarded by a lock too.**
  `findByTaskId`/`findByDependsOnTaskId` previously took no lock at all, while `save()`/`delete()`
  update both adjacency maps sequentially (not atomically) under the old plain `Object` lock - a
  reader could observe a torn view where one map reflects a write and the other doesn't. Replaced
  the plain lock with a `ReadWriteLock`: writes stay exclusive, reads are now guarded too (and
  still run concurrently with each other via the shared read lock). Both finder methods also
  gained a missing `Objects.requireNonNull` guard on `taskId`, matching `save()`'s existing
  convention.
- **`InMemoryTaskDependencyRepository.delete()` no longer inlines its adjacency-map-update pattern
  twice by hand** - extracted `removeFromIndex` as the removal-direction mirror of the existing
  `indexEdge` helper.
- **Restored 3 regression test scenarios** (`addSecondDependency_multipleBlockers`,
  `removeOneOfMultipleBlockers_staysNotReady`, `dependencyChain_onlyDirectDependentUpdated_notTransitive`)
  that existed in the original `com.rnd.chain.TaskEligibilityServiceTest` before the package
  restructuring to `com.rnd.chain.service`, but were never recreated afterward - ported to the
  current `Task`/UUID-based fixtures and repository contract.
- **`Constraint.reason` now rejects nested `Collection`/`Map` values.** The existing defensive copy
  only ever copied the top-level `Map` - a nested mutable value inside `reason` would still be a
  live, externally-mutable reference even after the "defensive" copy. A full recursive deep-copy
  isn't practical for a generic `Map<String, Object>`, so this takes the pragmatic middle ground of
  rejecting the shape outright; `reason` is documented/expected to hold flat scalar values only.
- **`Constraint.getReason()` no longer re-copies an already-copied map** - `this.reason` is always
  already the result of `copyReason()`, set that way by both the constructor and `setReason()`, so
  re-copying it again on every read was redundant work with no added safety.
- **`InMemoryTaskRepository`'s constructor now defensive-copies seed tasks**, consistent with
  `save()` (which already stores `Task.copyOf(task)` rather than the caller's exact reference) - a
  caller mutating a `Task` it passed into the seed list could previously change repository state
  without ever calling `save()`.
- **Added an O(1) `existsDependency(taskId, dependsOnTaskId)` repository method**, and
  `TaskEligibilityService.edgeExists()` now delegates to it instead of materializing the full
  `findByTaskId` result just to linear-scan it for one edge - both call sites (`addDependency`/
  `removeDependency`) are hot paths that run under a lock.
- **`lockFor(taskId)`'s stripe index now uses `Math.floorMod`** instead of a hand-rolled
  `(hashCode & 0x7fffffff) % LOCK_STRIPE_COUNT` sign-bit mask, which is only bit-for-bit equivalent
  to `Math.floorMod` because `LOCK_STRIPE_COUNT` (256) happens to be a power of two - an assumption
  nobody had written down. `Math.floorMod` expresses the intent directly and holds for any stripe
  count.
- **Extracted a shared `withTaskLock(taskId, mutator)` helper** for the
  `synchronized (lockFor(taskId)) { Task task = requireTask(taskId); ...; recalculateAndSave(task); }`
  shape that was duplicated across 5 call sites (`recalculateTaskEligibility`, `addDependency`,
  `removeDependency`, `onConstraintAdded`, `onConstraintStatusChanged`); each site now supplies
  only what actually differs. `onTaskStatusChanged` deliberately does not use this helper - it
  nests under `dependencyGraphLock` rather than locking `taskId` alone, and needs a value
  (`previousStatus`) out of the locked block, a different shape than "mutate this task, then save
  it".
- **Added inline `TODO(JPA)` comments at each `@Transactional` method's call sites** flagging two
  costs that only become real once a JPA migration lands: the transaction boundary becoming
  meaningful (today it's a deliberate placeholder - see `CLAUDE.md`), and the second
  `requireTask(taskId)` read in `addDependency`/`removeDependency` becoming a second real DB
  round-trip (it's a deliberate staleness-safety re-fetch today, cheap against an in-memory map;
  see `PLAN.md` Phase 3 for the eventual single-lock-scope, single-read redesign).
- **`RepositoryConfig`'s `@Bean` methods now individually document why they're singletons and why
  they're empty-seeded** (singleton because the service's locking is only correct for beans sharing
  the same repository instances; empty-seeded because no real persistence layer exists yet) - the
  class-level Javadoc already said this, but not on each bean.
- **Extracted a shared `TaskTestFixtures.defaultTask(id)` test helper**, replacing 3 independently
  hand-rolled `buildTask(...)` methods (in `InMemoryTaskRepositoryTest`,
  `TaskEligibilityServiceConcurrencyTest`, `TaskEligibilityServiceTest`) that each built the same
  underlying object via `Task.builder()` with a different ad-hoc parameter list. `Task`'s shape has
  already changed several times in this project's history, so every future change meant editing up
  to 3 near-identical methods with real drift risk; each test now chains only the field(s) it
  actually varies off the shared builder.
- **Merged `TaskEligibilityServiceConcurrencyTest`'s two line-for-line-identical concurrency test
  harness methods** (`runConcurrently`/`runConcurrentPair`) into one `runConcurrently(tasks,
  poolSize, timeoutSeconds)`, taking the pool size and timeout as explicit parameters instead of
  hardcoding them per method.
- **Fixed stale documentation**: `CLAUDE.md` claimed cycle detection uses "full in-memory BFS" -
  the actual implementation (`wouldCreateCycle`) is a stack-based DFS (`push`/`pop` on a `Deque`),
  and always has been. `PLAN.md`'s own audit table also still marked its Finding #1
  (validate-before-mutate ordering) and Finding #9 (missing concurrency regression test) as
  needing work, even though both were already fixed on this branch - their Status cells now
  reflect that.

A few of this round's comments were discussed and deliberately **not** acted on, each for a
specific reason rather than being skipped silently: cosmetic getter/setter formatting in
`Task.java` (not this branch's file - see file-ownership notes above); unifying
`Task.copyOf` and `Constraint.copyReason` behind a shared abstraction (no second concrete DTO on
this branch's near-term horizon to justify the abstraction yet); and the `spring-boot-starter-data-jpa`
+ H2 placeholder dependencies with zero JPA entities (intentional - see "Open items" in
`CLAUDE.md` and the DB-migration rationale above).

**Update (2026-09-08):** the "not acted on" call above on Lombok was revisited by direct request
and reversed - see the next section. The DTO-divergence-across-branches risk that motivated the
original call is still real and is being tracked/handled separately (not by this PR).

## Code review fixes (2026-09-08, PR #114 fifth review round)

Addressed all findings from this round's review comments (discussed and planned one-by-one
directly with the reviewer before implementation, then implemented as a batch of separate commits
on this branch - see [PLAN.md](PLAN.md) §4a for the full write-up):

- **`onConstraintAdded`/`onConstraintStatusChanged` now guard against duplicate event delivery.**
  Every other counter-mutating path in `TaskEligibilityService` already protected itself against
  being invoked twice for the same logical event (`edgeExists()` for
  `addDependency`/`removeDependency`, the task's own stored `previousStatus` for
  `onTaskStatusChanged`); these two had no equivalent - they unconditionally adjusted
  `openConstraintCount` off caller-supplied booleans, with no per-constraint identity ever
  consulted. Both methods now take a `constraintId` parameter and track a `Set<UUID>` of
  currently-open constraint ids per task, so a redelivered "constraint added"/"constraint status
  changed" event (expected from at-least-once delivery once a real event source is wired) is a
  no-op instead of permanently inflating or deflating the counter.
- **Added `InMemoryTaskDependencyRepositoryTest` and `TaskTest`** - the module's only two
  implementation classes that previously had no dedicated test file (every sibling class -
  `Constraint`, `TaskDependency`, `InMemoryTaskRepository` - already had one), covering `save`
  idempotency, `delete`'s empty-map-entry cleanup, `findByTaskId`/`findByDependsOnTaskId`
  directionality, constructor validation, and `copyOf`'s independence guarantee.
- **`Constraint.getReason()` now returns an empty map instead of `null`**, matching this module's
  own "empty collection, never null" convention (`TaskDependencyRepository`/`TaskRepository`'s
  finder methods) rather than the reviewer's suggested `Optional<Map<...>>`, which Effective Java
  advises against for collection-typed return values.
- **`Constraint.copyReason`'s mutability guard now also rejects array values**, not just
  `Collection`/`Map` - arrays are exactly as mutable and exactly as uncopied by the shallow
  `new HashMap<>(reason)` copy, but implement neither interface so the existing check missed them.
- **`addDependency` now checks for a duplicate edge before running the cycle-detection DFS**, not
  after - an edge that already exists cannot be introducing a new cycle, so the O(V+E) traversal
  (held under the single-writer `dependencyGraphLock`) is skipped entirely on a duplicate/retried
  call, exactly the call pattern at-least-once event delivery makes common.
- **Added method-level Javadoc to all 6 of `TaskEligibilityService`'s public methods**, with an
  accurate `@throws` list per method (not identical boilerplate across all six) - this class is
  currently the module's only real API surface (`controller/` is still empty).
- **Adopted Lombok on `Task`/`Constraint`/`TaskDependency`** (`@Getter`/`@Setter` for the trivial
  accessors, `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with the id field explicitly
  included so equality stays id-only - the per-task locking design leans on this - and `@Builder`
  on `Task`'s validating constructor so its generated builder still runs the same validation on
  every `build()`). `getReason`/`setReason`/`copyReason`/`copyOf`/the constructors stay
  hand-written, since Lombok doesn't produce that behavior.
  - **Required an explicit `annotationProcessorPaths` entry** for `maven-compiler-plugin` and a
    pinned newer Lombok version (`1.18.46`, vs. the `1.18.30` Spring Boot 2.7.18 manages) - the
    parent-managed compiler plugin didn't reliably invoke Lombok's annotation processor via
    implicit classpath scanning on this machine's JDK.

Full test suite (76 tests, `mvn clean test`) passes.

## Code review fixes (2026-09-23, PR #114 sixth review round)

Addressed the findings from this round that belong on this branch (discussed and triaged
one-by-one with the reviewer first):

- **Removed the dead `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` / `@EqualsAndHashCode.Include`
  annotations from `Constraint`.** Lombok skips generating `equals`/`hashCode` whenever hand-written
  ones already exist in the class, and `Constraint` has had hand-written id-only `equals`/`hashCode`
  since the 2026-09-06 round - the annotations, added two days later when Lombok landed on the
  DTOs, never produced any bytecode. Equality is now documented as hand-written by design (matching
  `getReason`/`setReason`'s pattern), not Lombok-generated.
- **`TaskEligibilityServiceTest`'s `buildTask` now delegates to `TaskTestFixtures.defaultTask(...)`**
  instead of duplicating the `Task.builder()` construction inline. `TaskTestFixtures`'s Javadoc
  previously claimed to have consolidated this exact helper, but `TaskEligibilityServiceTest` had
  kept its own independent copy (30+ call sites use its compact positional-args form, which is
  worth keeping - only the construction underneath it was duplicated). The Javadoc now states
  precisely which classes call `defaultTask` directly, which one delegates to it, and which one
  (`InMemoryTaskRepositoryTest`) uses neither.
- **`wouldCreateCycle`'s DFS no longer takes `InMemoryTaskDependencyRepository`'s internal read
  lock on every visited node.** The whole traversal already runs inside
  `TaskEligibilityService.dependencyGraphLock`, which is exclusive with every write this
  repository ever makes, so that per-node lock acquire/release was always uncontended overhead.
  Added `TaskDependencyRepository#findByTaskIdUnderExternalLock` - a `default` interface method
  (so a future JPA-backed implementation doesn't need to know about it at all) that
  `InMemoryTaskDependencyRepository` overrides to skip its own locking, documented with the
  exclusivity contract the override relies on.
- **`InMemoryTaskDependencyRepository`'s `find...` methods now pre-size their result list**
  (`Collectors.toCollection(() -> new ArrayList<>(ids.size()))` via a shared `toTaskDependencyList`
  helper) instead of `Collectors.toList()`, which gives the JDK no capacity hint and could grow/copy
  the list internally as elements accumulate.
- **Fixed a stale "Java 25" claim in this file's Stack section** (see below) - the actual
  `pom.xml` on this branch is Java 21 / Spring Boot 3.3.13. A separate commit
  (`03c9838b`, "[MINOR] Bump java.version to 25...") did briefly bump this module to 25, but that
  change did not survive the subsequent merge from `main` - `git blame` on `pom.xml`'s
  `java.version` line attributes it to the earlier Java 21 upgrade, not that commit.

Findings from this round **not** addressed on this branch, each for a specific reason:
- The `openConstraintIdsByTask` undercount-on-restart scenario and its inline
  "prune empty map entry" duplication (vs. `InMemoryTaskDependencyRepository.removeIfEmpty`) - both
  are being resolved by removing `openConstraintIdsByTask` entirely on a separate local branch
  (`constraint-persistent-378`), not by patching it here.
- The dead `@Setter` on `Task` (all 12 fields are `final`, so it generates nothing) - that DTO is
  owned by a teammate; flagged to her directly rather than edited here.
- The Java-25/Spring-Boot-2.7.18 compatibility concern - doesn't describe this branch's actual
  `pom.xml` (see the Stack-section fix above), so there's nothing to remediate here.

## Code review fixes (2026-10-04, PR #114 seventh review round)

Three correctness fixes from this round's review, each with a regression test:

- **`onTaskStatusChanged` no longer corrupts `Task.isReady`.** `copyOf` didn't carry `isReady`
  across, and `Task.Builder`'s primitive boolean defaults to `false`. `withCounters` masked this
  by always re-setting it, but `onTaskStatusChanged` builds directly off `copyOf`, so every status
  change - including a same-status replay - flipped a ready task to not-ready in the repository.
- **`addDependency`'s duplicate-edge path is a true no-op.** It previously called
  `requireTask(dependsOnTaskId)` before the `isNewEdge` check, so a retried call for an existing
  edge whose target had since been removed threw instead of being idempotent. All reads and writes
  after the cycle check are now gated on `isNewEdge`, matching `removeDependency`.
- **`onConstraintStatusChanged`'s non-open fast path validates `taskId`.** The early return for
  transitions that don't cross the `PENDING` boundary skipped task-existence validation, contradicting
  the method's `@throws` contract. It now calls `requireTask` first; the lock, counter write, and
  `isReady` recompute are still skipped.

Findings from this round not addressed on this branch:
- `Task`'s dead `@Setter` - owned by a teammate, reported to her separately.
- `.vscode/settings.json`'s `java.compile.nullAnalysis.mode` - a personal IDE setting that
  already landed on `main` through an unrelated commit, and reaches this branch only via a merge
  from `main`.
- Two findings in this round were already fixed by earlier commits on this branch when the review
  was posted (the dead `@EqualsAndHashCode` on `Constraint`, and the duplicated `buildTask` in
  `TaskEligibilityServiceTest`).

Kafka-related tests use `@EmbeddedKafka` (`spring-kafka-test`) - no external Kafka broker or
Docker is needed to run the test suite.

Run the tests on JDK 21 or 23. The Byte Buddy version bundled with Spring Boot 3.3.13 does not
support JDK 25, and Mockito then fails while loading the Spring context.

**A real Postgres reachable at `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` (defaults:
`localhost`/`5432`/`chain`/`chain`/`chain`) is now required for the full suite** - any
`@SpringBootTest` (including `ChainApplicationTests`) fails context startup without one, since
Flyway/Hibernate need a live connection. Bring one up via `docker compose up postgres` from the
repo root (creates the `chain` database/user on first init - see
`shared/database/init/create-chain-database.sh`), or point at any reachable Postgres instance.

In CI, like Inbox, the tests run against the kind cluster's Postgres (`values-chain.yaml` enables
it). The `@Tag("postgres")` tests still run only with `mvn test -Ppostgres-integration`. Several
of them also need a Kafka broker, so they aren't enabled in CI yet.

## Merge with main (2026-10-05)

This branch merges `origin/main` (Postgres persistence, `ChainController`, events). Where the
two diverged, `main` is the base and this branch's changes are layered on top:

- **Lombok stays on `Task`/`Constraint`** (team lead's instruction). `TaskDependency` is now the
  `main` record, since `main`'s persistence and traversal code call its record accessors.
- **Duplicate edges**: `addDependency` on an existing edge throws `DuplicateDependencyException`
  (mapped to HTTP 409 by `GlobalExceptionHandler`) before any task lookup or counter change. An
  event consumer retrying an already-applied edge should treat this exception as "already applied".
- **Constraint tracking** is persisted: `ConstraintService` saves the constraint, and
  `TaskEligibilityService` uses the database to detect duplicates (`save` reports whether the row
  was inserted) and to read the previous status (`updateStatus`). An in-memory ledger is not used,
  so a restart does not lose track of open constraints (issue #378).
- **Non-open constraint transitions** still validate `taskId` (`TaskNotFoundException`) before
  returning early.
- `wouldCreateCycle` reads edges through `findByTaskIdUnderExternalLock` (it already runs under
  `dependencyGraphLock`).
- Cross-team `pom.xml` changes from an earlier commit (Java 25 bump in `teams/bridge`,
  `teams/inbox`, `teams/lens`) are reverted to `main`'s versions. This PR only changes Chain.

**Local test note:** the full suite needs a reachable Postgres (see Tests above). On a JDK newer
than Byte Buddy supports (e.g. JDK 25), run Mockito-based tests with
`-DargLine="-Dnet.bytebuddy.experimental=true"`. CI runs on Java 21 and does not need it.

## Dependency graph persistence (Issue #66)

`TaskDependencyRepository` / `InMemoryTaskDependencyRepository` (in `repository/`) are a pure
CRUD store for dependency edges (`taskId` depends on `dependsOnTaskId`): `findByTaskId`,
`findByDependsOnTaskId`, `save`, `delete`. `save` is idempotent - saving the same edge twice does
not create a duplicate row - and `delete` is a no-op if the edge doesn't exist. This layer does not
validate task existence or reject self-dependencies; those are enforced at the DB level (FK +
`chk_task_dependency_no_self_reference` CHECK, V1 migration) for `PostgresTaskDependencyRepository`,
the real implementation backing this in production - `InMemoryTaskDependencyRepository` remains as
`MockDataConfig`'s fallback when no `DataSource` is configured.

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
existence checks and no shared implementation was merged yet at the time this was written.

## Dependency change history (Issue #153)

Every dependency-graph change that actually creates or removes an edge is recorded as a
`DependencyHistoryEntry` (`dto/`): `id`, `taskId`, `dependsOnTaskId`, `action` (`ADDED`/`REMOVED`),
`source` (`MIND`/`USER`/`SYSTEM`), `sourceRef` (nullable), `createdAt`. It's a plain DTO with no
REST/Kafka/Jackson dependency, so the same model applies regardless of how it's read later.

`DependencyHistoryRepository` / `InMemoryDependencyHistoryRepository` persist it, following the same
interface-plus-in-memory-implementation pattern as `TaskDependencyRepository` -
`PostgresDependencyHistoryRepository` is the real, Postgres-backed implementation (`dependency_history`
table, V2 migration). `TransactionRunner` / `InMemoryTransactionRunner` let a graph write and its
history entry happen as one unit; `JpaTransactionRunner` (backed by a real `PlatformTransactionManager`)
gives this actual ACID rollback in production. `TaskDependencyService`'s own compensating action
(undoing the graph write if the history write fails) is kept regardless - still correct, and now
largely redundant with the real rollback rather than the sole safety net.

### API

```
GET /tasks/{taskId}/dependencies/history?page=0&size=20
```

Returns a `PageResponse<DependencyHistoryResponse>` (`content`, `page`, `size`, `totalElements`,
`totalPages`), newest first. `page` defaults to 0; `size` defaults to 20 and is capped at 100 (both,
plus a negative `page`, are rejected with 400). A task with no history yet returns 200 with empty
`content` - 404 is only for a `taskId` that doesn't exist. This is the first paginated endpoint in
the repo; the `page`/`size`/`PageResponse` shape is meant for reuse by future endpoints too.

This is also the first `@RestControllerAdvice` in the repo (`GlobalExceptionHandler`, in
`exception/`): `TaskNotFoundException`/`DependencyNotFoundException` -> 404,
`DuplicateDependencyException` -> 409, `InvalidDependencyException`/`InvalidRequestException` -> 400,
with a `{"error": "...", "message": "..."}` body.

### Idempotency

`addDependency`/`removeDependency` take an optional `(source, sourceRef)` pair (the old 2-argument
overloads still work, delegating to `source = USER, sourceRef = null`). `sourceRef` is the
idempotency key: a repeat call with the same `(source, sourceRef)` is treated as a retry and
returns/no-ops instead of throwing the usual duplicate/not-found exception. It's **required** for
`MIND`/`SYSTEM` (automated, retry-exposed sources) and **optional** for `USER` (a direct synchronous
call). **Known limitation:** a `USER` call without a `sourceRef` gets no idempotency protection - a
double-click or client retry can hit a normal duplicate/not-found error.

### Persistence

Same status as the dependency graph: Postgres-backed in production (`PostgresDependencyHistoryRepository`
+ `JpaTransactionRunner`), in-memory only as `MockDataConfig`'s fallback. The manual compensating
action in `TaskDependencyService` was **not** removed - see "Dependency graph persistence" above.

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

`onConstraintAdded` also validates the task exists (and isn't soft-deleted) before writing -
`task_constraint`'s FK enforces existence at the DB level, but not soft-delete (a soft-deleted
task still has a row); `TaskRepository#findById` already treats a soft-deleted task as not found,
so calling it first is what actually satisfies #378's "non-existing, **non-deleted** task" wording.

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
