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
- Docker / Docker Compose

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

- ✅ Phase 2 Step 6: Gateway Complete - Routes to BFF only
- 🔄 Phase 3: BFF Implementation (Sari)
- 🔄 Phase 4: Frontend Development
- 🔄 Phase 5: Authentication & Authorization (JWT tokens)
- 🔄 Phase 6: Rate limiting & Circuit breakers
