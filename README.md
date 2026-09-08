# Resonance-Flow
Intelligent Work Prioritization Platform

## Database

A single shared PostgreSQL instance is used across all services. Schema migrations live in
[`shared/database/migrations`](shared/database/migrations) and are applied by Flyway.

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
