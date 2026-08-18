# Team Inbox - Service

Foundation module for Team Inbox (Backend Services & Shared Task/Data Model).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet - the
goal of this stage is a clean, working Maven/Spring Boot project that later work can build on.

## Stack

- Java 11
- Spring Boot 2.7.18 (Maven)
- Docker / Docker Compose

No database is configured yet - the DB choice for the project has not been decided.

## Project structure

```
src/main/java/com/rnd/inbox/
├── InboxApplication.java   # Spring Boot entry point
├── controller/
├── service/
├── repository/
├── dto/
└── config/
src/main/resources/application.yml
src/test/java/com/rnd/inbox/
```

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 11.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080

To just build the jar:

```bash
mvn clean package
java -jar target/inbox-0.1.0-SNAPSHOT.jar
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

The app will be available at http://localhost:8080 (or whatever `SERVER_PORT` is set to in `.env`).

To stop:

```bash
docker compose down
```

## Tests

```bash
mvn test
```
