# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

This is a Spring Boot skeleton with an initial slice of the dependency graph persistence layer
implemented on top of it.

## Stack

- Java 11
- Spring Boot 2.7.18 (Maven)
- Docker / Docker Compose

No database is configured yet - the DB choice for the project has not been decided. The
dependency graph is currently backed by an in-memory repository (see below); it will be swapped
for a real database once that decision is made, without changing the service/repository
interfaces.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

## Dependency graph persistence

`DependencyService` (`service/DependencyService.java`, implemented by `DependencyServiceImpl`)
manages dependency edges between tasks, where `taskId -> dependsOnTaskId` means `taskId` depends
on (is blocked by) `dependsOnTaskId`.

Implemented so far:

- `addDependency(UUID taskId, UUID dependsOnTaskId)` — creates the edge `taskId -> dependsOnTaskId`.
  - Throws `TaskNotFoundException` if either task id does not exist in `TaskRepository`.
  - Throws `SelfDependencyException` if `taskId` equals `dependsOnTaskId`.
  - Throws `DuplicateDependencyException` if the edge already exists.
- `removeDependency(UUID taskId, UUID dependsOnTaskId)` — deletes the edge `taskId -> dependsOnTaskId`.
  - Throws `DependencyNotFoundException` if the edge does not exist.

All exceptions live in `com.rnd.chain.exception` and are unchecked `RuntimeException`s, intended
to be translated to HTTP responses once the controller layer is added.

`getDependencies(taskId)` / `getDependents(taskId)` (querying the graph) are not implemented yet.

### Repositories

- `TaskRepository` / `InMemoryTaskRepository` — stores `Task` records; used only to validate that
  a referenced task id exists before an edge is created.
- `TaskDependencyRepository` / `InMemoryTaskDependencyRepository` — stores dependency edges,
  keyed by `taskId` for O(1) duplicate checks and add/remove.

Both `InMemory*` implementations are `@Repository` beans and are thread-safe
(`ConcurrentHashMap`-backed), but are a temporary stand-in until a database is chosen.

## Project structure

```
src/main/java/com/rnd/chain/
├── ChainApplication.java   # Spring Boot entry point
├── controller/
├── service/
├── repository/
├── dto/
└── config/
src/main/resources/application.yml
src/test/java/com/rnd/chain/
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
