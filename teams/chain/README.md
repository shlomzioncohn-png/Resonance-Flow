# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

Implements the graph traversal API: given a task id, walk its direct or transitive dependencies,
dependents, ancestors, children, or descendants, or fetch a combined trace across both axes in
one call. See "Graph Traversal API" below for the full endpoint list.

The in-memory repositories (`TaskRepository`, `TaskDependencyRepository` and their
implementations) and the `Task`/`TaskDependency`/`TaskStatus` DTOs are sourced from
[`chain/ruthHoltzman/dependency-repository-66`](../../tree/chain/ruthHoltzman/dependency-repository-66)
(originally tracked under issue #66), taken as-is rather than authored in this branch -
`InMemoryTaskRepository` is the one exception, hand-authored here (see its class Javadoc) after
no branch had an indexed `findByParentTaskId`. No real database is wired up yet; these
repositories are in-memory only.

## Stack

- Java 11
- Spring Boot 2.7.18 (Maven)
- Docker / Docker Compose

No database is configured yet - the DB choice for the project has not been decided.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

## Project structure

```
src/main/java/com/rnd/chain/
├── ChainApplication.java   # Spring Boot entry point
├── controller/             # ChainController - the graph traversal API
├── service/                # TaskTraversalService - traversal logic
├── repository/             # TaskRepository / TaskDependencyRepository + in-memory impls
├── dto/                    # Task, TaskDependency, TaskStatus, TaskResponse, TaskTraceResponse
├── exception/              # GlobalExceptionHandler, NotFoundException/BadRequestException +
│                           # TaskNotFoundException/DuplicateDependencyException (adopted from
│                           # Riki's branch - see the 2026-09-08 fixes below)
└── config/                 # MockDataConfig - empty-seeded repository beans
src/main/resources/application.yml
src/test/java/com/rnd/chain/
├── controller/ChainControllerTest.java
├── exception/GlobalExceptionHandlerTest.java
└── service/TaskTraversalServiceTest.java, TaskTraversalPerformanceTest.java
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
