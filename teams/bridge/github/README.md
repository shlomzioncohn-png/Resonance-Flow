# GitHub Bridge Adapter

A Spring Boot component of the **Team Bridge** module that ingests GitHub issues and pull requests, maps them to the canonical `Task` schema, validates them, and publishes the results downstream to an Apache Kafka topic.

## Overview

The GitHub Bridge Adapter is a structured ETL lane for the Resonance Flow platform. It exposes a manual sync endpoint, supports optional scheduled synchronization, and reuses the shared `AbstractTaskMapper` / `Task` contract so downstream consumers (Inbox, Chain, Lens) receive a single, validated task format regardless of the original source.

## Architecture & Components

| Component | Package / File | Responsibility |
|-----------|----------------|----------------|
| `WebClientConfig` | `com.rnd.bridge.github.config.WebClientConfig` | Builds the reactive `WebClient` used to call the GitHub REST API. Adds `Authorization: Bearer {GITHUB_TOKEN}` only when a token is configured. |
| `GitHubClient` | `com.rnd.bridge.github.service.GitHubClient` | Calls `/repos/{owner}/{repo}/issues` and `/repos/{owner}/{repo}/pulls` with `per_page`/`page` pagination (page size: `github.page-size`, default 100). Filters pull requests out of the `/issues` stream via the `pull_request` field so each PR is published only once. Maps each record through the appropriate mapper, validates it, and applies a 3-attempt exponential backoff retry on 5xx/429 responses. |
| `GitHubIssueMapper` | `com.rnd.bridge.github.mapper.GitHubIssueMapper` | Extends `AbstractTaskMapper<GitHubIssue>`, producing `Task` records with `SourceType.GITHUB_ISSUE`, `Tier.TACTICAL`, `RoleContext.DEVELOPER`. |
| `GitHubPullRequestMapper` | `com.rnd.bridge.github.mapper.GitHubPullRequestMapper` | Extends `AbstractTaskMapper<GitHubIssue>`, producing `Task` records with `SourceType.GITHUB_PR`, `Tier.TACTICAL`, `RoleContext.MANAGER`. |
| `TaskValidator` | `com.rnd.bridge.task.TaskValidator` | A Spring-managed instance bean (`@Component`) that fails fast on malformed `Task` objects before they leave the mapper or are published to Kafka. |
| `PayloadPublisher<Task>` | `com.rnd.bridge.core.PayloadPublisher` | Shared publisher - resolves to `KafkaPayloadPublisher` under the `kafka` profile (Protobuf `StructuredTaskEvent` to `bridge.structured.tasks` via `KafkaProtobufSerializer`), else `LoggingPayloadPublisher`. |
| `GitHubBridgeController` | `com.rnd.bridge.github.controller.GitHubBridgeController` | REST endpoint `POST /api/bridge/github/sync` for on-demand synchronization. |
| `GitHubSyncScheduler` | `com.rnd.bridge.github.scheduler.GitHubSyncScheduler` | Cron-based scheduler for nightly or periodic sync. Disabled by default. |
| `GitHubSchedulerConfig` | `com.rnd.bridge.github.config.GitHubSchedulerConfig` | Enables Spring scheduling (`@EnableScheduling`) for the GitHub module without touching the main application class. |

## Data Flow

1. `GitHubBridgeController` or `GitHubSyncScheduler` invokes `GitHubClient`.
2. `GitHubClient` emits two reactive streams: `fetchIssues(...)` and `fetchPullRequests(...)`, each paginating until a short (non-full) page is returned.
3. The `/issues` endpoint also returns pull requests (marked by a `pull_request` field); `fetchIssues` filters them out because `/pulls` already covers them.
4. Each `GitHubIssue` DTO is mapped to a `Task` by `GitHubIssueMapper` or `GitHubPullRequestMapper`, passing `Instant.now()` as the `asOf` parameter.
5. `TaskValidator` validates every `Task` immediately after mapping; a record that fails validation is logged and skipped without terminating the stream.
6. The shared `PayloadPublisher<Task>` publishes all valid tasks to `bridge.structured.tasks`, blocking per record so one failure can't fail the batch.

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
| `GITHUB_PAGE_SIZE` | `100` | `per_page` for the issues/pulls list endpoints (GitHub maximum: 100). |
| `GITHUB_SYNC_CRON_ENABLED` | `false` | Enables the scheduled `GitHubSyncScheduler` when `true`. |
| `GITHUB_SYNC_CRON_EXPRESSION` | `0 0 2 * * ?` | Cron expression for the scheduled sync (default: 02:00 daily). |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list. |
| `bridge.structured-tasks-topic` | `bridge.structured.tasks` | Kafka topic used by the shared `KafkaPayloadPublisher`. |
| `CRM_ORGANIZATION_ID` | empty | Inbox tenant UUID stamped onto every GitHub task's `organizationId` (via `bridge.organization-id`). Required under the `kafka` profile - Inbox's `tasks.tenant_id` is NOT NULL. Blank leaves it `null` (local `LoggingPayloadPublisher` runs only). |

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
    "fetched": 15,
    "published": 12
  }
  ```
  `fetched` is how many tasks the client pulled; `published` counts only records that were actually published - a record that fails validation/publish is logged and excluded, not counted.
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

- `GitHubClientTest`: Verifies issue/PR fetching and mapping, multi-page pagination, filtering pull requests out of the `/issues` stream, and that an invalid record is skipped while valid ones still flow.

## Tech Stack

- **Java 21**
- **Spring Boot 3.3.x**
- **Spring WebFlux / WebClient** for reactive GitHub REST API calls
- **Spring Kafka** for event publishing
- **Project Reactor (Mono / Flux)** for reactive stream processing
- **Protobuf** (`StructuredTaskEvent` via `KafkaProtobufSerializer`) for Kafka value serialization
