# Team LENS - API Gateway Service

**Phase 2 Step 6: COMPLETE ✅**  
**Status:** Gateway ready for Frontend ↔ BFF routing  
**Last Updated:** 2026-08-25

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

## Why Spring Cloud Gateway?

**Spring Cloud Gateway** was chosen instead of plain Spring Boot because:

1. **Purpose-Built for Gateways** 
   - Designed specifically for API gateway patterns
   - Optimized non-blocking request handling
   - Reactive stack for high throughput

2. **Built-in Features** (No Custom Code Needed)
   - Route configuration via YAML (not Java code)
   - Pre-built filters: rate limiting, retry, circuit breaker
   - Request/response transformation filters
   - Load balancing and service discovery support

3. **Configuration-Driven Architecture**
   - Routes defined in `application.yml`
   - Easy to add new routes without coding
   - Filters applied declaratively

4. **Performance**
   - Non-blocking I/O (Project Reactor)
   - Better resource utilization
   - Lower memory footprint than traditional servlet containers

5. **Team Scalability**
   - Less boilerplate code to maintain
   - New team members can add routes via configuration
   - Clear separation of concerns

## Important Documents

**Before integrating other services, read:**
- [GATEWAY_CONTRACT.md](./GATEWAY_CONTRACT.md) - Technical contract and API specification
- [INTEGRATION_GUIDE.md](./INTEGRATION_GUIDE.md) - How your service integrates with Gateway

## Stack

- Java 21
- Spring Boot 3.4.0
- Spring Cloud Gateway 2024.0.0
- Spring Security (for authentication & authorization)
- JWT for token-based authentication (Phase 2)
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
├── security/                 # Authentication & authorization
├── filters/                  # Custom gateway filters
└── config/                   # Configuration classes
src/main/resources/
├── application.yml          # Gateway routes & configuration
└── application-dev.yml      # Development profile
src/test/java/com/rnd/gateway/
```

## Gateway Routes

The gateway has a single route configured:

| Route | Service | Path | Forwards To |
|-------|---------|------|---|
| `/api/**` | BFF | All API requests | `http://bff-service:8080/` |

**Path Stripping:** The `/api` prefix is stripped before forwarding (1 segment removed).

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

## Building & Running

To just build the jar:

```bash
mvn package -DskipTests
```

## Running with Docker

Build and run with Docker Compose:

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

## Configuration Example

Routes are configured in `application.yml`:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: bff
          uri: http://bff-service:8080
          predicates:
            - Path=/api/**
          filters:
            - StripPrefix=1
```

---

## BFF Integration (Sari's Responsibility)

The BFF service (by Sari) receives ALL requests through the Gateway and is responsible for:

1. **Routing to team services:**
   - Bridge: `/api/bridge/...` logic
   - Chain: `/api/chain/...` logic
   - Inbox: `/api/inbox/...` logic
   - Mind: `/api/mind/...` logic

2. **Data orchestration:**
   - Calling multiple team services as needed
   - Combining and transforming responses
   - Handling service failures gracefully

3. **Frontend requirements:**
   - Providing APIs that Frontend needs
   - Proper error handling
   - Response formatting for UI

**See:** [GATEWAY_CONTRACT.md](GATEWAY_CONTRACT.md) for complete API specification.

---

## Next Steps

- ✅ Phase 2 Step 6: Gateway Core Complete - Routes to BFF only
- 🕐 Phase 2 Enhancement: Health Check endpoints (implemented, awaiting tech lead approval)
- 🔄 Phase 3: BFF Implementation (Sari)
- 🔄 Phase 4: Frontend Development
- 🔄 Phase 5: Authentication & Authorization (JWT tokens)
- 🔄 Phase 6: Rate limiting & Circuit breakers

### Phase 2 Current Status

**Core Gateway Functionality:** ✅ COMPLETE
- Routing to BFF service
- Error handling with unified error responses
- Docker integration
- Lombok for reducing boilerplate code
- Comprehensive documentation

**Pending Tech Lead Approval:**
- Health check endpoints (`/health`, `/health/ready` with BFF monitoring)
- Implementation is complete and tested, awaiting approval before production use
