# Resonance-Flow
Intelligent Work Prioritization Platform

## Database

A single shared PostgreSQL instance is used across all services. Inbox is the sole owner of the
canonical schema; its Flyway migrations live in
[`teams/inbox/src/main/resources/db/migration`](teams/inbox/src/main/resources/db/migration).

To run the shared Postgres plus all team services:

```bash
docker compose up --build
```

This starts one `postgres` container (port `5432` by default) shared by `chain`, `bridge`, and
`inbox`. Each team's own `docker-compose.yml` (e.g. `teams/chain/docker-compose.yml`) is for running
that single service in isolation against a Postgres already reachable on the host machine — it does
not spin up its own database.

### pgAdmin (Postgres UI)

A [pgAdmin](https://www.pgadmin.org/) container is included for browsing/querying the shared
Postgres without installing anything locally. It starts automatically with `docker compose up` and
is available at [http://localhost:5050](http://localhost:5050).

- Login: `admin@example.com` / `admin` (override via `PGADMIN_EMAIL` / `PGADMIN_PASSWORD` in `.env`)
- The `Resonance Flow (local)` server connection is pre-configured (host `postgres`, db
  `resonance_flow`, user `postgres`) — when you first expand it, pgAdmin will prompt for the DB
  password, which is `postgres` unless you changed `DB_PASSWORD` in `.env`.
- Copy [`.env.example`](.env.example) to `.env` first if you want to customize any ports/credentials.

## Kafka and schema registry

`docker compose up` also starts one shared Kafka broker (`kafka:29092` for containers,
`localhost:9092` for an IDE), an Apicurio schema registry
([http://localhost:8083](http://localhost:8083)) and Kafka UI
([http://localhost:8090](http://localhost:8090)). `chain`, `bridge` and `inbox` are wired to them.

The registry is in-memory and starts empty, and the producers don't register schemas themselves, so
register them once after it starts (again after a registry restart):

```bash
docker compose --profile seed run --rm schema-seed
```

To publish a test event, open Kafka UI, pick the topic, choose `SchemaRegistry` as the value serde
and send JSON. Each team's own `docker-compose.yml` runs a broker of its own on the same ports, so
stop it before starting this one.
