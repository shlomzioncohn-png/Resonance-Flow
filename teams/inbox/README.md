# Team Inbox - Service

Foundation module for Team Inbox (Backend Services & Shared Task/Data Model).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet - the
goal of this stage is a clean, working Maven/Spring Boot project that later work can build on.

## Stack

- Java 11
- Spring Boot 2.7.18 (Maven)
- PostgreSQL, with schema migrations managed by Flyway
- Docker / Docker Compose

## Database

Migrations live under [`shared/database/migrations`](../../shared/database/migrations) at the repo
root (shared across services) and are applied automatically by Flyway on application startup - no
manual step is needed.

Connection settings are read from environment variables (see `.env.example`): `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`, `DB_PASSWORD`.

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

Requires a local Maven installation (`mvn`) and JDK 11, plus a running PostgreSQL instance
(e.g. `docker compose up postgres` from the repo root, or your own local instance matching the
`.env.example` settings).

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

## Kafka

Local Kafka (single-node, KRaft mode — no Zookeeper) plus a web UI for it are part of this
module's `docker-compose.yml`.

### Bringing Kafka up

```bash
docker compose up -d kafka kafka-ui
```

(omit `-d` to watch the logs, or leave it off `docker compose up --build` entirely to start
everything, including the `inbox` app, together). First start pulls the `apache/kafka` image,
which is a few hundred MB — expect that to take a while once, not on every subsequent start.

Other services in this same `docker-compose.yml` (the `inbox` app, `kafka-ui`) reach Kafka
internally at `kafka:29092`. From your own machine (a local script, an IDE Kafka plugin, `kcat`,
etc.) it's at `localhost:${KAFKA_PORT:-9092}`.

### Accessing Kafka UI

Open **http://localhost:8090** (or `${KAFKA_UI_PORT}` from your `.env`) in a browser. It's
connected to this Kafka out of the box — no login, no extra setup. From there you can browse
Topics, inspect Messages and their payloads, see Partitions, and see Consumer Groups.

### Verifying the connection is healthy

```bash
docker compose ps
```

`inbox-kafka` should show `(healthy)` once it's fully up (health check polls
`kafka-broker-api-versions` internally, usually ready within ~20-30s of first start). You can
also confirm end-to-end from the CLI:

```bash
docker exec inbox-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

An empty list (no error) means Kafka is up and accepting connections. Once Kafka UI is open in
the browser, its cluster page shows the broker as online — that's the simplest visual check.

## Tests

```bash
mvn test
```
