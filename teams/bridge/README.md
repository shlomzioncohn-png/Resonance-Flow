# Team Bridge - Service

Foundation module for Team Bridge (source adapters & communication ingestion).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet - the
goal of this stage is a clean, working Maven/Spring Boot project that later work can build on.

## Sub-roles (4 developers)

| Role | Owner | Owns |
|---|---|---|
| GitHub-adapter engineer | Hadas | GitHub ETL adapter end to end, including incremental sync |
| CRM-adapter engineer | Ruth | CRM ETL adapter end to end, including deal-stage sync |
| Unstructured-intake engineer | Malki | Gmail/Drive/Slack/WhatsApp/calendar intake, scoping enforcement |
| Meeting/doc/audio hand-off engineer | Esther | BigBlueButton/Zoom + scanned-document intake, hand-off contract with Mind |

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Docker / Docker Compose

No MinIO/Kafka connection is configured yet - those are Relay's and Inbox's infra choices, to be
wired in once their connection details are available.

## Project structure

```
src/main/java/com/rnd/bridge/
├── BridgeApplication.java   # Spring Boot entry point
├── controller/
├── service/
├── repository/
├── dto/
└── config/
src/main/resources/application.yml
src/test/java/com/rnd/bridge/
```

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 21.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080

To just build the jar:

```bash
mvn clean package
java -jar target/bridge-0.1.0-SNAPSHOT.jar
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
