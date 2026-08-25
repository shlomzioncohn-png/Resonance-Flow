# Team LENS - API Gateway Service

**Phase 2 Step 6: COMPLETE ✅**  
**Status:** Gateway ready for team integration  
**Last Updated:** 2026-08-25

API Gateway service responsible for routing and authentication for the Resonance Flow system.

## What This Service Does

This service acts as the single entry point for all Resonance Flow requests:
- Route requests from `/api/{service}/**` to appropriate downstream services
- Error handling and status responses (404, 503, 504, etc)
- Gateway health monitoring (/health, /health/ready endpoints)
- CORS handling for client applications

## What This Service Does NOT Do

❌ Monitor downstream service health  
❌ Implement service-specific logic  
❌ Create HealthControllers for other teams  
❌ Aggregate service status

**Each team implements their own /health endpoint.**

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

The gateway automatically routes requests to downstream services:

| Route | Service | Path |
|-------|---------|------|
| `/api/bridge/**` | Bridge | ETL & Source Adapters |
| `/api/mind/**` | Mind | Extraction & Scoring |
| `/api/inbox/**` | Inbox | Task Model & Lifecycle |
| `/api/chain/**` | Chain | Dependencie/api

To just build the jar:

```bash
mvn package -DskipTests
```

## Running with Docker

Build and run with Docker Compose:

```bash
docker-compose up --build
```

The service will be available at http://localhost:8080/api

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
        - id: bridge
          uri: http://bridge:8080
          predicates:
            - Path=/api/bridge/**
          filters:
            - StripPrefix=2
```

## Integration Requirements for Other Teams

Each team must implement their service to work with the Gateway:

### 1. Health Endpoint (Required)
```bash
GET /health
HTTP 200 OK
{
  "status": "UP",
  "service": "{your-service-name}",
  "version": "0.1.0",
  "timestamp": "2026-08-25T..."
}
```

### 2. Readiness Endpoint (Required)
```bash
GET /health/ready
HTTP 200 OK
{
  "status": "UP",
  "service": "{your-service-name}",
  "ready": true,
  "timestamp": "2026-08-25T..."
}
```

### 3. Service Configuration
- Listen on port 8080
- Respond to requests at `/api/{your-service}/**`
- Return proper HTTP status codes
- Document your service contract

### 4. Testing via Gateway
```bash
# Your service should be accessible through Gateway
curl http://localhost:8080/api/{your-service}/health

# Example for Bridge team
curl http://localhost:8080/api/bridge/health

# Example for Mind team  
curl http://localhost:8080/api/mind/extract
```

**See:** [GATEWAY_CONTRACT.md](GATEWAY_CONTRACT.md) for complete integration details.

---

## Next Steps

- ✅ Phase 2 Step 6: Docker Integration & Scope Clarification - COMPLETE
- 🔄 Phase 3: Authentication & Authorization (JWT tokens)
- 🔄 Phase 4: Frontend BFF (Backend for Frontend)
- 🔄 Phase 5: Rate limiting & Circuit breakers
- 🔄 Phase 6: API Documentation (OpenAPI/Swagger)
