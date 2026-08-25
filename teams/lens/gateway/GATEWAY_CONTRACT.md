# LENS Gateway - Service Integration Contract

**Version:** 1.0  
**Date:** 2026-08-24  
**Owner:** LENS Team (Dvori)

---

## 1. Gateway Role & Responsibilities

The LENS Gateway is the **single entry point** for all external requests to Resonance Flow services. It acts as a reverse proxy with the following responsibilities:

| Responsibility | Scope |
|---|---|
| **Routing** | Dispatch requests to appropriate downstream services based on URL path |
| **Protocol Translation** | Handle HTTP/HTTPS, WebSocket, gRPC (future) |
| **Configuration** | Manage service URLs via environment variables (no hardcoding) |
| **Error Handling** | Provide unified error responses and proper HTTP status codes |
| **Health Checking** | Monitor **gateway status only** (each service monitors itself) |
| **Observability** | Log requests, trace routing decisions, measure latencies |

### Gateway Responsibilities
- ✅ Route `/api/{service}/**` to downstream services
- ✅ Strip `/api/{service}` prefix before forwarding
- ✅ Handle unknown routes (404)
- ✅ Handle service unavailable (503)
- ✅ Report gateway health (/health endpoint)
- ✅ Log and observe routing decisions

### NOT Gateway Responsibilities
- ❌ Monitor downstream service health (each service is responsible for its own /health)
- ❌ Aggregate health status from multiple services
- ❌ Authentication/Authorization (future separate service)
- ❌ Request transformation/mutation (future BFF service)
- ❌ Rate limiting (future phases)

---

## 2. API Path Structure

All requests follow this pattern:

```
GET/POST/PUT/DELETE  http://gateway:8080/api/{service}/{resource}
                                           ^^   ^^^^^^  ^^^^^^^^
                                        version service resource
```

### Routing Table

| Service | Internal Name | Downstream URL | Example Path | Forwards To |
|---|---|---|---|---|
| Bridge | bridge | http://bridge:8080 | `/api/bridge/sources` | `http://bridge:8080/sources` |
| Mind | mind | http://mind:8080 | `/api/mind/extract` | `http://mind:8080/extract` |
| Inbox | inbox | http://inbox:8080 | `/api/inbox/tasks` | `http://inbox:8080/tasks` |
| Chain | chain | http://chain:8080 | `/api/chain/deps` | `http://chain:8080/deps` |

**Path Stripping:** The `/api/{service}` prefix is stripped before forwarding. Gateway removes 2 path segments.

---

## 3. Request/Response Contract

### Request Flow

```
Client Request
    ↓
[Gateway Receives] GET /api/bridge/sources?limit=10
    ↓
[Routing] → Matches /api/bridge/** → route to Bridge service
    ↓
[Strip Prefix] → Remove /api/bridge (2 segments)
    ↓
[Forward] → GET http://bridge:8080/sources?limit=10
    ↓
Bridge Service Response
    ↓
[Return to Client] with original HTTP status
```

### Success Response (2xx)
```json
{
  "status": 200,
  "data": { ... },
  "timestamp": "2026-08-24T10:30:00Z"
}
```

### Error Response (4xx, 5xx)
```json
{
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "Invalid request format",
  "path": "/api/bridge/sources",
  "timestamp": "2026-08-24T10:30:00Z"
}
```

---

## 4. Error Contract

| Scenario | HTTP Status | Error Code | Message |
|---|---|---|---|
| Unknown route | 404 | `NOT_FOUND` | "Route not found" |
| Service unavailable | 503 | `SERVICE_UNAVAILABLE` | "Downstream service unavailable" |
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

## 7. Service Integration Example

### Bridge Team Integration
1. Ensure Bridge service is running on `http://bridge:8080`
2. Test Gateway: `curl http://gateway:8080/api/bridge/health`
3. Use any Bridge API path after `/api/bridge/`
4. Gateway will forward with `/api/bridge` stripped

### Example Request Flow
```bash
# Client calls Gateway
curl -X GET http://gateway:8080/api/bridge/sources

# Gateway routes to Bridge
GET http://bridge:8080/sources

# Bridge responds
{ "sources": [...] }

# Gateway returns to client (unchanged)
{ "sources": [...] }
```

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
