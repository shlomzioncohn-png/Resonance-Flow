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

**Postgres has been chosen** (see issue #384) - plain relational tables for the dependency graph,
no `pgvector`. Own database (`chain`) and own DB user (not the shared postgres superuser) on the
shared cluster instance, wired in `teams/relay/helm/resonance-flow/values.yaml`
(`components.chain.config.data.datasource`) and `templates/services/chain/configmap.yaml`, the
same file-mount ConfigMap mechanism Inbox uses. Flyway is the confirmed migration tool
(matching Inbox), not yet wired into `pom.xml` - premature before real JPA entities exist.

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

Application-side JPA wiring (entities/repositories actually backed by Postgres, replacing the
in-memory `TaskRepository`/`TaskDependencyRepository`) is also still open follow-up work, not
done here.

Locally, an in-memory H2 database is still configured as a placeholder purely so a
`DataSource`/transaction manager exists for `@Transactional` - `Task`/`TaskDependency` are plain
DTOs (not JPA entities) and are **not** persisted to H2; real data still lives in the in-memory
`TaskRepository`/`TaskDependencyRepository` implementations. See [CLAUDE.md](CLAUDE.md) for the
full rationale and open items.

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
