# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

`TaskEligibilityService` implements the dependency/constraint update flow (`addDependency`,
`removeDependency`, `onTaskStatusChanged`, `recalculateTaskEligibility`, `onConstraintAdded`,
`onConstraintStatusChanged`) against in-memory repositories. It's a registered `@Service` bean -
`config/RepositoryConfig.java` wires the in-memory `TaskRepository`/`TaskDependencyRepository`
implementations as beans (seeded empty) so the whole thing is actually constructible by Spring,
not just unit-testable. No REST controllers are wired up yet (`controller/` is still empty) -
this stage is the eligibility engine and its tests.

`Task`/`TaskDependency`/`TaskStatus` and the `TaskRepository`/`TaskDependencyRepository`
contracts (with in-memory implementations) come from issue #66's branch
(`chain/ruthHoltzman/dependency-repository-66`). That branch also has a `TaskDependencyService`
(existence/self-dependency/duplicate-edge validation) that isn't merged here yet and overlaps
with some of what `TaskEligibilityService.addDependency`/`removeDependency` already do
(cycle detection, `blockingCount`) - reconcile the two once #66 lands.

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
