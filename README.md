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
