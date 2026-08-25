# LENS Gateway - Service Integration Contract

**Version:** 2.0  
**Date:** 2026-08-25  
**Owner:** LENS Team (Dvori)

**Status:** Phase 2 Step 6 Complete - Gateway Ready for BFF Integration

---

## Overview

The LENS Gateway is a **transparent routing layer** that dispatches client requests from the **Frontend** to the **BFF (Backend for Frontend)** by Sari.

**Important:** 
- Gateway routes ALL requests from Frontend to BFF only
- BFF is responsible for communicating with Bridge, Chain, Inbox, and Mind teams
- Each team implements their own service independently

---

## 1. Gateway Role & Responsibilities

The LENS Gateway is the **single entry point** for external client (Frontend) requests. It acts as a reverse proxy with the following responsibilities:

| Responsibility | Scope |
|---|---|
| **Routing** | Dispatch Frontend requests to BFF |
| **Protocol Translation** | Handle HTTP/HTTPS, WebSocket, gRPC (future) |
| **Configuration** | Manage BFF URL via environment variables |
| **Error Handling** | Provide unified error responses and proper HTTP status codes |
| **Health Checking** | Monitor **gateway status only** (BFF monitors itself) |
| **Observability** | Log requests, trace routing decisions, measure latencies |

### Gateway Responsibilities
- ✅ Route `/api/**` to BFF service
- ✅ Strip `/api` prefix before forwarding
- ✅ Handle unknown routes (404)
- ✅ Handle service unavailable (503)
- ✅ Report gateway health (/health endpoint)
- ✅ Log and observe routing decisions

### NOT Gateway Responsibilities
- ❌ Monitor BFF or team services health (each monitors itself)
- ❌ Aggregate health status from multiple services
- ❌ Call Bridge, Chain, Inbox, or Mind services directly (that's BFF's job)
- ❌ Authentication/Authorization (future separate service)
- ❌ Rate limiting (future phases)

---

## 2. API Path Structure

All requests follow this pattern:

```
GET/POST/PUT/DELETE  http://gateway:8080/api/{resource}
                                           ^^  ^^^^^^^^
                                        version resource
                                        
                              ↓ Routes to ↓
                              
                     http://bff:8080/{resource}
```

### Routing Table

| Request Path | Gateway Forwards To | Who Handles Next | Notes |
|---|---|---|---|
| `/api/**` | `http://bff-service:8080/**` | BFF (Sari) | All external requests go to BFF |

**Path Stripping:** The `/api` prefix is stripped before forwarding. Gateway removes 1 path segment.

---

## 3. Request/Response Contract

### Request Flow

```
Frontend Request
    ↓
[Gateway Receives] GET /api/dashboard
    ↓
[Routing] → Matches /api/** → route to BFF
    ↓
[Strip Prefix] → Remove /api (1 segment)
    ↓
[Forward] → GET http://bff-service:8080/dashboard
    ↓
BFF Processes:
  - Calls Bridge, Chain, Inbox, Mind as needed
  - Aggregates responses
  - Returns combined data
    ↓
[Return to Frontend] with original HTTP status
```

### Success Response (2xx)
```json
{
  "status": 200,
  "data": { ... },
  "timestamp": "2026-08-25T10:30:00Z"
}
```

### Error Response (4xx, 5xx)
```json
{
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "Invalid request format",
  "path": "/api/dashboard",
  "timestamp": "2026-08-25T10:30:00Z"
}
```

---

## 4. Error Contract

| Scenario | HTTP Status | Error Code | Message |
|---|---|---|---|
| Unknown route | 404 | `NOT_FOUND` | "Route not found" |
| BFF unavailable | 503 | `SERVICE_UNAVAILABLE` | "Backend service unavailable" |
| Request timeout | 504 | `GATEWAY_TIMEOUT` | "Request timeout" |
| Invalid request | 400 | `BAD_REQUEST` | "Invalid request format" |
| Authentication required | 401 | `UNAUTHORIZED` | "Authentication required" |
| Insufficient permissions | 403 | `FORBIDDEN` | "Insufficient permissions" |
| Internal error | 500 | `INTERNAL_ERROR` | "Internal server error" |

**All errors must include:**
- `status` (HTTP status code)
- `error` (error code)
- `message` (human-readable message)
- `path` (original request path)
- `timestamp` (ISO 8601)

---

## 5. Health & Readiness Endpoints

### GET /health
Returns **gateway health status only** (not downstream services).

Each downstream service has its own `/health` endpoint:
- `GET http://bridge:8080/health`
- `GET http://mind:8080/health`
- `GET http://inbox:8080/health`
- `GET http://chain:8080/health`

**Gateway /health response:**
```json
{
  "status": "UP",
  "service": "gateway",
  "version": "0.1.0",
  "timestamp": "2026-08-25T10:30:00Z"
}
```

### GET /health/ready
Returns gateway readiness status.

```json
{
  "status": "UP",
  "service": "gateway",
  "ready": true,
  "timestamp": "2026-08-25T10:30:00Z"
}
```

**Note:** Downstream services have their own `/health/ready` endpoints. Monitor them independently.

---

## 6. Configuration

Services are configured via **environment variables** or **application.yml**, never hardcoded.

Gateway routes are defined in `application.yml` with downstream service URLs:

```yaml
# application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: bridge
          uri: http://bridge-service:8080
          predicates:
            - Path=/api/bridge/**
          filters:
            - StripPrefix=2
        - id: mind
          uri: http://mind-service:8080
          predicates:
            - Path=/api/mind/**
          filters:
            - StripPrefix=2
        - id: inbox
          uri: http://inbox-service:8080
          predicates:
            - Path=/api/inbox/**
          filters:
            - StripPrefix=2
        - id: chain
          uri: http://chain-service:8080
          predicates:
            - Path=/api/chain/**
          filters:
            - StripPrefix=2
```

**For Docker Compose:**
- Use service container names (e.g., `http://bridge-service:8080`)
- Gateway automatically resolves via Docker's internal DNS

---

## 7. Service Integration Requirements for Each Team

### Your Team's Responsibility

1. **Build your service** on port 8080
2. **Implement /health endpoint**
   ```json
   GET /health
   Response:
   {
     "status": "UP",
     "service": "{your-service-name}",
     "version": "0.1.0",
     "timestamp": "2026-08-25T..."
   }
   ```

3. **Implement /health/ready endpoint** (same format as /health)

4. **Register with LENS team**
   - Provide your service name (e.g., "bridge")
   - LENS will add a route to Gateway

5. **Test via Gateway**
   ```bash
   curl http://localhost:8080/api/{your-service}/health
   ```

### Request Flow Through Gateway

```
Client Request
  ↓
[Gateway Receives] GET /api/bridge/sources?limit=10
  ↓
[Routing Match] /api/bridge/** matches "bridge" route
  ↓
[Prefix Strip] Removes /api/bridge (2 path segments)
  ↓
[Forward] GET http://bridge-service:8080/sources?limit=10
  ↓
[Your Service Processes] Returns response with your status code
  ↓
[Gateway Returns] Response to client unchanged
```

### Example: Bridge Team Integration
1. Build Bridge service to listen on port 8080
2. Implement `/health` and `/health/ready` endpoints
3. Notify LENS team
4. LENS adds route to Gateway
5. You're done! Gateway handles routing

---

## 8. Testing & Verification

### Manual Testing
```bash
# Test routing
curl http://localhost:8080/api/bridge/health

# Test unknown route
curl http://localhost:8080/api/unknown

# Test error handling
curl -X POST http://localhost:8080/api/bridge/invalid
```

### Docker Compose Testing
```bash
docker-compose up --build
# Verify all services started
docker-compose logs
```

---

## 9. Testing

### Unit Tests
- `GatewayHealthTests` - Verify /health and /health/ready endpoints return correct format
- `GatewayRoutingTests` - Verify routing paths are configured for all services
- `GatewayErrorHandlingTests` - Verify error handling for unknown routes and malformed requests

### Running Tests
```bash
cd teams/lens/gateway
mvn test
```

### Test Coverage
- Health endpoints (2xx responses)
- Routing path configuration (4xx/5xx responses)
- Error handling (400, 404, 503, 504)
- Query parameter forwarding

---

## 10. Next Steps

## 10. Next Steps

1. ✅ Define Gateway Contract (this document)
2. ✅ Implement Routing with all downstream services
3. ✅ Add Error Handling middleware
4. ✅ Implement Health/Readiness endpoints (gateway-only)
5. ✅ Add Tests (health, routing, error handling)
6. ✅ Docker Compose integration with all 5 services
7. ✅ Documentation for consumers

**Phase 2 Step 6 Definition of Done: COMPLETE**

---

## 11. Consumer Guidelines (for other teams)

### How to integrate with Gateway

1. **Ensure your service runs on a known URL** (e.g., `http://your-service:8080`)
2. **Notify LENS team** of your service URL
3. **LENS adds route** to Gateway configuration
4. **Clients use** `/api/{your-service}/your-endpoints`
5. **Your service** receives requests without `/api/{your-service}` prefix

### What Gateway guarantees
- ✅ Requests forwarded to your service with original method preserved
- ✅ Query parameters and request body forwarded unchanged
- ✅ Response status codes preserved (your service's status code returned to client)
- ✅ Response body forwarded unchanged (no transformation)
- ✅ Your service receives requests without `/api/{service}` prefix

### What Gateway does NOT do
- ❌ Monitor your service health (you maintain your own /health endpoint)
- ❌ Transform requests/responses (pure routing only)
- ❌ Handle service-specific authentication (future BFF service)
- ❌ Aggregate data from multiple services (each service is independent)

### Architectural Principle
**Each service is autonomous:** 
- Your team owns your service, including its health status
- Gateway is a transparent routing layer
- Teams communicate directly (not through Gateway)
- Each team monitors its own service health
- ❌ No request/response transformation
- ❌ No authentication enforcement
- ❌ No rate limiting
- ❌ No caching
