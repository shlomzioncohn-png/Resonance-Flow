# GitHub Bridge Adapter

A Spring Boot component of the **Team Bridge** module that ingests GitHub issues and pull requests, maps them to the canonical `Task` schema, validates them, and publishes the results downstream to an Apache Kafka topic.

## Overview

The GitHub Bridge Adapter is a structured ETL lane for the Resonance Flow platform. It exposes a manual sync endpoint, supports optional scheduled synchronization, and reuses the shared `AbstractTaskMapper` / `Task` contract so downstream consumers (Inbox, Chain, Lens) receive a single, validated task format regardless of the original source.

## Architecture & Components

| Component | Package / File | Responsibility |
|-----------|----------------|----------------|
| `WebClientConfig` | `com.rnd.bridge.github.config.WebClientConfig` | Builds the reactive `WebClient` used to call the GitHub REST API. Adds `Authorization: Bearer {GITHUB_TOKEN}` only when a token is configured. |
| `GitHubClient` | `com.rnd.bridge.github.service.GitHubClient` | Calls `/repos/{owner}/{repo}/issues` and `/repos/{owner}/{repo}/pulls`, maps each record through the appropriate mapper, validates the result and applies a 3-attempt exponential backoff retry on 5xx/429 responses. |
| `GitHubIssueMapper` | `com.rnd.bridge.github.mapper.GitHubIssueMapper` | Extends `AbstractTaskMapper<GitHubIssue>`, producing `Task` records with `SourceType.GITHUB_ISSUE`, `Tier.TACTICAL`, `RoleContext.DEVELOPER`. |
| `GitHubPullRequestMapper` | `com.rnd.bridge.github.mapper.GitHubPullRequestMapper` | Extends `AbstractTaskMapper<GitHubIssue>`, producing `Task` records with `SourceType.GITHUB_PR`, `Tier.TACTICAL`, `RoleContext.MANAGER`. |
| `TaskValidator` | `com.rnd.bridge.task.TaskValidator` | A Spring-managed instance bean (`@Component`) that fails fast on malformed `Task` objects before they leave the mapper or are published to Kafka. |
| `GitHubKafkaProducer` | `com.rnd.bridge.github.service.GitHubKafkaProducer` | Publishes each validated `Task` to the configured Kafka topic using `sourceRef` as the record key and `JsonSerializer` for the value. |
| `GitHubBridgeController` | `com.rnd.bridge.github.controller.GitHubBridgeController` | REST endpoint `POST /api/bridge/github/sync` for on-demand synchronization. |
| `GitHubSyncScheduler` | `com.rnd.bridge.github.scheduler.GitHubSyncScheduler` | Cron-based scheduler for nightly or periodic sync. Disabled by default. |
| `GitHubSchedulerConfig` | `com.rnd.bridge.github.config.GitHubSchedulerConfig` | Enables Spring scheduling (`@EnableScheduling`) for the GitHub module without touching the main application class. |

## Data Flow

1. `GitHubBridgeController` or `GitHubSyncScheduler` invokes `GitHubClient`.
2. `GitHubClient` emits two reactive streams: `fetchIssues(...)` and `fetchPullRequests(...)`.
3. Each `GitHubIssue` DTO is mapped to a `Task` by `GitHubIssueMapper` or `GitHubPullRequestMapper`, passing `Instant.now()` as the `asOf` parameter.
4. `TaskValidator` validates every `Task` immediately after mapping.
5. `GitHubKafkaProducer` publishes all valid tasks to Kafka using `kafkaTemplate.send(topic, sourceRef, task)`.

## Canonical Task Mapping

The mappers extend `AbstractTaskMapper<GitHubIssue>` and therefore produce a `Task` record with the following fixed values and transformations:

- `organizationId`: `null` (cross-team contract gap, to be resolved by Inbox).
- `projectId`: `null` (no lane currently sources this).
- `title`: `GitHubIssue.getTitle()`.
- `description`: `GitHubIssue.getBody()`.
- `status`: `open` -> `TaskStatus.NEW`, `closed` -> `TaskStatus.DONE`, any other state -> `TaskStatus.IN_PROGRESS`.
- `urgency`: `null` (currently not sourced from GitHub).
- `confidence`: `null` (deterministic ETL lane, no ML confidence).
- `tier`: `Tier.TACTICAL`.
- `extractionProvenance`: `ExtractionProvenance.ETL`.
- `roleContext`: `RoleContext.DEVELOPER` for issues, `RoleContext.MANAGER` for pull requests.
- `sourceType`: `SourceType.GITHUB_ISSUE` or `SourceType.GITHUB_PR`.
- `sourceRef`: `GitHubIssue.getHtmlUrl()`.
- `effortEstimateMinutes`: `null`.
- `deadline`: `null`.
- `snoozedUntil`: `null`.

## Configuration / Environment Variables

All values are overridable through `application.yml`, command-line arguments or environment variables.

| Property | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | HTTP port for the Bridge service. |
| `GITHUB_API_BASE_URL` | `https://api.github.com` | Base URL of the GitHub REST API. |
| `GITHUB_TOKEN` | empty | Optional GitHub personal access token. Required only for higher rate limits or private repositories; if empty, the `Authorization` header is omitted. |
| `GITHUB_TARGET_OWNER` | `KamaTechOrg` | Default owner for scheduled or parameterless sync. |
| `GITHUB_TARGET_REPO` | `Resonance-Flow` | Default repository for scheduled or parameterless sync. |
| `GITHUB_SYNC_CRON_ENABLED` | `false` | Enables the scheduled `GitHubSyncScheduler` when `true`. |
| `GITHUB_SYNC_CRON_EXPRESSION` | `0 0 2 * * ?` | Cron expression for the scheduled sync (default: 02:00 daily). |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list. |
| `spring.kafka.topic` | `bridge.test` in `application.yml`; producer default `bridge.github.tasks` if not configured | Kafka topic used by `GitHubKafkaProducer`. The recommended topic is `bridge.github.tasks`. |

## API Endpoints

### Manual GitHub Sync

- **URL**: `/api/bridge/github/sync`
- **Method**: `POST`
- **Query Parameters**:
  - `owner` (optional, default `KamaTechOrg`): GitHub organization or username.
  - `repo` (optional, default `Resonance-Flow`): Target repository name.
- **Success Response**: `200 OK`
  ```json
  {
    "owner": "KamaTechOrg",
    "repo": "Resonance-Flow",
    "published": 12
  }
  ```
- **Error Response**: `500 Internal Server Error`
  ```json
  {
    "error": "...",
    "owner": "KamaTechOrg",
    "repo": "Resonance-Flow"
  }
  ```

## Retry & Error Handling

`GitHubClient` uses `Retry.backoff(3, Duration.ofSeconds(1))` and retries only when the downstream exception is a `WebClientResponseException` whose status code is either a 5xx server error or a 429 rate-limit response. All other errors are logged and propagated immediately.

## Testing

Unit and integration tests for this adapter include:

- `GitHubClientTest`: Verifies issue/PR fetching, retry behavior and mapping delegation.
- `GitHubKafkaProducerTest`: Verifies task validation, topic selection and Kafka template invocation.

## Tech Stack

- **Java 21**
- **Spring Boot 3.3.x**
- **Spring WebFlux / WebClient** for reactive GitHub REST API calls
- **Spring Kafka** for event publishing
- **Project Reactor (Mono / Flux)** for reactive stream processing
- **Jackson** via `JsonSerializer` for Kafka value serialization
