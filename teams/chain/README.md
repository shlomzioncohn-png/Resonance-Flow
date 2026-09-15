# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

`TaskEligibilityService` implements the dependency/constraint update flow (`addDependency`,
`removeDependency`, `onTaskStatusChanged`, `recalculateTaskEligibility`, `onConstraintAdded`,
`onConstraintStatusChanged`) against in-memory repositories. It's a registered `@Service` bean -
`config/MockDataConfig.java` wires the in-memory `TaskRepository`/`TaskDependencyRepository`
implementations as beans (seeded empty) so the whole thing is actually constructible by Spring,
not just unit-testable. Each bean is guarded with `@ConditionalOnMissingBean`, so once a real
DB-backed `TaskRepository`/`TaskDependencyRepository` is registered, it overrides the mock instead
of colliding with it - no manual removal of this config needed when that lands. No REST
controllers are wired up yet (`controller/` is still empty) - this stage is the eligibility engine
and its tests.

`Task`/`TaskDependency`/`TaskStatus` and the `TaskRepository`/`TaskDependencyRepository`
contracts (with in-memory implementations) come from issue #66's branch
(`chain/ruthHoltzman/dependency-repository-66`). That branch also has a `TaskDependencyService`
(existence/self-dependency/duplicate-edge validation), which now lives alongside
`TaskEligibilityService` in this module and overlaps with some of what
`TaskEligibilityService.addDependency`/`removeDependency` already do (cycle detection,
`blockingCount`) - reconciling the two (having `TaskEligibilityService` delegate raw edge
validation/CRUD to `TaskDependencyService` and keep only cycle detection + counters) is still an
open follow-up, not done as part of this merge.

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Docker / Docker Compose

The DB choice for the project has not been decided yet. An in-memory H2 database is configured
as a placeholder purely so a `DataSource`/transaction manager exists for `@Transactional` -
`Task`/`TaskDependency` are plain DTOs (not JPA entities) and are **not** persisted to H2; real
data still lives in the in-memory `TaskRepository`/`TaskDependencyRepository` implementations.
See [CLAUDE.md](CLAUDE.md) for the full rationale and open items.

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
└── config/
    └── MockDataConfig.java
src/main/resources/application.yml
src/test/java/com/rnd/chain/
├── service/
│   ├── TaskEligibilityServiceTest.java
│   └── TaskEligibilityServiceConcurrencyTest.java   # real threads, real in-memory repos, no mocks
└── repository/
    ├── InMemoryTaskRepositoryTest.java
    └── InMemoryTaskDependencyRepositoryTest.java
```

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
existence checks and no shared implementation was merged yet at the time this was written.

## Dependency change history (Issue #153)

Every dependency-graph change that actually creates or removes an edge is recorded as a
`DependencyHistoryEntry` (`dto/`): `id`, `taskId`, `dependsOnTaskId`, `action` (`ADDED`/`REMOVED`),
`source` (`MIND`/`USER`/`SYSTEM`), `sourceRef` (nullable), `createdAt`. It's a plain DTO with no
REST/Kafka/Jackson dependency, so the same model applies regardless of how it's read later.

`DependencyHistoryRepository` / `InMemoryDependencyHistoryRepository` persist it, following the same
interface-plus-in-memory-implementation pattern as `TaskDependencyRepository`, pending a real DB.
`TransactionRunner` / `InMemoryTransactionRunner` let a graph write and its history entry happen as
one unit; the in-memory implementation is a global lock only, **not** real ACID rollback -
`TaskDependencyService` performs its own compensating action (undoing the graph write) if the
history write fails.

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

Same status as the dependency graph: in-memory, pending a real DB choice. `TransactionRunner` will
be replaced by a `@Transactional`-based implementation once that happens, and the manual
compensation in `TaskDependencyService` removed.
