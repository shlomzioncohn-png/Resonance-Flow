# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository overview

Resonance-Flow ("Intelligent Work Prioritization Platform") is a monorepo containing several
independent teams' backend services plus the shared Kubernetes/Helm deployment infrastructure for
all of them. Each team ships its own service, its own CI pipeline, and (for the JVM services) its
own Java/Spring Boot version — there is no shared application code between teams; anything
genuinely cross-team is either an HTTP/Kafka contract or lives under `shared/` (see below).

Teams with a service under `teams/` today: **bridge** (source adapters & ingestion — GitHub, CRM,
unstructured intake, meeting/doc hand-off), **Chain** (dependency graph, task selection &
constraints), **inbox** (canonical Task data model & the backend APIs other teams build on),
**mind** (Python; proposes task dependencies and priority scores to Chain), **lens**
(`gateway`, `bff`, `frontend` + Storybook — the user-facing side), **relay** (event bus /
service communication, the shared Helm chart and CI/CD; its service is `notification_delivery`).

## Commands

Everything below is run from inside a given team's directory (`teams/<team>/`). The root
`pom.xml` only aggregates `teams/lens/gateway`; it is not a build for the other services.

```bash
mvn test                      # unit + slice tests
mvn -B package                # build the jar
mvn -B package -DskipTests    # build without tests, as each Dockerfile does
mvn spring-boot:run           # run locally (needs local JDK/Maven matching that team's version)

cp .env.example .env          # first run only
docker compose up --build     # containerized run
docker compose down           # stop (add -v to also drop named volumes)
```

**Versions differ per service — check `pom.xml` / `pyproject.toml` / `package.json` before assuming:**

| Service | Language | Framework |
|---|---|---|
| bridge | Java 21 | Spring Boot 3.3.13 |
| inbox | Java 21 | Spring Boot 3.3.13 |
| Chain | Java 21 | Spring Boot 3.3.13 |
| relay `notification_delivery` | Java 21 | Spring Boot 3.3.13 |
| lens `bff`, `gateway` | Java 21 | Spring Boot 3.4.0 |
| lens `frontend` | Node | see its `package.json` |
| mind | Python ≥ 3.12 | see its `pyproject.toml` |

(`teams/inbox/README.md` still describes Java 11 / Spring Boot 2.7.18 — that's stale; its `pom.xml`
and `teams/inbox/CLAUDE.md` are current and authoritative.)

CI/CD is a set of reusable workflows called by each team's own `<team>-ci.yaml`. Its `ci`
job calls `service-ci-<language>.yaml` (one per language), which runs `mvn -B test` (or the
Python/Node equivalent) on pull requests only. Its `release` job calls the single
`service-release.yaml` (takes `language` as an input, since a workflow's `uses:` target
can't be chosen dynamically) on push/tag/`workflow_dispatch`: its `build-and-push` job (still
CI) runs the same tests, then builds a GHCR image, smoke-tests it in a `kind` cluster and
pushes it; its `update-manifest` job (the CD part) then calls `service-update-manifest.yaml`
to open the manifest-bump PR. The
kind cluster + Helm chart setup shared by both phases lives in the
`.github/actions/setup-service-infra` composite action, so there's exactly one kind cluster
per job regardless of which phase runs. Each team's workflow (`bridge-ci.yaml`,
`inbox-ci.yaml`, etc.) is scoped with `paths: teams/<team>/**`, so it only fires on changes to
that team's own directory — despite that, the workflow **files themselves** must live at the
repo root under `.github/workflows/`; GitHub Actions silently ignores workflow files placed
inside a team's own folder.

## Architecture

### Team boundaries and data flow

- **inbox** owns the canonical `tasks` schema and is the sole authority for it. Every other team
  converges on this one representation. Its full development standard (layering, REST/Kafka
  contracts, idempotency, naming, error shape) lives in `teams/inbox/CLAUDE.md` and is loaded
  automatically for work inside `teams/inbox/` — read it before touching that module.
- **bridge** ingests from external sources and publishes structured task events to the single
  Kafka topic `bridge.structured.tasks` (all source platforms share this one topic; the platform
  travels inside the event as `sourceType`). **inbox** consumes it through one platform-agnostic
  `BridgeTaskConsumer` — adding a new source platform means adding a `SourceType` enum value, not a
  new consumer/topic/DTO. See `teams/inbox/README.md` ("Bridge task ingestion") for the
  idempotency key, ack mode, and failure-handling details.
- **Chain** owns the dependency graph and task selection/availability. `is_available` and
  `priority_score` on a task are Chain's computed values, delivered to inbox on
  `task.scheduling.updated` and only materialized (never computed) there.
- **relay** is the event bus / service-communication layer: `notification_delivery` pushes task
  notifications to users over WebSocket, and Relay owns the Kafka schemas, the shared Helm chart
  and the CI/CD.
- Every registered Kafka `.proto` schema lives once, in `shared/proto/` (Relay-owned). Services
  build against it via a `shared` named Docker build context — see `shared/proto/README.md`.
  Don't copy those files into a team's `src/main/proto/`.

### Deployment model (Helm + ArgoCD, GitOps)

- `teams/relay/helm/resonance-flow/` is the **one shared Helm chart** for every team's service —
  one release, one namespace (`resonance-flow`). Each team owns a `components.<team>` block (image,
  probes, resources, env) in `teams/relay/helm/resonance-flow/values.yaml`. Coordinate
  chart/template changes with its owner rather than editing the chart unilaterally.
- `values/values-<team>.yaml` is only for that team's CI install, and is opt-in: CI first applies
  an all-components-off overlay (`setup-service-infra`), so the file lists just the components
  and subcharts that service needs.
- The cluster's Kafka, Postgres and MinIO are Bitnami subcharts of `resonance-flow` (distinct from
  the single-node Kafka each team may run locally via its own `docker-compose.yml`).
- A team's CD, on push to `main`, builds and pushes a GHCR image, then opens a PR bumping the
  image tag in `teams/relay/helm/resonance-flow/values.yaml` (see
  `service-update-manifest.yaml`). ArgoCD (`teams/relay/argocd/`) watches the `resonance-flow`
  and `schema-sync-hook` charts and reconciles the cluster — no image updater involved. It also
  installs the Traefik ingress controller (`apps/traefik.yaml`), and loads
  `environments/shared.yaml` on top of `values.yaml` for shared-cluster-only settings (ingress
  host/TLS) that CI never sees.
- Because every team's component renders from the same Helm release into the same namespace, a
  broken value in one team's block (bad tag, template error) can block sync for every other team's
  service in the same `Application` — see `teams/relay/argocd/README.md` "Known limitation".
- A component that needs an infra dependency (Kafka, Postgres, MinIO) reachable before its main
  container starts declares it under `dependencies` in the Helm values; the chart adds a
  `wait-for-<name>` initContainer that blocks until `host:port` is TCP-reachable — see
  `teams/relay/helm/resonance-flow/README.md`.

### Local dev infra — known gaps

Some cross-team infra that individual teams' docs/configs already reference does not exist in this
repo yet. Don't assume it's there — check first:

- The root `docker-compose.yml` runs the shared Postgres (+ pgAdmin), the lens services, chain,
  bridge and inbox, and one shared Kafka broker, schema registry and Kafka UI (issue #1088). Each
  team also has its own `docker-compose.yml`, with a broker of its own.
- The registry starts empty and the producers don't auto-register schemas: after it starts, run
  `docker compose --profile seed run --rm schema-seed` once (again after a registry restart).

## Team-specific standards

Per-team `CLAUDE.md` files load automatically for work inside that directory:
`teams/inbox/CLAUDE.md`, `teams/bridge/CLAUDE.md`, `teams/chain/CLAUDE.md` and
`teams/lens/frontend/CLAUDE.md`. Inbox's is the full development standard and is binding for all
work under `teams/inbox/` — it covers the canonical schema/spec documents to check before touching
a field or enum, controller→service→repository layering, REST/Kafka rules, exception shape,
logging, and testing conventions.
