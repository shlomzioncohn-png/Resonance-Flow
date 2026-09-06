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
**relay** (event bus / service communication). **mind** and **lens-bff** / **lens-frontend** /
**lens-gateway** already have CI workflows and a `components.<team>` block in the shared Helm
chart's values, but no source tree under `teams/` in this repo yet.

## Commands

Everything below is run from inside a given team's directory (`teams/<team>/`) — there is no
root-level build.

```bash
mvn test                      # unit + slice tests
mvn -B package                # build the jar
mvn -B package -DskipTests    # build without tests, as each Dockerfile does
mvn spring-boot:run           # run locally (needs local JDK/Maven matching that team's version)

cp .env.example .env          # first run only
docker compose up --build     # containerized run
docker compose down           # stop (add -v to also drop named volumes)
```

**JVM versions differ per team — check `pom.xml` before assuming:**

| Team | Java | Spring Boot |
|---|---|---|
| bridge | 21 | 3.3.13 |
| inbox | 21 | 3.3.13 |
| Chain | 11 | 2.7.18 |
| relay | 11 | 2.7.18 |

(`teams/inbox/README.md` still describes Java 11 / Spring Boot 2.7.18 — that's stale; its `pom.xml`
and `teams/inbox/CLAUDE.md` are current and authoritative.)

CI (`.github/workflows/service-ci.yaml`, a reusable workflow) runs the same `mvn -B test`, then
builds/pushes a GHCR image and smoke-tests it in a `kind` cluster. Each team's workflow
(`teams/inbox/CLAUDE.md`'s consumer/idempotency section, `teams/inbox/README.md`'s "Bridge task
ingestion" section, and `bridge-ci.yaml` etc.) is scoped with `paths: teams/<team>/**`, so it only
fires on changes to that team's own directory — despite that, the workflow **files themselves**
must live at the repo root under `.github/workflows/`; GitHub Actions silently ignores workflow
files placed inside a team's own folder.

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
- **relay** is the event bus / service-communication layer; still a foundation skeleton.
- **mind** and **lens-*** are consumers/producers referenced by CI and Helm values but not yet
  present as code in this repo.

### Deployment model (Helm + ArgoCD, GitOps)

- `teams/relay/helm/resonance-flow/` is the **one shared Helm chart** for every team's service —
  one release, one namespace (`resonance-flow`). Each team owns a `components.<team>` block (image,
  probes, resources, env), split out per-team under
  `teams/relay/helm/resonance-flow/values/values-<team>.yaml`. Coordinate chart/template changes
  with its owner rather than editing the chart unilaterally.
- `kafka/` is a separate Helm chart for the **cluster's** shared Kafka (distinct from the
  single-node Kafka each team may run locally via its own `docker-compose.yml`).
- A team's CI, on push to `main`, builds and pushes a GHCR image, then commits the new image tag
  straight into `teams/relay/helm/resonance-flow/values/values-<team>.yaml` (see the `update-manifest` job in
  `service-ci.yaml`). ArgoCD (`teams/relay/argocd/`) watches the `resonance-flow` and `kafka`
  charts and reconciles the cluster — push-based GitOps, no image updater involved.
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

- No root-level `docker-compose.yml` exists yet. `teams/inbox/CLAUDE.md` describes one providing
  the shared Postgres instance; only per-team `teams/*/docker-compose.yml` files exist today.
- `shared/database/migrations` (the Flyway migration source mounted by
  `teams/inbox/docker-compose.yml`, and where any cross-team schema migration would need to be
  agreed and added) does not exist at the repo root yet either.
- Kafka for local dev currently only exists inside `teams/inbox/docker-compose.yml`
  (single-node, KRaft mode). Other teams that need a broker (bridge, relay) don't have one wired
  into their own compose files yet.

## Team-specific standards

`teams/inbox/CLAUDE.md` is the only per-team `CLAUDE.md` in the repo today and is binding for all
work under `teams/inbox/` — it covers the canonical schema/spec documents to check before touching
a field or enum, controller→service→repository layering, REST/Kafka rules, exception shape,
logging, and testing conventions. No other team has published an equivalent standard yet.
