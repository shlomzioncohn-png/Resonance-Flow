# Team LENS - BFF Service

Backend-For-Frontend service responsible for routing requests from the Gateway to the
backing services (Bridge, Mind, Inbox, Chain) and mapping their entities to DTOs
consumed by the Frontend.

## Responsibilities

- Aggregate/compose calls to downstream services
- Map service entities to frontend-facing DTOs
- Shield the Frontend from downstream service contract changes

## Stack

- Java 21
- Spring Boot 3.4.0 (Web, Actuator)
- Maven
- Docker / Docker Compose

## Project structure

```
src/main/java/com/rnd/bff/
├── BffApplication.java   # Spring Boot entry point
├── controller/            # REST controllers
├── service/               # Orchestration (combines client responses into a DTO)
├── client/                # One class per downstream service (Inbox/Chain/Mind) -
│                            mocked at the service boundary until each is live
├── dto/                   # Request/response shapes
└── exception/             # GlobalExceptionHandler + uniform ErrorResponse
src/main/resources/
└── application.yml       # config (port, actuator)
src/test/java/com/rnd/bff/
```

## Running locally

Requires JDK 21 and Maven 3.9+.

```bash
mvn spring-boot:run
```

The service starts on [http://localhost:8082](http://localhost:8082).

- Health check: `GET /actuator/health`
- Status: `GET /api/status`

## Running with Docker

```bash
docker-compose up --build
```

To stop:

```bash
docker-compose down
```

## Building

```bash
mvn clean package
java -jar target/bff-0.1.0-SNAPSHOT.jar
```

## Environment variables

- `SERVER_PORT` - Server port (default: 8082)
- `SPRING_PROFILES_ACTIVE` - Active Spring profile (default: local)

## Endpoints

### `POST /workspace/recommend`

Employee workspace recommendation — returns the best-fit task for the caller's
available time. Full contract (request/response shape, error format, identity
flow): [../docs/workspace-screen-integration-plan.md](../docs/workspace-screen-integration-plan.md).

- Header: `X-User-Id` (required — set by Gateway, extracted from the JWT once
  Auth & Identity exists; Gateway currently sends a mock value).
- Body: `WorkspaceRequestDTO` (`availableTime` required, `projectFilter` /
  `minPriority` optional).
- Returns `TaskRecommendationDTO`. `WorkspaceService` orchestrates three
  clients — `InboxClient` (task data), `ChainClient` (dependencies/
  feasibility), `MindClient` (scoring) — each currently mocked at the
  service boundary (same method signature the real HTTP call will use, so
  wiring one up is a body swap, not a redesign). No live downstream calls
  yet.
- Errors follow the uniform `ErrorResponse` shape
  (`errorCode`/`message`/`path`/`timestamp`/`details`), matching the
  Gateway's format.

### `GET /api/status`

Basic liveness check (`StatusController`).

## Status

DTOs, the `/workspace/recommend` endpoint, and the `InboxClient`/
`ChainClient`/`MindClient` orchestration are implemented against mock data
(stage 2). No live calls to Bridge, Mind, Inbox, or Chain yet.

## Next steps

- Wire `InboxClient`, `ChainClient`, and `MindClient` to their real APIs once
  each service exposes one — see the `TODO` in each client class
- Wire up authentication/authorization propagated from the Gateway
