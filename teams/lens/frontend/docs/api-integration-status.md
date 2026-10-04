# API integration status

Snapshot as of 2026-10-04. Replaces the 2026-09-01 snapshot, which described a BFF that
was still a skeleton — that's no longer true. Current focus: connecting the BFF to Inbox's
real Task API, starting with the My Tasks list and Task details screens, then the chat.

## How a request flows today

```
Frontend (services/*Api.ts) → Gateway :8080 (/api/**) → BFF :8082 → *Client classes (mocked)
```

Frontend → Gateway → BFF is real HTTP. The data itself is still mocked, but inside the BFF
(`com.rnd.bff.client.*`), not in the frontend. Nothing calls another team's service yet.

## What exists

- **Gateway** (`teams/lens/gateway`): `WorkspaceController`, `ManagerController`,
  `ProjectDetailsController` — each forwards to BFF. The user id is a hardcoded
  `MOCK_USER_ID = "emp_001"` (dev-mode only), sent to BFF as the `X-User-Id` header.
  JWT extraction is still a TODO.
- **BFF** (`teams/lens/bff`):
  - `GET /workspace/tasks` (filter, search, page, size)
  - `GET /workspace/tasks/{taskId}`
  - `PATCH /workspace/tasks/{taskId}/status`
  - `POST /workspace/recommend`, `GET /workspace/employee-task`
  - `GET /manager/workload`, `GET /manager/projects`, `GET /admin/projects[/{id}]`

  All backed by in-memory mocks. `InboxClient` has a `TODO` at each method marking the
  Inbox call that will replace it; `MindClient`, `ChainClient`, `EmployeeClient`,
  `ManagerClient` are mocked the same way.

- **Frontend**: the employee screens call the Gateway through `services/workspaceApi.ts`
  and `services/employeeApi.ts`. MSW (`mocks/handlers.ts`) now only mocks
  `GET /api/admin/projects/:projectId`; everything else passes through to the Gateway.
- **Chat** (`app/(app)/employee/tasks/[taskId]/ChatPanel.tsx`): a scripted demo with
  canned replies. No chat endpoint exists in the Gateway, BFF or Inbox yet.

## Inbox Task API (checked on branch `inbox-team`, commit `438c368`)

Not merged to `main` yet — Inbox expect that to happen soon. On `main`, Inbox only has
`/api/v1/users`.

| Method | Path                        | Notes                                                                                                        |
| ------ | --------------------------- | ------------------------------------------------------------------------------------------------------------ |
| GET    | `/api/v1/tasks`             | Paged. Filters: `search`, `status`, `available`, `source`, `tier`, `deadlineFrom`/`deadlineTo`, `noDeadline` |
| GET    | `/api/v1/tasks/{id}`        | `id` is a UUID                                                                                               |
| PATCH  | `/api/v1/tasks/{id}/status` | Body `{ status, snoozedUntil }`. `snoozedUntil` is required when status is `SNOOZED`                         |
| PATCH  | `/api/v1/tasks/{id}`        | Edit task details (not needed by our screens yet)                                                            |

**Identity:** every call needs an `X-Actor-Id` header holding the UUID of a user that
exists in Inbox's DB. Otherwise the call returns 401.

**Running locally (agreed with Inbox):** Inbox's DB is local to each machine. Its
`SeedGenerator` always produces the same rows and the same UUIDs, so loading the seed
gives us the same users and tasks Inbox has, and the test UUIDs they send us will work.

1. Check out `inbox-team`. The Task API and the seed aren't on `main` yet.
2. Start Postgres and Inbox from the **root** `docker-compose.yml` with
   `INBOX_SERVER_PORT=8083`. Inbox defaults to 8080 standalone or 8082 via the root
   compose, and both clash with our Gateway (8080) and BFF (8082). Starting Inbox runs
   Flyway, which creates the tables.
3. Get the seed CSV files from Inbox (they're git-ignored) and put them in
   `teams/inbox/seeds/`. Then, from that folder:
   `psql -h localhost -p 5432 -U postgres -d resonance_flow -f load-seeds.sql`
4. In the BFF, set `inbox.service.url=http://localhost:8083`. That property is already
   read by `InboxClient`, so no code change is needed. Its default is
   `http://inbox-service:8080`.

Note: in the seed, task ownership lives in the `assignments` table (task → user/team +
role), not in a column on `tasks`. That matters for "my tasks" (gap 2 below).

## Gaps to close before the BFF can call Inbox

1. **User id.** We send `emp_001` as `X-User-Id`. Inbox expects a real user UUID as
   `X-Actor-Id`. For local testing, Inbox will send a seeded user's UUID. It replaces
   `emp_001`, and the BFF has to forward it under Inbox's header name.
2. **"My tasks" scoping.** Inbox's list returns every task in the actor's _tenant_. There
   is no owner filter in `TaskFilterDto`. To show only the employee's own tasks, either
   Inbox adds an `ownerId` filter, or the BFF filters by `ownerId` after the call. That
   second option breaks paging, so it should be raised with Inbox.
3. **List shape.** Inbox returns `{ content, page, size, totalElements, totalPages }`.
   Our `TaskListResponseDTO` is `{ items, total, page, size }`. Map these in the BFF.
4. **Filters.** Our list takes a single `filter` string, and Inbox takes separate
   `status`/`available`/deadline params. The BFF must translate one into the other.
5. **Task fields.** Most of our `TaskDTO` fields already mirror Inbox's names. These need
   mapping or have no Inbox source:

   | Our `TaskDTO`                             | Inbox `TaskResponseDto`                                |
   | ----------------------------------------- | ------------------------------------------------------ |
   | `dueDate`                                 | `deadline` (`Instant`, ours is `LocalDateTime`)        |
   | `effortEstimate`                          | `effortEstimateMinutes`                                |
   | `owner` (display name)                    | `ownerId` (UUID only; name needs `/api/v1/users/{id}`) |
   | `project` (name)                          | `projectId` (UUID only)                                |
   | `tenantId`                                | `tenant` (object)                                      |
   | `createdBy`                               | `createdById`                                          |
   | `isAvailable`                             | `available`                                            |
   | `priority`, `dependencies`, `constraints` | not in Inbox. `dependencies` likely belongs to Chain   |

6. **Status update body.** Inbox needs `snoozedUntil` when snoozing. Our
   `TaskStatusUpdateDTO` doesn't carry it yet.

## Next steps

1. Run Inbox locally on 8083 with the seed loaded (see "Running locally" above). Use the
   test user UUID Inbox sends.
2. Replace the mock body of `InboxClient.getTasksForUser` with the real call, then fix the
   mapping and scoping issues above. This connects the **My Tasks** screen.
3. Do the same for `getTaskById` and `updateStatus`. This connects the **Task details**
   screen.
4. Handle Inbox errors (401/403/404/unavailable) so the screens show a clear message.
5. Agree with Inbox on a chat API (they mediate with Mind), then add it to the BFF and
   Gateway and wire `ChatPanel`.
