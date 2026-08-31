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

A real database has been confirmed for Chain (decision resolved - see [CLAUDE.md](CLAUDE.md)). An in-memory H2 database is configured
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
