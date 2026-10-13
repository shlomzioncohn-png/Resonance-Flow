# API integration status

Snapshot as of 2026-10-04. Current focus: connecting the BFF to Inbox's real Task API.
The **My Tasks** list and **Task details** screens now work end to end against a local
Inbox. The chat was dropped from the plan (2026-10-06) and its code removed
(ChatPanel, taskStore, the `employee-task` endpoint).

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
  - `POST /workspace/recommend` — real mode: Inbox's task selection
    (`GET /api/v1/tasks/selection?availableMinutes=&userId=`, inbox-team PR #550) picks
    the tasks. If that call fails (other than a 4xx), it falls back to the BFF's own
    ranking over the user's tasks (Chain and Mind still mocked).
  - `GET /workspace/tasks/selection?availableMinutes=` — Inbox's task selection as-is
    (no fallback; 1-480 minutes)
  - `GET /manager/workload`, `GET /manager/projects`,
    `GET /admin/projects[/{id}]` — mocked inside the BFF
- **Frontend**: every screen calls the Gateway through `services/*Api.ts`. There is no
  mocking in the browser anymore (MSW was removed on 2026-10-06): all mock data lives in
  the BFF.

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

Checked end to end through the Gateway on 2026-10-04/05: the list (the user's 60 tasks, status filter,
search, paging), a single task, a status change (saved in Inbox), and an unknown id (404).

## Gaps — status

| #   | Gap                                                                                            | Status                                                                                                                                                                        |
| --- | ---------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | User id: Inbox needs a real user UUID as `X-Actor-Id`                                          | ✅ For local testing: `INBOX_DEV_ACTOR_ID` replaces `emp_001`. The real fix waits for login (JWT → user).                                                                     |
| 2   | **"My tasks" scoping:** Inbox returned every task in the tenant (828), not the user's own (60) | ✅ Inbox added a `userId` filter (inbox-team PR #534). The BFF sends it on every list call. Checked live: 60 tasks.                                                           |
| 3   | List shape `{content, total_elements}` → `{items, total}`                                      | ✅ Mapped in `InboxClient.listTasks`                                                                                                                                          |
| 4   | Our single `filter` → Inbox's `status`                                                         | ✅ `WorkspaceService.toStatusFilter`. `search`/`page`/`size` are passed straight through, and Inbox does the paging.                                                          |
| 5   | Task field names                                                                               | ✅ Mapped in `InboxClient.toTaskDTO`. `owner`/`project` stay empty (Inbox only has ids). `priority` is derived from `priority_score`. `dependencies`/`constraints` are empty. |
| 6   | `snoozedUntil` when snoozing                                                                   | ✅ `snoozedUntil` is passed from the frontend through Gateway and BFF to Inbox. Without it Inbox answers 400. (No snooze button on the screens yet.)                          |
| 7   | Inbox returns **500** (not 404) for a task id that isn't a UUID                                | ✅ Worked around: the BFF treats a non-UUID id as "not found" without calling Inbox. Needs reporting to Inbox.                                                                |

## Known Inbox bug (reported)

`GET /api/v1/tasks/selection` answers **500** when a candidate task has no
`priority_score` (`SelectionScoreCalculator.normalizedPriority` NPE). With the seed, that
happens for most windows above ~20 minutes, so the BFF falls back to its own ranking
there.

## Next steps

1. Inbox: merging the Task API to `main`. The seed role name is already fixed on
   `inbox-team` (PR #530).
2. Show owner/project names. This needs names in Inbox's task response (Inbox only sends
   ids).

Done since the first snapshot: error handling (Inbox 401/403/400 keep their status and
get a clear message on screen; the Gateway passes BFF's 4xx message on), snooze, "my
tasks" scoping, and recommendations that only pick workable tasks.
