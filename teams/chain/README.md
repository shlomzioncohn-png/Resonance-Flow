# Team Chain - Service

Foundation module for Team Chain (Dependency Graph, Task Selection & Constraints).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet - the
goal of this stage is a clean, working Maven/Spring Boot project that later work can build on.
The dependency graph persistence layer (`DependencyService`, `TaskRepository`,
`TaskDependencyRepository` and related exceptions) is tracked separately under issue #66 and is
not part of this module yet.

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Docker / Docker Compose

No database is configured yet - the DB choice for the project has not been decided.

Task identifiers are `java.util.UUID` for now, pending the Inbox team publishing its own task id
type (`Task`/`TaskDependency` will switch to that type once it exists).

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
