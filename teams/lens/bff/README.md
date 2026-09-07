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

- Health check (Gateway-facing, GATEWAY_CONTRACT.md §7 shape): `GET /health`
- Health check (Spring Actuator, different shape): `GET /actuator/health`
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

## Security & Architecture Notes

### X-User-Id Header Trust Model

The BFF **trusts X-User-Id headers implicitly**, without verification. This is architecturally
safe **only if**:

1. The Gateway is the **exclusive entry point** for all requests to the BFF — the BFF is never
   exposed directly (firewall rules, network isolation, Kubernetes service mesh policies).
2. The Gateway is **trustworthy** — it validates JWT tokens and sets X-User-Id only for
   authenticated callers matching the JWT subject.
3. **Deployment isolation** — the BFF is not exposed on public networks or uncontrolled
   internal networks.

If any of these assumptions break (e.g., BFF port is exposed, Gateway is compromised, or
network misconfiguration allows direct calls), callers can spoof any user ID, leading to
**IDOR (Insecure Direct Object Reference) vulnerabilities**.

**Mitigation strategies (if isolation cannot be guaranteed):**
- Add an authentication layer inside the BFF (e.g., verify JWT tokens independently)
- Reject requests that claim a user ID not present in a trusted Authorization header
- Monitor for direct BFF calls outside the Gateway (should not happen in normal operation)
- Enforce network policies (firewall, service mesh) to restrict BFF access

For now, assume the Gateway is trusted and network isolation is in place. **Flag this for review
if deployment strategy changes.**

### Test Coverage

- **WorkspaceControllerTest** — HTTP request/response cycles, validation errors, 404s, and downstream failures (including missing headers, validation failures). Exception handler is tested implicitly here.
- **WorkspaceServiceTest** — Orchestration logic, multi-client failures, edge cases in filtering/pagination, fallback semantics.
- Future: Direct GlobalExceptionHandler unit tests for exception-to-HTTP-status mapping can be added if verification needs to be isolated from controller tests.

## Next steps

- Wire `InboxClient`, `ChainClient`, and `MindClient` to their real APIs once
  each service exposes one — see the `TODO` in each client class
- Wire up authentication/authorization propagated from the Gateway
- Confirm "waiting_on_me" filter semantics with Product team (currently mapped to READY status)
