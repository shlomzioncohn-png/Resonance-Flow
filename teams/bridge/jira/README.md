# Jira Bridge Adapter

A Spring Boot component of the **Team Bridge** module that searches Jira issues via JQL, maps them to the canonical `Task` schema, validates them, and publishes the results downstream to an Apache Kafka topic.

## Overview

The Jira Bridge Adapter is a structured ETL lane for the Resonance Flow platform. It exposes a manual sync endpoint, supports optional scheduled synchronization, and reuses the shared `AbstractTaskMapper` / `Task` contract so downstream consumers (Inbox, Chain, Lens) receive a single, validated task format regardless of the original source.

## Architecture & Components

| Component | Package / File | Responsibility |
|-----------|----------------|----------------|
| `JiraClient` | `com.rnd.bridge.jira.service.JiraClient` | Calls `/rest/api/2/search` with `startAt`/`maxResults` pagination (page size: `jira.page-size`, default 100), iterating offsets until the reported `total` is consumed or an empty page returns. Maps each issue through `JiraIssueMapper`, validates it, and applies a 3-attempt exponential backoff retry on 5xx/429 responses. |
| `JiraIssueMapper` | `com.rnd.bridge.jira.mapper.JiraIssueMapper` | Extends `AbstractTaskMapper<JiraIssue>`, producing `Task` records with `SourceType.JIRA`. |
| `TaskValidator` | `com.rnd.bridge.task.TaskValidator` | Validates each `Task` after mapping; invalid records are logged and skipped without terminating the stream. |
| `PayloadPublisher<Task>` | `com.rnd.bridge.core.PayloadPublisher` | Shared publisher - resolves to `KafkaPayloadPublisher` under the `kafka` profile (Protobuf `StructuredTaskEvent` to `bridge.structured.tasks`), else `LoggingPayloadPublisher`. |
| `JiraBridgeController` | `com.rnd.bridge.jira.controller.JiraBridgeController` | REST endpoint `POST /api/bridge/jira/sync` for on-demand synchronization (optional `projectKey` parameter). |
| `JiraSyncScheduler` | `com.rnd.bridge.jira.scheduler.JiraSyncScheduler` | Cron-based scheduler for periodic sync. Disabled by default. |

## Data Flow

1. `JiraBridgeController` or `JiraSyncScheduler` invokes `JiraClient.searchIssues(jql)`.
2. `JiraClient` pages through `/rest/api/2/search` (`startAt` advances by the number of issues consumed; stops at `total` or on an empty page).
3. Each `JiraIssue` DTO is mapped to a `Task` by `JiraIssueMapper`, passing `Instant.now()` as the `asOf` parameter.
4. `TaskValidator` validates every `Task` immediately after mapping; a record that fails validation is logged and skipped without terminating the stream.
5. The shared `PayloadPublisher<Task>` publishes all valid tasks to `bridge.structured.tasks`, blocking per record so one failure can't fail the batch.

## Configuration / Environment Variables

| Property | Default | Description |
|----------|---------|-------------|
| `JIRA_API_BASE_URL` | `https://example.atlassian.net` | Base URL of the Jira instance. |
| `JIRA_TOKEN` | empty | Jira API token; if empty, no auth header is sent. |
| `JIRA_TARGET_PROJECT` | empty | Default project key used when the sync is called without a `projectKey` parameter. |
| `JIRA_PAGE_SIZE` | `100` | `maxResults` for `/rest/api/2/search` pagination. |
| `JIRA_SYNC_CRON_ENABLED` | `false` | Enables the scheduled `JiraSyncScheduler` when `true`. |
| `JIRA_SYNC_CRON_EXPRESSION` | `0 0 2 * * ?` | Cron expression for the scheduled sync (default: 02:00 daily). |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list. |
| `bridge.structured-tasks-topic` | `bridge.structured.tasks` | Kafka topic used by the shared `KafkaPayloadPublisher`. |

## Retry & Error Handling

`JiraClient` uses `Retry.backoff(3, Duration.ofSeconds(1))` and retries only when the downstream exception is a `WebClientResponseException` whose status code is either a 5xx server error or a 429 rate-limit response. All other errors are logged and propagated immediately.

## Testing

- `JiraClientTest`: verifies issue mapping, multi-page pagination (`startAt`/`total`), empty-result handling, and that an invalid record is skipped while valid ones still flow.

## Tech Stack

- **Java 21**
- **Spring Boot 3.3.x**
- **Spring WebFlux / WebClient** for reactive Jira REST API calls
- **Spring Kafka** for event publishing
- **Project Reactor (Mono / Flux)** for reactive stream processing
