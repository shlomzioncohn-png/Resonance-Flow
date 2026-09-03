# LENS Gateway - Service Integration Contract

**Version:** 2.2  
**Date:** 2026-08-26  
**Owner:** LENS Team (Dvori)

**Status:** Phase 2 Complete - Gateway Ready for BFF Integration (Health Check endpoints pending tech lead approval)

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

## 2. API Endpoints

The Gateway exposes three endpoints for the Frontend's workspace functionality. All requests are forwarded to the BFF service.

### Endpoint Routing Table

| Method | Request Path | BFF Forwards To | Handler | Notes |
|---|---|---|---|---|
| POST | `/api/workspace/recommend` | `http://bff-service:8080/workspace/recommend` | BFF (Sari) | Get task recommendation for available time |
| GET | `/api/workspace/tasks/{taskId}` | `http://bff-service:8080/workspace/tasks/{taskId}` | BFF (Sari) | Get full task details by ID |
| PATCH | `/api/workspace/tasks/{taskId}/status` | `http://bff-service:8080/workspace/tasks/{taskId}/status` | BFF (Sari) | Update task status |

**Current Implementation:** Manual routing via `WorkspaceController.java` — each endpoint explicitly forwards to BFF using `RestTemplate`.

**Future Phases:** Once more team services are ready, BFF will expand to route to Bridge, Chain, Inbox, and Mind services. Gateway's routing layer remains unchanged.

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

**Status:** 🕐 *Pending approval from tech lead - Implemented but awaiting confirmation before production use*

### GET /health
Returns **gateway health status AND BFF dependency status**.

**Response (BFF Available):**
```json
{
  "status": "UP",
  "service": "gateway",
  "version": "0.1.0",
  "timestamp": "2026-08-26T10:30:00Z",
  "dependencies": {
    "bff": "UP",
    "bff_url": "http://bff-service:8080/health"
  }
}
```

**Response (BFF Unavailable):**
```json
{
  "status": "UP",
  "service": "gateway",
  "version": "0.1.0",
  "timestamp": "2026-08-26T10:30:00Z",
  "dependencies": {
    "bff": "DOWN",
    "bff_url": "http://bff-service:8080/health"
  }
}
```

**Note:** Gateway status remains `UP` even if BFF is `DOWN`. Always check `dependencies.bff` field.

### GET /health/ready
Returns gateway readiness status, including BFF availability check.

**Response:**
```json
{
  "status": "UP",
  "service": "gateway",
  "ready": true,
  "timestamp": "2026-08-26T10:30:00Z",
  "dependencies": {
    "bff": "UP",
    "bff_ready": true
  }
}
```

**How it Works:**
1. Gateway checks its own availability (always UP if process is running)
2. Gateway makes HTTP call to `http://bff-service:8080/health`
3. If BFF responds with 200 OK, `dependencies.bff = "UP"`
4. If BFF is unreachable/errors, `dependencies.bff = "DOWN"`
5. Health check is non-blocking - failures are logged but don't affect gateway health

**For Consumers:**
- If `dependencies.bff = "UP"` → Proceed with API calls
- If `dependencies.bff = "DOWN"` → Expect `503 SERVICE_UNAVAILABLE` errors on `/api/**` requests

---

## 6. Configuration

Gateway configuration is managed via **environment variables** in `application.yml`.

### BFF Service Configuration

| Variable | Default | Purpose |
|---|---|---|
| `BFF_SERVICE_URL` | `http://bff-service:8080` | Base URL for BFF service |
| `BFF_CONNECT_TIMEOUT_MS` | `2000` | Connection timeout (milliseconds) |
| `BFF_READ_TIMEOUT_MS` | `10000` | Read timeout (milliseconds) |
| `BFF_ENDPOINT_WORKSPACE_RECOMMEND` | `/workspace/recommend` | BFF endpoint for task recommendations |
| `BFF_ENDPOINT_TASKS` | `/workspace/tasks` | BFF endpoint for task operations |

### Server Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | Gateway service port |
| `SPRING_PROFILES_ACTIVE` | `local` | Active Spring profile (local/prod/etc) |

### How Configuration Works

Example `application.yml`:
```yaml
bff:
  service:
    url: ${BFF_SERVICE_URL:http://bff-service:8080}
  connect-timeout-ms: ${BFF_CONNECT_TIMEOUT_MS:2000}
  read-timeout-ms: ${BFF_READ_TIMEOUT_MS:10000}
  endpoints:
    workspace-recommend: ${BFF_ENDPOINT_WORKSPACE_RECOMMEND:/workspace/recommend}
    tasks: ${BFF_ENDPOINT_TASKS:/workspace/tasks}
```

**Benefits:**
- No code changes needed to adjust BFF URL
- Supports different environments (local, staging, production)
- Easy to override without Docker rebuild

---

## 7. Implementation Details

### Current Architecture

The Gateway uses **manual routing** via Spring Boot `RestTemplate`:

1. **WorkspaceController** receives requests from Frontend
2. **Extracts user ID** from mock token (TODO: real JWT extraction)
3. **Builds headers** with X-User-Id
4. **Forwards to BFF** using `RestTemplate.postForObject()` / `exchange()`
5. **Returns response** to Frontend unchanged

### Request Flow

```
Frontend
  │
  │ POST /api/workspace/recommend
  │ Content-Type: application/json
  │ { "availableTime": 45 }
  │
  ▼
WorkspaceController (Gateway)
  ├─ Extract userId (currently mock)
  ├─ Build headers (X-User-Id: emp_001)
  ├─ Construct BFF URL from config
  │  (bff.service.url + bff.endpoints.workspace-recommend)
  ├─ Call: POST http://bff-service:8080/workspace/recommend
  │
  └─ Return response to Frontend (200 OK or error)
```

### Future Extensibility

When more services are ready (Bridge, Chain, Inbox, Mind), the BFF will handle routing to those services. The Gateway's routing logic remains unchanged — it only forwards to BFF.

---

## 8. Testing & Verification

### Manual Testing

```bash
# Test workspace endpoint
curl -X POST http://localhost:8080/api/workspace/recommend \
  -H "Content-Type: application/json" \
  -d '{"availableTime": 45}'

# Test get task details
curl http://localhost:8080/api/workspace/tasks/task_001

# Test unknown route (should get 404)
curl http://localhost:8080/api/unknown/endpoint
```

### Docker Compose Testing

```bash
cd teams/lens/gateway
docker-compose up --build
```

Verify Gateway is running:
```bash
curl http://localhost:8080/health
```

### Test Coverage

- ✅ Workspace recommendation endpoint
- ✅ Task details retrieval
- ✅ Task status updates
- ✅ Error handling for unknown routes (404)
- ✅ Timeout handling (504)
- ✅ Service unavailable handling (503)

---

## 9. Next Steps

1. ✅ Define Gateway Contract (this document)
2. ✅ Implement Routing with all downstream services
3. ✅ Add Error Handling middleware
4. ✅ Implement Health/Readiness endpoints (gateway-only)
5. ✅ Add Tests (health, routing, error handling)
6. ✅ Docker Compose integration with all 5 services
7. ✅ Documentation for consumers

**Phase 2 Step 6 Definition of Done: COMPLETE**

---

## 10. Consumer Guidelines (for other teams)

### Future Phase: When More Services are Ready

When Bridge, Chain, Inbox, or Mind teams are ready, they'll integrate through BFF — not directly with Gateway.

**Architecture remains:**
```
Frontend → Gateway → BFF → [Bridge/Chain/Inbox/Mind]
```

**BFF responsibilities (Sari's team):**
- Route requests to appropriate team service
- Orchestrate calls to multiple services
- Aggregate and transform responses
- Handle service failures

**Gateway responsibilities (LENS team):**
- Route Frontend requests to BFF (unchanged)
- Error handling (unchanged)
- Health checking (unchanged)
