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
| **Health Checking** | Monitor gateway status and downstream service availability |
| **Observability** | Log requests, trace routing decisions, measure latencies |

### Out of Scope (Phase 2+)
- Authentication/Authorization (will be added later)
- Rate limiting (reserved for future phases)
- Request transformation/mutation (future filters)
- Circuit breaker logic (future resilience patterns)

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
Returns gateway health status (always available)
```json
{
  "status": "UP",
  "components": {
    "gateway": { "status": "UP" },
    "bridge": { "status": "UP" },
    "mind": { "status": "UP" },
    "inbox": { "status": "UP" },
    "chain": { "status": "UP" }
  }
}
```

### GET /health/ready
Returns readiness status (all critical dependencies up)
```json
{
  "ready": true,
  "message": "Gateway is ready to accept requests"
}
```

---

## 6. Configuration

Services are configured via **environment variables** or **application.yml**, never hardcoded.

```yaml
# application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: bridge
          uri: ${BRIDGE_URL:http://bridge:8080}
          predicates:
            - Path=/api/bridge/**
          filters:
            - StripPrefix=2
```

**Environment Variables (for Docker):**
```
BRIDGE_URL=http://bridge:8080
MIND_URL=http://mind:8080
INBOX_URL=http://inbox:8080
CHAIN_URL=http://chain:8080
```

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

## 9. Next Steps

1. ✅ Define Gateway Contract (this document)
2. ⚙️ Implement Routing with real downstream services
3. ⚙️ Add Error Handling middleware
4. ⚙️ Implement Health/Readiness endpoints
5. ⚙️ Add Tests (routing, error handling, configuration)
6. ⚙️ Update docker-compose.yml with Bridge integration
7. ⚙️ Document consumer guidelines

---

## 10. Consumer Guidelines (for other teams)

### How to integrate with Gateway

1. **Ensure your service runs on a known URL** (e.g., `http://your-service:8080`)
2. **Notify LENS team** of your service URL
3. **LENS adds route** to Gateway configuration
4. **Clients use** `/api/{your-service}/your-endpoints`
5. **Your service** receives requests without `/api/{your-service}` prefix

### What Gateway guarantees
- ✅ Requests forwarded to your service
- ✅ Original HTTP method preserved
- ✅ Query parameters forwarded
- ✅ Request body forwarded
- ✅ Response status codes preserved
- ✅ Response body forwarded unchanged

### What Gateway does NOT do (yet)
- ❌ No request/response transformation
- ❌ No authentication enforcement
- ❌ No rate limiting
- ❌ No caching
