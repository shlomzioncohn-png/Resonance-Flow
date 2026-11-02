# Employee Workspace — Work Plan (per layer)

Companion to [`employee-workspace-api-contract.md`](./employee-workspace-api-contract.md).
That doc defines *what* crosses the wire; this one defines *who builds what,
in what order*, for the Employee → My Workspace screen.

## 0. Alignment (now)

- [x] Draft the API contract doc from the screenshot + attached DTO classes.
- [ ] Review the contract with Dvori — confirm the route in §2 and the
      `X-User-Id` decision in §3.
- [ ] Share both docs with the manager for a go/no-go before implementation
      (also resolves the §9 scope caveat).

---

## 1. lens-bff (this doc's author — primary responsibility)

Currently skeleton-only: `com.rnd.bff` has just `BffApplication` and a
`/api/status` controller (`teams/lens/bff/src`). No `dto/`, `service/`,
`controller/`, or `client/` packages exist yet.

1. **DTOs** (`com.rnd.bff.dto`) — `WorkspaceRequestDTO`, `TaskRecommendationDTO`,
   `TaskDTO` (as attached, plus `blocksTaskIds`), `TaskDetailDTO`,
   `BlockTaskRequestDTO`, `BlockReason`, `TaskListResponseDTO`,
   `UserProfileDTO`, `ErrorResponseDTO` — see contract doc §5.
2. **Controllers** (`com.rnd.bff.controller`) — `WorkspaceController`
   (endpoint 4.1), `TaskController` (endpoints 4.2–4.5), `MeController`
   (endpoint 4.6).
3. **User-context plumbing** — read `X-User-Id` (and `X-User-Role`) via a
   request interceptor/`@RequestHeader`; until Gateway's auth filter exists,
   default to a hardcoded dev header so the BFF is independently testable.
4. **Downstream clients** (`com.rnd.bff.client`) — add `spring-boot-starter-webflux`
   or a `RestClient` bean (pom.xml has neither yet); define `InboxClient`,
   `ChainClient` interfaces per the contract doc §7.
5. **Mock implementations of those clients** — since Inbox/Chain/Mind are
   skeleton-only today, ship an in-memory/fake implementation behind the
   same interface (e.g. `@Profile("mock")`) so the BFF, Gateway route, and
   frontend can all be integrated and demoed before the downstream teams
   are ready. Swap to real HTTP calls per-endpoint as each downstream
   contract lands.
6. **Global exception handler** → `ErrorResponseDTO` envelope (contract §5.6).
7. **Tests** — controller tests with mocked clients; keep the mock-client
   behavior covered so it stays a trustworthy stand-in.

## 2. lens-gateway (Dvori)

Currently: declarative routes only (`bridge`, `mind`, `inbox`, `chain`), no
route to BFF, no auth filter implemented yet (README lists it as a next
step).

1. Add the `bff` route block from the contract doc §2 to `application.yml`.
2. Confirm existing global CORS config covers the new path (it's currently
   wildcard, so likely already does).
3. Auth filter (JWT validation + `X-User-Id`/`X-User-Role` injection) — this
   is Gateway's pre-existing open item, not new scope from this screen, but
   this screen is the first consumer that actually needs it since task data
   is per-employee. Worth prioritizing accordingly.

## 3. lens-frontend

Currently: `app/(app)/employee/` routes are `EmptyState` stubs; no page,
component, or mock exists yet for time-input → recommendation → task
details → block-reason flow (confirmed by repo search).

1. Build the My Workspace page per the screenshot, wired to the contract
   doc's endpoints 4.1–4.4 (+ 4.6 for the header greeting/avatar).
2. Follow the existing `LensDataInterface`/adapter pattern
   (`.github/agents/react-lens.agent.md`) rather than calling the BFF shape
   directly from components, and the `loading`/`empty`/`error`/content
   pattern already used elsewhere ([`app-shell-and-routing.md`](../../frontend/docs/app-shell-and-routing.md)).
3. Map `TaskDTO.status` onto the existing `StatusBadge`
   (`healthy | warning | critical | neutral`) tone convention — needs an
   explicit status→tone mapping (e.g. `READY`→neutral, `IN_PROGRESS`→healthy,
   `BLOCKED`→critical).
4. This is frontend-team scope, listed here only so BFF work stays
   sequenced against a real consumer; not this doc's author's task to build.

## 4. Downstream (Inbox / Chain / Mind) — tracked, not owned by any LENS layer

Blocking risk carried over from the contract doc §7. Nothing to build here
directly, but each item below unblocks a mock→real swap in Step 1.5:

- Chain: best-fit selection contract (time + user → task, explanation, confidence).
- Chain: blocker/constraint update contract (for `/tasks/{id}/block`).
- Inbox: canonical Task read/write endpoints, and confirmation on whether
  `source` / `sprint` / `createdAt` are tracked fields.

---

## 5. Suggested sequencing

```text
Stage 0  Contract review (Dvori + manager)         ← do first
Stage 1  BFF: DTOs + controllers + mock clients     ← can start immediately after Stage 0
Stage 2  Gateway: route + (existing) auth filter    ← in parallel with Stage 1
Stage 3  Frontend: page wired to mocked BFF         ← after Stage 1 has a running mock BFF
Stage 4  Swap mock clients for real Inbox/Chain     ← as each team's contract lands
```

Stages 1–3 do not need to wait on Stage 4 — that's the point of the mock
clients in Step 1.5.

## 6. Open items to close before/soon after Stage 0

- `userId` in request body vs. `X-User-Id` header (contract §3).
- DTO package location: `com.rnd.gateway.dto` (as attached) vs.
  `com.rnd.bff.dto` (recommended, contract §6).
- Error envelope shape shared across Inbox/Gateway/BFF (contract §5.6).
- One-line scope confirmation with the manager re: contract §9.
