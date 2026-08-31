# My Workspace Screen — Integration Plan

**Version:** 1.0
**Date:** 2026-08-30
**Owner:** LENS Team

---

## 1. Flow Overview

```
Frontend
   |
   | POST /api/workspace/recommend
   | Body: { availableTime: 45 }
   v
Gateway
   |
   | POST /workspace/recommend
   | Header: X-User-Id: emp_001   ← mock userId (JWT extraction planned)
   | Body: { availableTime: 45 }
   v
BFF
   |
   +--→ Mind   (task scores)
   +--→ Chain  (feasibility, dependencies)
   +--→ Inbox  (task data)
   |
   v
TaskRecommendationDTO
   |
   v
Gateway → Frontend
```

---

## 2. API Contract — Gateway ↔ BFF

### Endpoint

```
POST /workspace/recommend
```

BFF listens on this path directly (Gateway strips the `/api` prefix before forwarding).

### Request

**Headers (set by Gateway, not by Frontend):**

| Header | Value | Notes |
|---|---|---|
| `X-User-Id` | `emp_001` | Mock value today. Will be extracted from JWT when Auth & Identity is implemented. |
| `Content-Type` | `application/json` | Standard |

**Body:**

```json
{
  "availableTime": 45,
  "projectFilter": "Platform Service",
  "minPriority": "MEDIUM"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `availableTime` | Integer | Yes | Time available in minutes. Min value: 1. |
| `projectFilter` | String | No | Filter by project name. Null = any project. |
| `minPriority` | String | No | Minimum priority: `LOW` / `MEDIUM` / `HIGH`. Null = any priority. |

### Response 200

```json
{
  "task": {
    "id": "task_001",
    "title": "Review API changes",
    "description": "Review the latest API changes and update integration tests.",
    "status": "READY",
    "priority": "HIGH",
    "effortEstimate": 35,
    "dueDate": "2026-08-30T17:00:00",
    "owner": "Alex Morgan",
    "project": "Platform Service",
    "dependencies": [],
    "constraints": []
  },
  "explanation": "Fits in your 45 minutes, high importance, no blocking dependencies",
  "fitsInTime": true,
  "isHighPriority": true,
  "hasNoDependencies": true,
  "confidenceScore": 85,
  "alternatives": []
}
```

### TaskDTO Fields

| Field | Type | Values |
|---|---|---|
| `id` | String | Unique task ID |
| `title` | String | Task title |
| `description` | String | Full description |
| `status` | String | `READY` / `IN_PROGRESS` / `BLOCKED` / `COMPLETED` |
| `priority` | String | `LOW` / `MEDIUM` / `HIGH` |
| `effortEstimate` | Integer | Minutes |
| `dueDate` | LocalDateTime | ISO-8601 |
| `owner` | String | Assigned person name |
| `project` | String | Project / team name |
| `dependencies` | List\<String\> | List of task IDs this task depends on |
| `constraints` | List\<String\> | Blockers, e.g. `approval_needed` |

### Error Responses

| Status | errorCode | When |
|---|---|---|
| 400 | `BAD_REQUEST` | `availableTime` missing or less than 1 |
| 503 | `SERVICE_UNAVAILABLE` | BFF not reachable |
| 504 | `GATEWAY_TIMEOUT` | BFF timeout |
| 500 | `INTERNAL_ERROR` | Unexpected error |

Error format:
```json
{
  "errorCode": "BAD_REQUEST",
  "message": "Invalid request parameters",
  "path": "/api/workspace/recommend",
  "timestamp": "2026-08-30T15:00:00",
  "details": "availableTime: availableTime is required"
}
```

---

## 3. API Design Principles

1. **Identity via header, not body** — `userId` is never sent by the Frontend. Gateway extracts it from the JWT and injects it as `X-User-Id`. This prevents a user from requesting data for a different user (IDOR).

2. **Each layer owns its DTOs** — Gateway has `com.rnd.gateway.dto`, BFF will have `com.rnd.bff.dto`. Same structure, separate packages. No shared library between modules.

3. **Gateway is a transparent proxy** — It does not transform, aggregate, or add business logic. It routes and forwards.

4. **BFF owns the response shape** — The Frontend-facing data model (`TaskRecommendationDTO`, `TaskDTO`) is defined by BFF. Gateway passes it through unchanged.

5. **Downstream services are mocked until ready** — BFF works against mock clients for Mind, Chain, and Inbox. The contract is defined; implementations follow.

6. **Errors are uniform** — All errors from Gateway follow the same JSON format with `errorCode`, `message`, `path`, `timestamp`, `details`.

---

## 4. Work Plan by Layer

### Gateway — Done

| Task | Status |
|---|---|
| `WorkspaceRequestDTO` (availableTime, projectFilter, minPriority) | ✅ |
| `TaskDTO` (all fields) | ✅ |
| `TaskRecommendationDTO` (task + explanation + flags + score + alternatives) | ✅ |
| `POST /api/workspace/recommend` controller | ✅ |
| Forward `X-User-Id` header to BFF | ✅ |
| Mock userId `emp_001` with `TODO` comment for JWT extraction | ✅ |
| Validation: `availableTime` required, min 1 | ✅ |
| Global error handling (400 / 401 / 403 / 503 / 504 / 500) | ✅ |
| Routing: `/api/**` → BFF with `StripPrefix=1` | ✅ |
| **JWT extraction (Auth & Identity)** | 🔜 Next phase |

---

### BFF

| Task | Status |
|---|---|
| Create `com.rnd.bff.dto.WorkspaceRequestDTO` | ⬜ |
| Create `com.rnd.bff.dto.TaskDTO` | ⬜ |
| Create `com.rnd.bff.dto.TaskRecommendationDTO` | ⬜ |
| `POST /workspace/recommend` controller | ⬜ |
| Read `X-User-Id` from header | ⬜ |
| Mock service: return hardcoded `TaskRecommendationDTO` | ⬜ |
| Wire to Mind client (when ready) | 🔜 |
| Wire to Chain client (when ready) | 🔜 |
| Wire to Inbox client (when ready) | 🔜 |

**BFF can start against mock data immediately** — no dependency on Mind, Chain, or Inbox.

---

### Frontend

| Task | Status |
|---|---|
| Time input widget (minutes) | ⬜ |
| "Find my best task" button → `POST /api/workspace/recommend` | ⬜ |
| Render recommended task card (title, project, priority, effort) | ⬜ |
| Render context flags (Fits in time / High priority / No blockers) | ⬜ |
| Start task action | ⬜ |
| Chat about it action | ⬜ |
| View full details action | ⬜ |

**Frontend can start once BFF returns a mock response.**

---

### Downstream (Mind / Chain / Inbox)

No dependency on these for the initial flow. BFF will call them once they expose their endpoints. Contract alignment to be done separately per service.

---

## 5. Identity — Current vs Future

| | Now (Mock) | Future (JWT) |
|---|---|---|
| Frontend sends | `{ "availableTime": 45 }` | Same — no change |
| Gateway reads userId from | Hardcoded `emp_001` | JWT `sub` claim from `Authorization` header |
| Gateway sends to BFF | `X-User-Id: emp_001` | `X-User-Id: <real userId>` |
| BFF reads userId from | `X-User-Id` header | Same — no change |

**The BFF and Frontend contracts do not change when JWT is implemented. Only Gateway changes internally.**
