# Employee Workspace — Gateway ↔ BFF API Contract

## 1. Purpose

This document defines the API contract between **lens-gateway** and **lens-bff**
for the **Employee → My Workspace** screen (see attached screenshot / Figma:
"Resonance Flow — Good morning, Alex").

It exists to answer one question precisely, per
[`lens-architecture-design.md`](./lens-architecture-design.md) §11
("Define clear contracts between Gateway, BFF, and downstream services"):

> Which HTTP endpoints does the Gateway need to expose (route) so the
> frontend can render this screen, and what does the BFF return?

**Ownership split** (per `lens-architecture-design.md` §9–10):

| Layer | Owner | Responsibility here |
|---|---|---|
| lens-gateway | Dvori | Add a **route** forwarding `/api/bff/**` to lens-bff, propagate the authenticated user context, no domain logic |
| lens-bff | (this doc's author) | Implement the **controllers**, aggregate Inbox/Chain/Mind, own the DTOs below |
| Inbox / Chain / Mind | respective teams | Domain data — currently skeleton-only, see §7 |

This is a companion to `docs/employee-ui-design.md` (UX reference) and is meant to
be reviewed with Dvori and the manager before implementation starts.

---

## 2. Request flow

```text
Browser
  │
  ▼
lens-frontend  (My Workspace page)
  │
  ▼
lens-gateway   /api/bff/v1/...            ← Dvori adds this route
  │  (auth: validates JWT, injects X-User-Id)
  ▼
lens-bff       /v1/...                    ← this doc's author implements these
  │
  ├──► Chain   (best-fit selection, blocker state)
  ├──► Inbox   (canonical task data, lifecycle)
  └──► Mind    (score/explanation — consumed indirectly via Chain, see §7)
```

Gateway route to add (mirrors the existing `bridge`/`mind`/`inbox`/`chain`
entries in `teams/lens/gateway/src/main/resources/application.yml`):

```yaml
- id: bff
  uri: http://bff:8082
  predicates:
    - Path=/api/bff/**
  filters:
    - StripPrefix=2
```

With this route, the frontend calls `POST /api/bff/v1/workspace/recommendation`,
Gateway strips `api/bff` and forwards to lens-bff as `POST /v1/workspace/recommendation`.
No new BFF-specific filter logic is required for routing itself — only the
generic auth filter described in §3.

---

## 3. Decision needed: how the authenticated user reaches the BFF

**Open question for Dvori — flag explicitly in the sync.**

The attached `WorkspaceRequestDTO` includes a `userId` field. If the frontend
sends `userId` as a plain request-body value, any employee could pass another
employee's id (IDOR risk). Since Gateway already owns authentication
(`lens-architecture-design.md` §4, §7), the recommended contract is:

- Gateway validates the JWT and injects an **`X-User-Id`** (and optionally
  `X-User-Role`) header on the proxied request.
- BFF **trusts the header, not the body field.**
- `WorkspaceRequestDTO.userId` stays in the class for now (matches the
  attached draft) but is treated as **ignored/optional** once the header
  exists, and should be removed once Gateway's JWT filter ships (currently
  listed as a "next step" in the gateway README, not yet implemented).

This only needs to be agreed on paper now; Gateway's JWT filter itself is
tracked separately (see the work-plan doc, Stage 2).

---

## 4. Endpoints

Base path at the BFF: `/v1`. All request/response bodies are JSON.

| # | Method & path | Purpose (UI element) | Request | Response |
|---|---|---|---|---|
| 1 | `POST /v1/workspace/recommendation` | "Find my best task" → Best-task-for-you card | `WorkspaceRequestDTO` | `TaskRecommendationDTO` |
| 2 | `GET /v1/tasks/{taskId}` | Task details panel | – | `TaskDetailDTO` |
| 3 | `POST /v1/tasks/{taskId}/start` | "Start task" button | – | `TaskDetailDTO` |
| 4 | `POST /v1/tasks/{taskId}/block` | "Can't do this now" → reason dropdown | `BlockTaskRequestDTO` | `TaskDetailDTO` |
| 5 | `GET /v1/tasks?filter=` | "My Tasks" nav screen (list) | query params | `TaskListResponseDTO` |
| 6 | `GET /v1/me` | Greeting / avatar / role ("Alex Morgan, Developer") | – | `UserProfileDTO` |

Endpoints 1–4 cover everything visible in the attached screenshot. Endpoint 5
covers the "My Tasks" nav item (not shown in the screenshot, but present in
the sidebar) — flagged as lower priority, see §8. Endpoint 6 is new, proposed
so the frontend never has to read auth/session internals directly, consistent
with `lens-architecture-design.md` §3 ("frontend should communicate through
the LENS API boundary").

### 4.1 `POST /v1/workspace/recommendation`

Request — `WorkspaceRequestDTO` (as attached, `userId` per §3):

```json
{
  "availableTime": 45,
  "projectFilter": null,
  "minPriority": null
}
```

Response — `TaskRecommendationDTO` (as attached):

```json
{
  "task": { "...": "TaskDTO, see §5" },
  "explanation": "Fits in your 45 minutes, High importance, Unblocks 2 tasks",
  "fitsInTime": true,
  "isHighPriority": true,
  "hasNoDependencies": true,
  "confidenceScore": 92,
  "alternatives": []
}
```

Maps to the checklist rows and the three badges (`High priority`,
`Fits in 45 min`, `Unblocks 2 tasks`) in the card.

### 4.2 `GET /v1/tasks/{taskId}`

Response — `TaskDetailDTO` (extends `TaskDTO`, see §5.2 for new fields):

```json
{
  "id": "task_review_api",
  "title": "Review API changes",
  "description": "Review the latest API changes and update integration tests accordingly.",
  "status": "READY",
  "priority": "HIGH",
  "effortEstimate": 35,
  "dueDate": "2026-08-30T17:00:00",
  "owner": "Alex Morgan",
  "ownerAvatarUrl": "https://.../alex-morgan.png",
  "project": "Platform Service",
  "dependencies": [],
  "blocksTaskIds": ["task_002", "task_003"],
  "constraints": [],
  "createdAt": "2026-05-10T09:15:00",
  "source": { "type": "GITHUB_PR", "label": "GitHub – PR #4821", "url": "https://github.com/..." },
  "sprint": { "name": "Sprint 24", "startDate": "2026-05-06", "endDate": "2026-05-20" }
}
```

### 4.3 `POST /v1/tasks/{taskId}/start`

No request body (actor = `X-User-Id`). Transitions `status` → `IN_PROGRESS`
via Inbox. Returns the updated `TaskDetailDTO`.

### 4.4 `POST /v1/tasks/{taskId}/block`

Request — new `BlockTaskRequestDTO`, one entry per dropdown option in the
screenshot:

```json
{
  "reason": "WAITING_FOR_MANAGER_APPROVAL",
  "note": null,
  "blockingTaskId": null
}
```

`reason` enum (`BlockReason`) — matches the six dropdown rows exactly:

| Value | Screenshot label |
|---|---|
| `WAITING_FOR_MANAGER_APPROVAL` | Waiting for manager approval |
| `WAITING_FOR_CUSTOMER` | Waiting for customer |
| `BLOCKED_BY_ANOTHER_TASK` | Blocked by another task |
| `WAITING_FOR_INFORMATION` | Waiting for information |
| `EXTERNAL_DEPENDENCY` | External dependency |
| `OTHER` | Other reason |

`note` is free text, required when `reason = OTHER`. `blockingTaskId` is set
when `reason = BLOCKED_BY_ANOTHER_TASK` (links to the blocking task). Returns
the updated `TaskDetailDTO` (`status` → `BLOCKED`, `constraints` populated).

### 4.5 `GET /v1/tasks?filter=`

`filter` ∈ `all | in_progress | blocked | waiting_on_me` (default `all`).
Response:

```json
{ "items": [ "TaskDTO, ..." ], "total": 12 }
```

### 4.6 `GET /v1/me`

```json
{ "userId": "emp_001", "name": "Alex Morgan", "role": "Developer", "avatarUrl": "https://..." }
```

Sourced from the token claims Gateway already validated — BFF may simply
echo the `X-User-*` headers rather than calling a downstream service.

---

## 5. DTOs

### 5.1 As attached (no structural change, see §3 for the `userId` caveat)

`WorkspaceRequestDTO`, `TaskRecommendationDTO` — used as-is.

### 5.2 `TaskDTO` — two fields proposed as additions

The attached `TaskDTO` covers everything **except** the "unblocks N tasks"
badge, which needs the *outgoing* edge (tasks that depend on this one), not
just `dependencies` (the *incoming* edge):

| New field | Type | Why |
|---|---|---|
| `blocksTaskIds` | `List<String>` | IDs of tasks waiting on this one; badge shows `blocksTaskIds.size()` ("Unblocks 2 tasks") |

### 5.3 `TaskDetailDTO` — new, extends `TaskDTO`

Only needed for the Task Details panel (endpoint 4.2), not the recommendation
card:

| Field | Type | Screenshot source |
|---|---|---|
| `ownerAvatarUrl` | `String` | Owner avatar image |
| `createdAt` | `LocalDateTime` | "Created — May 10, 9:15 AM" |
| `source` | `TaskSourceDTO { type, label, url }` | "Source — GitHub – PR #4821" |
| `sprint` | `SprintDTO { name, startDate, endDate }` | "Sprint 24 (May 6 – May 20)" |

`source` and `sprint` are provenance/scheduling metadata that likely come
from Bridge (ingestion) and a sprint-planning source respectively, not from
Inbox's core Task model as currently documented — **flag with Inbox/Bridge**
whether these are already tracked or need to be added upstream.

### 5.4 `BlockTaskRequestDTO`, `BlockReason` — new (§4.4)

### 5.5 `TaskListResponseDTO`, `UserProfileDTO` — new (§4.5, §4.6)

### 5.6 Error envelope — proposed, to align with Inbox's convention

```json
{ "status": 404, "error": "TASK_NOT_FOUND", "message": "...", "timestamp": "..." }
```

Inbox's `CLAUDE.md` already flags this shape as an open cross-team question.
Since Gateway is the edge, propose adopting it for BFF → Gateway → frontend
errors too, so the frontend has one error shape regardless of which
downstream call failed.

---

## 6. Package location note

The attached DTOs are namespaced `com.rnd.gateway.dto`. Per
`lens-architecture-design.md` §5 ("BFF: … Define and expose frontend-facing
DTOs") and §4 ("Gateway … should not contain domain logic"), and given
lens-gateway is a **reactive Spring Cloud Gateway** with declarative
YAML routes (no `@RestController`s, confirmed in `teams/lens/gateway/src`),
Gateway does not deserialize these bodies at all for a plain proxy route —
it only forwards bytes. Recommend the DTOs live in **`com.rnd.bff.dto`**;
Gateway needs no DTO classes for this contract, only the route in §2 (and,
later, the auth filter in §3).

---

## 7. Downstream dependency status (blocking risk)

Per direct inspection of the repo: **Inbox, Chain, and Mind are all
skeleton/foundation stage** — no concrete endpoints or DTOs exist yet for
task retrieval, best-fit selection, or status/blocker transitions. This
contract can be finalized and the BFF built against **mock clients** now,
but real end-to-end behavior is blocked until:

- **Chain** publishes a best-fit-selection contract (time window + user →
  task id, explanation, confidence) — feeds endpoint 4.1.
- **Inbox** publishes canonical Task read/write endpoints — feeds 4.1, 4.2,
  4.3, 4.5, and confirms whether `source`/`sprint`/`createdAt` are tracked.
- **Chain** publishes a blocker/constraint-update contract — feeds 4.4.

See the companion work-plan doc for how BFF work proceeds in parallel via
mocks.

---

## 8. Out of scope for this round

- **Notifications** (bell icon, badge count) — frontend's own header already
  marks this "not implemented yet"; owned by Relay per
  `docs/employee-ui-design.md` §9. No endpoint proposed here.
- **`GET /v1/tasks` (My Tasks list)** — included above for completeness
  since the nav item exists, but not required to ship the screenshot's
  Workspace screen; can follow in a second pass.
- **"Chat about it"** — present in `docs/employee-ui-design.md` §4B but
  **absent from the actual screenshot**, which instead shows "Can't do this
  now". Recommend confirming with the manager which is authoritative for
  this milestone before designing a chat endpoint.

---

## 9. Scope caveat inherited from `employee-ui-design.md`

That document's §11 explicitly notes the Employee UI was, at time of
writing, a *"product/UX reference and cross-team handoff example"* rather
than confirmed LENS Phase-2 scope (which was manager-workload-only). Today's
direct instruction to build this screen's BFF appears to supersede that
note — worth a one-line confirmation back to the manager for the record,
since it changes LENS's documented scope.
