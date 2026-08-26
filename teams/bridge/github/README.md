# GitHub Bridge Adapter

A Spring Boot-based microservice component responsible for synchronizing GitHub issues and pull requests into canonical task events and publishing them to an Apache Kafka message broker.

## Overview

The `GitHub Bridge Adapter` serves as an ETL pipeline component for the **Resonance Flow** project. It integrates with the public GitHub REST API to fetch issues and pull requests, maps them into a unified canonical `Task` data structure, and streams them downstream via Kafka.

## Architecture & Components

- **`GitHubBridgeController`**: Exposes REST endpoints (such as `/api/bridge/github/sync`) to trigger manual or automated synchronization for a given repository (`owner/repo`), returning a reactive response (`Mono<ResponseEntity<Map<String, Object>>>`).
- **`WebClientConfig`**: Configures and wires the reactive `WebClient` with the base URL (`${GITHUB_API_BASE_URL}`) and optional authorization header (`Authorization: Bearer ${GITHUB_TOKEN}`).
- **`GitHubClient`**: A reactive WebClient-based service that communicates with the GitHub REST API (`/repos/{owner}/{repo}/issues` and `/repos/{owner}/{repo}/pulls`), featuring a built-in exponential backoff retry mechanism (retrying 3 times with a 1-second delay specifically on 5xx server errors and 429 rate-limiting responses).
- **Canonical Task Mapping**: Automatically transforms GitHub issues and pull requests into canonical `Task` objects. It builds a deterministic ID pattern (`github:<repo>:<issue|pr>:<number>`), maps source types (`SourceType.GITHUB`), tiers (`Tier.TIER_2`), provenance (`ExtractionProvenance.API`), and translates GitHub states (`open`/`closed`) to standard internal statuses (`OPEN`, `CLOSED`, `IN_PROGRESS`).
- **`GitHubKafkaProducer`**: Publishes the mapped tasks asynchronously to the Kafka topic `bridge.github.tasks` using `JsonSerializer`.

## Configuration / Environment Variables

Make sure to configure the following environment variables or application properties:

- `GITHUB_TOKEN`: Your GitHub personal access token for authentication (recommended for higher rate limits).
- `GITHUB_API_BASE_URL`: The base URL for the GitHub API (defaults to `https://api.github.com`).
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka broker address (defaults to `localhost:9092`).
- `SERVER_PORT`: The port on which the service runs.

## API Endpoints

### Sync Repository
- **URL**: `/api/bridge/github/sync`
- **Method**: `POST`
- **Parameters**:
  - `owner` (String): The GitHub organization or username.
  - `repo` (String): The target repository name.
- **Response**: Returns a JSON object containing the owner, repository name, and the total number of successfully published tasks, or a 500 internal server error response body upon failure.

## Testing

The project includes unit tests for the core components:
- `GitHubClientTest`
- `GitHubKafkaProducerTest`

## Tech Stack

- **Java 21** / **Spring Boot**
- **Spring WebFlux (WebClient)** for reactive HTTP calls to GitHub API
- **Spring Kafka** for message publishing
- **Project Reactor (Mono/Flux)** for asynchronous stream processing
