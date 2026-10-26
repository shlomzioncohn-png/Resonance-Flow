# Team LENS - API Gateway Service

**Phase 2 Step 6-7: COMPLETE ✅**  
**Status:** Gateway ready for Frontend ↔ BFF routing + Employee Workspace  
**Last Updated:** 2026-08-27

API Gateway service responsible for routing between **Frontend** and **BFF** (Backend for Frontend by Sari).

## What This Service Does

This service acts as the single entry point for the Frontend:
- Route requests from Frontend to BFF service only
- Error handling and status responses (404, 503, 504, etc)
- Gateway health monitoring (/health, /health/ready endpoints)
- CORS handling for Frontend requests

## What This Service Does NOT Do

❌ Talk directly to Bridge, Chain, Inbox, or Mind services  
❌ Monitor downstream service health  
❌ Implement service-specific logic  
❌ Aggregate data from multiple services (that's BFF's job)

**Each team implements their own service independently.**  
**BFF (Sari) is responsible for orchestrating all team services.**

---

## Architecture

```
Frontend (UI)
    ↓
LENS Gateway (Port 8080)
    ↓
BFF - Orchestrates all teams
    ↓
┌───┬───┬────┬──────┐
Bridge Chain Inbox Mind
```

---

## Why Spring Boot with RestTemplate (Not Spring Cloud Gateway)?

The initial design considered Spring Cloud Gateway but was replaced with manual routing using `RestTemplate` for these reasons:

1. **Simplicity** 
   - Currently 3 endpoints, doesn't need full gateway framework
   - Easier to understand and debug
   - Less boilerplate configuration

2. **Flexibility**
   - Can add custom error handling and timeouts per endpoint
   - Fine-grained control over BFF communication
   - Easy to add retry logic or circuit breakers later

3. **Team Scalability**
   - Clear, readable Java code in `WorkspaceController`
   - New team members understand the routing immediately
   - Changes are explicit, not hidden in YAML configuration

4. **Future Upgrade Path**
   - Once we have 10+ endpoints across multiple services, migration to Spring Cloud Gateway becomes worthwhile
   - Current implementation is a solid foundation

## Important Documents

**Before integrating other services, read:**
- [GATEWAY_CONTRACT.md](./GATEWAY_CONTRACT.md) - Technical contract and API specification
- [INTEGRATION_GUIDE.md](./INTEGRATION_GUIDE.md) - How your service integrates with Gateway

## Stack

- Java 21
- Spring Boot 3.4.0
- Spring Security (for authentication & authorization)
- Spring Security OAuth2 Resource Server — validates Keycloak-issued JWTs (see [Authentication](#authentication-keycloak))
- **Lombok 1.18.30** (reduces boilerplate code)
- Docker / Docker Compose

---

## Lombok - Reducing Boilerplate Code

[Lombok](https://projectlombok.org/) is a Java library that automatically generates boilerplate code (getters, setters, constructors, toString, etc.) at compile time using annotations.

### Why Lombok?

Traditional Java DTOs require ~100 lines of code per class:
```java
// ❌ Without Lombok (~100 lines)
public class ErrorResponse {
    private String status;
    private String errorCode;
    private String message;
    private String path;
    private LocalDateTime timestamp;
    
    // getters, setters, constructors, toString...
}
```

With Lombok, the same class is just:
```java
// ✅ With Lombok (~20 lines)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ErrorResponse {
    private String status;
    private String errorCode;
    private String message;
    private String path;
    private LocalDateTime timestamp;
}
```

### Annotations Used

- **`@Data`** - Generates getters, setters, equals(), hashCode(), toString()
- **`@NoArgsConstructor`** - Generates no-arg constructor
- **`@AllArgsConstructor`** - Generates constructor with all fields
- **`@ToString`** - Generates toString() method

### Maven Configuration

Lombok is configured in `pom.xml`:

```xml
<!-- Dependency -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.30</version>
    <scope>provided</scope>
</dependency>

<!-- Annotation Processor (for compile-time code generation) -->
<annotationProcessorPaths>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.30</version>
    </path>
</annotationProcessorPaths>
```

### Usage in Gateway

All DTOs in the Gateway use Lombok:
- `ErrorResponse.java` - Error response format
- `ServiceHealth.java` - Health status of a service
- Any future DTOs added to the project

---

## Project structure

```
src/main/java/com/rnd/gateway/
├── GatewayApplication.java   # Spring Boot entry point
├── config/                   # SecurityConfig (JWT required on /api/**), SecurityErrorHandler (JSON 401/403)
├── controller/                # WorkspaceController, HealthController
├── dto/                       # Request/response DTOs
└── exception/                 # GlobalExceptionHandler + typed exceptions
src/main/resources/
├── application.yml          # Gateway routes & configuration
└── application-local.yml    # Local-profile overrides (disables Spring Security's default login)
src/test/java/com/rnd/gateway/
```

## Authentication (Keycloak)

The Gateway is the single authentication point of the system. Every `/api/**`
request must send `Authorization: Bearer <access token>`, issued by the `lens`
Keycloak realm. The Gateway validates the token's **signature** (Keycloak's
public keys, fetched from its JWKS endpoint), **expiry** and **issuer**.
Anything else — no token, malformed, forged, tampered, expired, or from another
issuer — gets a JSON `401 UNAUTHORIZED` and **never reaches the BFF**.

`/health`, `/health/ready` and Swagger stay public.

> **Enforcement is off by default** (`app.auth.enabled` / `APP_AUTH_ENABLED=false`)
> until the frontend logs in through Keycloak and sends the token (Auth stage 3).
> While off, `/api/**` is open and any `Authorization` header is ignored. Set
> `APP_AUTH_ENABLED=true` to test with real tokens (as in the curl example below).

> Still mocked (Auth stage 2): the userId forwarded to BFF as `X-User-Id` is
> still `emp_001` (`/api/workspace/**`) or `mgr_001` (`/api/manager/users/**`, `/api/manager/dashboard/**`),
> not the token's `sub`, and roles aren't checked yet — any valid token reaches
> every `/api/**` route.

### Local Keycloak

`docker compose up keycloak` (from `teams/lens/`) starts Keycloak on
http://localhost:8180 (admin console: `admin` / `admin`). The realm is
imported from [`../keycloak/realm-lens.json`](../keycloak/realm-lens.json) on
every start (dev mode, in-memory DB — changes made in the console are lost on restart;
edit the JSON instead).

| User | Password | Realm role |
|------|----------|------------|
| `manager1` | `manager1` | `manager` |
| `employee1` | `employee1` | `employee` |

| Client | Purpose |
|--------|---------|
| `lens-frontend` | Browser login for the Next.js app (Auth stage 3) |
| `lens-dev-cli` | Local testing only — lets you get a token with curl |

### Testing with curl

```bash
TOKEN=$(curl -s http://localhost:8180/realms/lens/protocol/openid-connect/token \
  -d grant_type=password -d client_id=lens-dev-cli \
  -d username=employee1 -d password=employee1 | jq -r .access_token)

curl -i http://localhost:8080/api/workspace/tasks -H "Authorization: Bearer $TOKEN"  # 200
curl -i http://localhost:8080/api/workspace/tasks                                     # 401
```

The `# 401` line assumes `APP_AUTH_ENABLED=true`. Tokens live 5 minutes.
Automated coverage of every rejection case is in `SecurityJwtValidationTests`;
the off switch is covered by `AuthDisabledTests`.

---

## Gateway Routes

The Gateway currently handles these endpoints (manual routing via explicit controllers):

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/workspace/recommend` | POST | Get task recommendation for available time |
| `/api/workspace/tasks/{taskId}` | GET | Get full task details by ID |
| `/api/workspace/tasks/{taskId}/activity` | GET | Task history from Chain, newest first (the task page's Activity tab) |
| `/api/workspace/tasks/{taskId}/status` | PATCH | Update task status |
| `/api/manager/dashboard/kpis` | GET | Manager Dashboard KPI tiles for the manager's tenant (`?period=`, `?project=` optional) |
| `/api/manager/users` | GET | List users, MANAGER only — see below |
| `/api/manager/users` | POST | Create a user (displayName + role MANAGER/EMPLOYEE), MANAGER only |
| `/api/manager/users/{id}` | GET | Get one user's details, MANAGER only |
| `/api/manager/users/{id}` | PATCH | Edit displayName/firstName/lastName/phone, MANAGER only |
| `/api/manager/users/{id}/status` | PATCH | Change status (ACTIVE/SUSPENDED/DISABLED), MANAGER only |

**All endpoints forward to BFF** at `http://bff-service:8080` using `RestTemplate`.

**Known gap (Auth stage 2):** `/api/manager/users/**` is meant to be MANAGER-only, but the
Gateway doesn't check roles yet — like every other `/api/**` route it only requires a valid token
(when `APP_AUTH_ENABLED=true`). `ManagerUsersController` forwards a mock `X-User-Id` (`mgr_001`)
and `X-User-Role: MANAGER`. The Gateway never knows Inbox user ids: the BFF maps the mock id to a
real Inbox user (`INBOX_DEV_ACTOR_ID`) before calling Inbox — see the BFF README.

**Future phases:** Once more team services are ready, BFF will expand to handle Bridge, Chain, Inbox, and Mind routing. Gateway's endpoint structure remains unchanged.

---

## Health Check Endpoints

**Status:** 🕐 *Pending approval from tech lead - Implemented but awaiting confirmation before production use*

The Gateway provides health check endpoints for monitoring and orchestration:

### `GET /health` - Full Health Status

Returns the health of the Gateway AND its BFF dependency:

```bash
curl http://localhost:8080/health
```

**Response (BFF is UP):**
```json
{
  "status": "UP",
  "service": "gateway",
  "version": "0.1.0",
  "timestamp": "2026-08-26T10:30:45.123456Z",
  "dependencies": {
    "bff": "UP",
    "bff_url": "http://bff-service:8080/health"
  }
}
```

**Response (BFF is DOWN):**
```json
{
  "status": "UP",
  "service": "gateway",
  "version": "0.1.0",
  "timestamp": "2026-08-26T10:30:45.123456Z",
  "dependencies": {
    "bff": "DOWN",
    "bff_url": "http://bff-service:8080/health"
  }
}
```

**Status Codes:**
- `200 OK` - Gateway is healthy (shown regardless of BFF status)
- Always check the `dependencies.bff` field to see if BFF is available

### `GET /health/ready` - Readiness Check

Returns readiness status for deployment orchestration (K8s, Docker Swarm):

```bash
curl http://localhost:8080/health/ready
```

**Response:**
```json
{
  "status": "UP",
  "service": "gateway",
  "ready": true,
  "timestamp": "2026-08-26T10:30:45.123456Z",
  "dependencies": {
    "bff": "UP",
    "bff_ready": true
  }
}
```

### How Health Checks Work

1. **Gateway Health**
   - Always returns `UP` when the Gateway process is running
   - Reports the Gateway version and current timestamp

2. **BFF Dependency Check**
   - Gateway makes an HTTP call to `http://bff-service:8080/health`
   - If BFF responds with `200 OK`, status is `UP`
   - If BFF is unreachable or returns error, status is `DOWN`
   - Timeout and exceptions are caught and logged (non-fatal)

3. **Failure Handling**
   - Gateway remains `UP` even if BFF is `DOWN`
   - Frontend/consumers should check the `dependencies.bff` field
   - If `bff: "DOWN"`, expect API requests to fail with `503 SERVICE_UNAVAILABLE`

### Docker Healthcheck

Docker Compose automatically probes `/health` every 10 seconds:

```yaml
healthcheck:
  test: ["CMD", "curl", "http://localhost:8080/health"]
  interval: 10s
  timeout: 5s
  retries: 3
  start_period: 20s
```

Container status becomes `unhealthy` after 3 consecutive failed checks.

---

## Employee Workspace - Task Recommendation

**Status:** 🆕 NEW - Phase 2 Step 7  
**Controller:** `WorkspaceController.java`  
**Endpoint:** `POST /api/workspace/recommend`

### Purpose

Provides task recommendations to employees based on available time.

**Main Question:** "I have 45 minutes - what should I work on?"

### Endpoint

**Request:**
```bash
POST /api/workspace/recommend
Content-Type: application/json

{
  "userId": "emp_001",
  "availableTime": 45,
  "projectFilter": null,
  "minPriority": null
}
```

**Response:**
```json
{
  "task": {
    "id": "task_001",
    "title": "Review API changes",
    "description": "Review the new API endpoints and provide feedback",
    "status": "READY",
    "priority": "HIGH",
    "effortEstimate": 35,
    "dueDate": "2026-08-28T17:00:00",
    "owner": "Rachel",
    "project": "Platform Service",
    "dependencies": [],
    "constraints": []
  },
  "explanation": "Fits in 45 minutes ✓, High importance ✓, No blockers ✓",
  "fitsInTime": true,
  "isHighPriority": true,
  "hasNoDependencies": true,
  "confidenceScore": 85,
  "alternatives": []
}
```

### How It Works

1. **Frontend** sends `WorkspaceRequestDTO`
   - `userId`: Which employee
   - `availableTime`: Minutes available (e.g., 45)
   - Optional: `projectFilter`, `minPriority`

2. **Gateway** (WorkspaceController) forwards to BFF

3. **BFF** orchestrates:
   - **Mind** scores all tasks
   - **Chain** selects best feasible task based on:
     - Available time constraint
     - No blocking dependencies
     - Highest priority/importance
   - Returns `TaskRecommendationDTO` with task + explanation

4. **Gateway** forwards response back to Frontend

5. **Frontend** displays:
   ```
   ✨ BEST TASK FOR YOU
   
   Review API changes                     High
   Platform Service
   
   ✓ Fits in your 45 minutes
   ✓ High importance
   ✓ No blocking dependency
   
   [ Start task ]  [ View details ]
   ```

### Flow Diagram

```
Frontend
  │
  │ POST /api/workspace/recommend
  │ WorkspaceRequestDTO
  │ (userId, availableTime)
  ▼
Gateway (WorkspaceController)
  │
  │ Forward to BFF
  ▼
BFF
  ├── Mind: Score all tasks
  ├── Chain: Select best feasible
  └── Return TaskRecommendationDTO
  │
  │ (task + explanation + flags)
  ▼
Gateway
  │
  │ Return response
  ▼
Frontend
  │
  └── Display recommended task
```

### DTOs

There is no shared DTO library — each layer owns its own copy in its own package,
kept structurally in sync by convention (per `docs/workspace-screen-integration-plan.md`
principle #2, "each layer owns its DTOs"):
- **Gateway:** `com.rnd.gateway.dto` (this module)
- **BFF:** `com.rnd.bff.dto`
- **Frontend:** TypeScript interfaces in `services/workspaceApi.ts`

`WorkspaceRequestDTO`, `TaskDTO`, and `TaskRecommendationDTO` exist independently in
Gateway and BFF. When a field is added or removed on one side, update the other by hand.

---

## Building & Running

To just build the jar:

```bash
mvn package -DskipTests
```

## Running with Docker

Build and run with Docker Compose. This compose file starts only the Gateway, so start the BFF
first from `teams/lens/bff` (`docker-compose up --build`). The BFF's own compose file is what
connects it to Inbox; the Gateway only ever calls the BFF, at `host.docker.internal:8082`.

```bash
docker-compose up --build
```

The Gateway will be available at http://localhost:8080/api

To stop:

```bash
docker-compose down
```

## Environment variables

- `SERVER_PORT` - Server port (default: 8080)
- `SPRING_PROFILES_ACTIVE` - Active Spring profile (default: local)

## Environment Variables

Configure Gateway behavior via environment variables. All defaults allow local Docker Compose testing without modification.

### BFF Service Configuration

| Variable | Default | Purpose | Example |
|----------|---------|---------|---------|
| `BFF_SERVICE_URL` | `http://bff-service:8080` | Base URL for BFF service | `http://bff-prod.example.com:8080` |
| `BFF_CONNECT_TIMEOUT_MS` | `2000` | Connection timeout (ms) | `5000` |
| `BFF_READ_TIMEOUT_MS` | `10000` | Read timeout (ms) | `15000` |
| `BFF_ENDPOINT_WORKSPACE_RECOMMEND` | `/workspace/recommend` | Workspace recommendation endpoint | `/api/v1/workspace/recommend` |
| `BFF_ENDPOINT_TASKS` | `/workspace/tasks` | Task operations endpoint | `/api/v1/tasks` |

### Authentication Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8180/realms/lens` | Must equal the token's `iss` claim (Keycloak's public URL) |
| `KEYCLOAK_JWK_SET_URI` | `http://localhost:8180/realms/lens/protocol/openid-connect/certs` | Where the Gateway fetches signing keys. In docker-compose: `http://keycloak:8080/...` |

### Server Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `SERVER_PORT` | `8080` | Gateway service port |
| `SPRING_PROFILES_ACTIVE` | `local` | Active Spring profile (local/prod/etc) |

### Using with Docker

Set environment variables in `docker-compose.yml`:

```yaml
services:
  gateway:
    environment:
      - BFF_SERVICE_URL=http://bff-service:8080
      - BFF_READ_TIMEOUT_MS=15000
      - SPRING_PROFILES_ACTIVE=local
```

Or in a `.env` file:

```
BFF_SERVICE_URL=http://bff-service:8080
BFF_READ_TIMEOUT_MS=15000
SPRING_PROFILES_ACTIVE=local
```

---

## Next Steps

- ✅ Phase 2 Step 6: Gateway Core Complete - Routes to BFF only
- ✅ Workspace endpoints: task recommendation, task details, status updates
- 🕐 Phase 2 Enhancement: Health Check endpoints (implemented, awaiting tech lead approval)
- 🔄 Phase 3: BFF Implementation (Sari) — Orchestrates Bridge, Chain, Inbox, Mind
- 🔄 Phase 4: Frontend Development
- 🔄 Phase 5: Authentication & Authorization (Keycloak) — stage 1 done: JWT validation at the Gateway; next: userId/role to BFF, frontend login, OTP
- 🔄 Phase 6: Rate limiting & Circuit breakers
