# API integration status

Snapshot as of 2026-10-04. Current focus: connecting the BFF to Inbox's real Task API.
The **My Tasks** list and **Task details** screens now work end to end against a local
Inbox. The chat comes next.

## How a request flows today

```
Frontend (services/*Api.ts) → Gateway :8080 (/api/**) → BFF :8082 → InboxClient → Inbox :8083
                                                                    → other *Clients (mocked)
```

`InboxClient` has two modes, switched by `INBOX_MOCK_ENABLED`:

- `true` (default): in-memory mock tasks, no Inbox needed. The team keeps working without
  running Inbox.
- `false`: real calls to Inbox's Task API. See "Running locally" below.

Every other client (`MindClient`, `ChainClient`, `EmployeeClient`, `ManagerClient`) is
still mocked inside the BFF.

## What exists

- **Gateway** (`teams/lens/gateway`): `WorkspaceController`, `ManagerController`,
  `ProjectDetailsController` — each forwards to BFF. The user id is a hardcoded
  `MOCK_USER_ID = "emp_001"` (dev-mode only), sent to BFF as the `X-User-Id` header.
  JWT extraction is still a TODO.
- **BFF** (`teams/lens/bff`):
  - `GET /workspace/tasks` (filter, search, page, size) — real Inbox in real mode
  - `GET /workspace/tasks/{taskId}` — real Inbox in real mode
  - `PATCH /workspace/tasks/{taskId}/status` — real Inbox in real mode
  - `POST /workspace/recommend` — uses Inbox's first page (100 tasks) as candidates;
    Chain and Mind still mocked
  - `GET /workspace/employee-task`, `GET /manager/workload`, `GET /manager/projects`,
    `GET /admin/projects[/{id}]` — mocked
- **Frontend**: the employee screens call the Gateway through `services/workspaceApi.ts`
  and `services/employeeApi.ts`. MSW (`mocks/handlers.ts`) now only mocks
  `GET /api/admin/projects/:projectId`; everything else passes through to the Gateway.
- **Chat** (`app/(app)/employee/tasks/[taskId]/ChatPanel.tsx`): a scripted demo with
  canned replies. No chat endpoint exists in the Gateway, BFF or Inbox yet.

## Inbox Task API (branch `inbox-team`, commit `438c368`)

Not merged to `main` yet — Inbox expect that to happen soon. On `main`, Inbox only has
`/api/v1/users`.

| Method | Path                        | Notes                                                                                                        |
| ------ | --------------------------- | ------------------------------------------------------------------------------------------------------------ |
| GET    | `/api/v1/tasks`             | Paged. Filters: `search`, `status`, `available`, `source`, `tier`, `deadlineFrom`/`deadlineTo`, `noDeadline` |
| GET    | `/api/v1/tasks/{id}`        | `id` is a UUID                                                                                               |
| PATCH  | `/api/v1/tasks/{id}/status` | Body `{ status, snoozedUntil }`. `snoozedUntil` is required when status is `SNOOZED`                         |
| PATCH  | `/api/v1/tasks/{id}`        | Edit task details (not needed by our screens yet)                                                            |

- **Identity:** every call needs an `X-Actor-Id` header holding the UUID of a user that
  exists in Inbox's DB. Without it, Inbox returns 401.
- **JSON:** field names are snake_case (`project_id`, `total_elements`), and enums are
  UPPERCASE (`IN_PROGRESS`, `MEETING`). `InboxTaskResponse` and `InboxPageResponse` in
  the BFF mirror this shape.

## Running locally (agreed with Inbox)

Inbox's DB is local to each machine. Its `SeedGenerator` always produces the same rows
and the same UUIDs. Loading the seed gives us the same users and tasks Inbox has, so the
test UUID they send works for us too.

1. Check out `inbox-team` into a separate folder, so your own branch stays untouched:
   `git fetch origin inbox-team && git worktree add --detach ../Resonance-Flow-inbox FETCH_HEAD`
2. From that folder's root, start Postgres and Inbox on 8083. 8080 and 8082 are our
   Gateway and BFF. Starting Inbox runs Flyway, which creates the tables:
   `INBOX_SERVER_PORT=8083 docker compose -p inbox-local up -d --build postgres inbox`
3. Get the seed CSV files from Inbox (they're git-ignored) and put them in
   `teams/inbox/seeds/`. Load them through the Postgres container (no local `psql`
   needed):
   ```bash
   cd teams/inbox/seeds
   docker exec resonance-postgres mkdir -p /tmp/seeds
   for f in *.csv load-seeds.sql; do docker cp "$f" resonance-postgres:/tmp/seeds/; done
   docker exec -w /tmp/seeds resonance-postgres sh -c \
     "sed -i 's/,member,/,employee,/' users.csv && psql -U postgres -d resonance_flow -f load-seeds.sql"
   ```
   The `sed` is only needed for CSVs generated before Inbox PR #530 (like the ones we got
   on 2026-10-04). Those still use the old role name `member`, and the DB enum (migration
   V9) only accepts `employee`. Newer CSVs are already fixed, so the `sed` does nothing.
4. Run the BFF with:
   `INBOX_MOCK_ENABLED=false`, `INBOX_SERVICE_URL=http://localhost:8083`,
   `INBOX_DEV_ACTOR_ID=ef071a8a-1040-3a27-904a-f6352bad4fea`. That test user ("User 8",
   tenant "Medium Org 18") has the most assigned tasks. From inside Docker, use
   `http://host.docker.internal:8083` instead. If the Gateway runs in Docker, the BFF
   container must be on the Gateway's network with the alias `bff-service`.

Checked end to end through the Gateway on 2026-10-04: the list (828 tasks, status filter,
search, paging), a single task, a status change (saved in Inbox), and an unknown id (404).

## Gaps — status

| #   | Gap                                                                                           | Status                                                                                                                                                                                   |
| --- | --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | User id: Inbox needs a real user UUID as `X-Actor-Id`                                         | ✅ For local testing: `INBOX_DEV_ACTOR_ID` replaces `emp_001`. The real fix waits for login (JWT → user).                                                                                |
| 2   | **"My tasks" scoping:** Inbox returns every task in the tenant (828), not the user's own (60) | ❌ **Open — needs Inbox.** No owner/assignee filter exists, and in the seed `owner_id` is empty (ownership is in `assignments`). Raised with Inbox: they need to add an assignee filter. |
| 3   | List shape `{content, total_elements}` → `{items, total}`                                     | ✅ Mapped in `InboxClient.listTasks`                                                                                                                                                     |
| 4   | Our single `filter` → Inbox's `status`                                                        | ✅ `WorkspaceService.toStatusFilter`. `search`/`page`/`size` are passed straight through, and Inbox does the paging.                                                                     |
| 5   | Task field names                                                                              | ✅ Mapped in `InboxClient.toTaskDTO`. `owner`/`project` stay empty (Inbox only has ids). `priority` is derived from `priority_score`. `dependencies`/`constraints` are empty.            |
| 6   | `snoozedUntil` when snoozing                                                                  | ❌ Open — snoozing from our screen fails until `TaskStatusUpdateDTO` carries it                                                                                                          |
| 7   | Inbox returns **500** (not 404) for a task id that isn't a UUID                               | ✅ Worked around: the BFF treats a non-UUID id as "not found" without calling Inbox. Needs reporting to Inbox.                                                                           |

## Next steps

1. Inbox: an assignee filter for "my tasks" (gap 2), and merging the Task API to `main`.
   The seed role name is already fixed on `inbox-team` (PR #530).
2. Error handling: Inbox 401/403/400 currently surface as 503 "unavailable". Map them to
   clear messages on the screens.
3. Snooze support (gap 6).
4. Show owner/project names (needs a call to Inbox's users API, or names in the task
   response).
5. Agree with Inbox on a chat API (they mediate with Mind), then add it to the BFF and
   Gateway and wire `ChatPanel`.
