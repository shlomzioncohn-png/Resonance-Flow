# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

`TaskEligibilityService` implements the dependency/constraint update flow (`addDependency`,
`removeDependency`, `onTaskStatusChanged`, `recalculateTaskEligibility`, `onConstraintAdded`,
`onConstraintStatusChanged`) against in-memory repositories. No REST controllers are wired up
yet (`controller/` is still empty) - this stage is the eligibility engine and its tests.

`Task`/`TaskDependency`/`TaskStatus` and the `TaskRepository`/`TaskDependencyRepository`
contracts (with in-memory implementations) come from issue #66's branch
(`chain/ruthHoltzman/dependency-repository-66`). That branch also has a `TaskDependencyService`
(existence/self-dependency/duplicate-edge validation) that isn't merged here yet and overlaps
with some of what `TaskEligibilityService.addDependency`/`removeDependency` already do
(cycle detection, `blockingCount`) - reconcile the two once #66 lands.

## Stack

- Java 11
- Spring Boot 2.7.18 (Maven)
- Docker / Docker Compose

The DB choice for the project has not been decided yet. An in-memory H2 database is configured
as a placeholder purely so a `DataSource`/transaction manager exists for `@Transactional` -
`Task`/`TaskDependency` are plain DTOs (not JPA entities) and are **not** persisted to H2; real
data still lives in the in-memory `TaskRepository`/`TaskDependencyRepository` implementations.
See [CLAUDE.md](CLAUDE.md) for the full rationale and open items.

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
├── controller/             # empty - no REST endpoints yet
├── service/
│   └── TaskEligibilityService.java
├── repository/
│   ├── TaskRepository.java / InMemoryTaskRepository.java
│   └── TaskDependencyRepository.java / InMemoryTaskDependencyRepository.java
├── dto/
│   ├── Task.java, TaskDependency.java, TaskStatus.java, Constraint.java
└── config/
    └── RepositoryConfig.java   # wires the in-memory repos as beans
src/main/resources/application.yml
src/test/java/com/rnd/chain/
├── ChainApplicationTests.java
├── repository/
│   └── InMemoryTaskRepositoryTest.java
└── service/
    ├── TaskEligibilityServiceTest.java
    └── TaskEligibilityServiceConcurrencyTest.java   # multi-threaded, against the real in-memory repos
```

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 11.

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

Includes `TaskEligibilityServiceConcurrencyTest`, which runs `TaskEligibilityService` against the
real in-memory repositories (not mocks) under actual concurrent load, so lock-related races have
a chance to surface.

## Code review fixes (2026-08-30, PR #114 second review round)

Addressed the following findings from the review, directly on this branch:

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
- **Added `TaskEligibilityServiceConcurrencyTest`** and **`InMemoryTaskRepositoryTest`**, both
  exercising the real in-memory implementations (not mocks) under concurrent/mutation scenarios
  that the previous mock-only test suite couldn't catch.

Still open from that review round (deliberately not addressed here yet - see CLAUDE.md and the
PR thread): non-blocking cosmetic query de-duplication in `addDependency`'s cycle check, and
`onTaskStatusChanged`/`addDependency` racing on a dependency's target-task-completion status
(a separate, narrower race from the per-task-lock gap fixed above).
