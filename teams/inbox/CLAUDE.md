# Team Inbox — Development Standard & Architecture

This file is the source of truth for how code is written and reviewed in `teams/inbox`.
It is loaded automatically by Claude Code whenever work happens inside this module, and it is
the rulebook that the `architecture-review` skill (`.claude/skills/architecture-review/`) checks
code against. Human reviewers should treat it the same way.

Scope: everything under `teams/inbox/`. This file does not govern other teams' modules or
`shared/` — see [Working with `shared/`](#working-with-shared) for the boundary.

## 1. What Team Inbox owns

Team Inbox owns the **central Task data model** and the **backend services / REST APIs** built
around it. In Phase 1 (Foundation) that means providing the task/data model and API groundwork
other teams build on top of. In Phase 2 it becomes the **Personal Task Inbox & Task Lifecycle**
vertical — where a user manages their own tasks and moves them through their lifecycle
(created → in progress → blocked → done, etc.).

Because other teams depend on what we put here, correctness and stability of what we expose
matter more than moving fast and breaking the shape of things later.

Per the system architecture diagram Ayala shared: Team Mind extracts new tasks and updates to
existing tasks from raw sources — structured ones (GitHub/CRM) via a deterministic ETL, and
unstructured ones (email/Slack/meetings) via Bridge ingestion followed by Mind's
OCR/ASR/classification — and writes them into Inbox's Task schema, described there as "one
canonical record for everyone." Team Chain then reads/links Task records to build the
dependency graph, and downstream consumers (e.g. Lens) read the combined Task + dependency
state to produce recommendations. This confirms the intent that the Task schema is consumed by
other teams from day one, not an Inbox-internal detail — what's still open is *how* that
consumption happens technically (REST calls into our API, published events, etc.) and the exact
field-level shape, which this document doesn't settle rather than assume an answer.

## 2. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Java 11 | Fixed by the Foundation-stage `pom.xml`; team machines are provisioned with JDK 11. |
| Framework | Spring Boot 2.7.18 (Maven) | Already established in the Foundation module; last Spring Boot line with first-class Java 8/11 support. |
| Web layer | Spring MVC (`spring-boot-starter-web`) | Standard synchronous REST stack, no need for WebFlux at our scale. |
| Boilerplate reduction | Lombok | Java 11 has no `record` type (that's Java 16+), so plain DTOs/entities mean a lot of hand-written getters/constructors/`equals`/`hashCode`. Lombok removes that boilerplate without changing the object model. Added as a `teams/inbox`-local dependency — it does not affect other teams. |
| Persistence | **Not yet decided** — tracked in the project-wide DB decision (see `.env.example`: *"DB choice for the project has not been decided"*). | Cross-team call, not ours to make unilaterally. Repository-layer rules below are written to hold regardless of which store is picked. |
| Tests | `spring-boot-starter-test` (JUnit 5, Mockito, AssertJ) | Ships with the Foundation module already; no reason to add another stack. |
| Packaging/run | Docker / Docker Compose | Established in Foundation; keeps "works on my machine" risk low since local Maven isn't guaranteed to be installed on every dev's machine. |

## 3. Project structure & packages

```
teams/inbox/
├── src/main/java/com/rnd/inbox/
│   ├── InboxApplication.java
│   ├── controller/          # REST endpoints
│   ├── service/              # business logic
│   ├── repository/          # data access (Spring Data interfaces)
│   ├── model/
│   │   ├── entity/           # persisted domain model (JPA entities / documents)
│   │   └── enums/            # typed enums used by the domain model (e.g. task status)
│   ├── dto/                  # request/response shapes exposed over the API
│   ├── mapper/               # DTO <-> entity conversion
│   ├── exception/            # custom exceptions + centralized handler
│   └── config/                # @Configuration classes, property binding
├── src/main/resources/application.yml
├── src/test/java/com/rnd/inbox/    # mirrors the main package tree
├── pom.xml
├── Dockerfile / docker-compose.yml
└── .env.example
```

This matches the finalized project structure (`teams/inbox/model/{entity,enums}` +
`mapper`/`exception`/`config` alongside `controller`/`service`/`repository`/`dto`).

**Decision (background):** the Foundation-stage skeleton only had
`controller/service/repository/dto/config`. This standard adds `model` (with `entity` and
`enums`), `mapper`, and `exception` as first-class packages, because:
- Without a separate `entity` package, DTOs and persisted objects have no natural place to stay
  apart, which is exactly what leads to entities leaking through the API (see §4). Grouping
  `entity` and `enums` under one `model` package keeps "the domain model" — persisted data plus
  the typed values it's made of (e.g. a task's status) — in one place, distinct from how it's
  transported (`dto`) or converted (`mapper`).
- Without a `mapper` package, DTO↔entity conversion tends to get inlined into services, bloating
  them and duplicating logic across services.
- Without an `exception` package, exception types and the centralized handler end up scattered
  or duplicated per-controller.

All of these are internal to `teams/inbox` and don't require coordination with other teams.

Sub-package by domain concept when a layer grows past a handful of classes (e.g.
`service/task/`, `service/label/`) rather than letting one package accumulate dozens of
unrelated classes. Don't pre-create domain sub-packages before there's a second domain concept.

## 4. Layer responsibilities

### Controller
- Only: HTTP concerns — route mapping, request/response (de)serialization, status codes,
  delegating to exactly one `Service` method per endpoint.
- Input arrives and leaves as **DTOs only**, never entities.
- No business logic: no branching on business rules, no loops that compute something, no
  direct data access.
- No direct use of `Repository` — always through a `Service`.

### Service
- Owns business logic and orchestration: validation of business rules (as opposed to input
  shape, which is Controller-boundary bean validation — see §7), coordinating one or more
  repositories, transaction boundaries (`@Transactional`), converting between DTO and entity
  (via a `Mapper`, not by hand inline), raising domain exceptions.
- May call other services. Should not reach into another team's module directly — cross-team
  calls go through the module's public REST API / shared contract, not a Java method call.

### Repository
- Only: data access, expressed as thin Spring Data interfaces (or, if a concrete implementation
  is ever needed, a class that talks to the store and nothing else).
- Returns/accepts **entities**, never DTOs.
- No business logic, no validation beyond what the persistence layer itself enforces.

### DTO
- The API's public shape. Split request vs. response DTOs when they diverge (e.g.
  `TaskCreateRequestDto` vs. `TaskResponseDto`) rather than reusing one class both ways.
- Bean-validation annotations (`@NotNull`, `@Size`, …) live on request DTOs.
- DTOs never contain persistence annotations (`@Entity`, `@Id`, `@Table`, …) and never leak
  outside the `controller`/`service`/`mapper` boundary into `repository`.

### Entity
- The persisted domain model. Carries persistence annotations, not bean-validation or
  JSON-serialization annotations aimed at the API.
- Never returned from a `Controller` and never accepted as a `@RequestBody`.

### Config
- `@Configuration` classes and `@ConfigurationProperties`-bound settings only.
- No business logic.
- No literal secrets or environment-specific values — see §8 and §11.

## 5. Dependency rules between layers

```
Controller → Service → Repository
Controller → DTO
Service → Repository, Mapper, other Services (within teams/inbox)
Mapper → DTO, Entity
Repository → Entity
```

- Dependencies point strictly downward. A `Repository` must never depend on a `Service`, an
  `Entity` must never depend on a `DTO`, and nothing depends "up" into `controller`.
- `Controller` must never call `Repository` directly — even for a "trivial" read. This is the
  single most important rule in this document because it's the one most tempting to break under
  time pressure, and it's the one that makes the rest of the layering meaningless once broken.
- A `Service` may call another `Service` inside `teams/inbox`. It must not import classes from
  another team's module — if Inbox needs something from Team Mind/Chain/etc., that's a REST
  call (or, once it exists, a shared contract), not a Java dependency.

## 6. Naming conventions

- Classes are suffixed by role: `TaskController`, `TaskService` (+ `TaskServiceImpl` only if an
  interface is actually justified — see note below), `TaskRepository`, `TaskCreateRequestDto`,
  `TaskResponseDto`, `Task` (entity — no `Entity` suffix; the package already says what it is),
  `TaskMapper`, `TaskNotFoundException`. Enums live in `model/enums` and are named for the
  concept they represent, not suffixed `Enum` (`TaskStatus`, not `TaskStatusEnum`).
- **Decision:** don't create a `Service` interface + `Impl` pair by default. Java interfaces for
  single-implementation services are ceremony with no payoff at our scale; only introduce one
  when there's an actual second implementation or a real test/mocking reason. This keeps the
  codebase smaller and easier to navigate for a small team.
- Packages: all lower-case, no underscores, singular (`controller`, not `controllers`).
- REST resource paths: plural, kebab/lowercase nouns (`/api/v1/tasks`, not `/api/v1/Task`).
- Test classes: `<ClassUnderTest>Test`, one test class per production class, same package under
  `src/test`.
- Constants: `UPPER_SNAKE_CASE`. Booleans read as questions (`isBlocked`, not `blocked`).

## 7. REST API rules

- Base path: `/api/v1/...` — version the API from day one since other teams/the gateway will
  depend on it and we want room to evolve without breaking them.
- Resource-oriented URIs and correct HTTP verbs (`GET` read, `POST` create, `PUT`/`PATCH`
  update, `DELETE` remove). Actions that don't map to a CRUD verb are a sub-resource or a POST
  with a clear name (`POST /tasks/{id}/complete`), not a verb in the URL.
- Only DTOs cross the wire — request bodies deserialize into request DTOs, responses serialize
  from response DTOs. Never `Task` (entity) directly.
- Status codes are meaningful: `200`/`201`/`204` for success, `400` for bad input, `404` for
  missing resources, `409` for conflicts, `500` only for genuinely unexpected failures (see §9).
- Every endpoint validates its input DTO (§8) before touching a `Service`.

## 8. Validation

- Structural/input validation (required fields, formats, sizes) uses `javax.validation`
  annotations on request DTOs, enforced via `@Valid` at the controller parameter.
- Business-rule validation (things that require domain knowledge or a DB lookup — e.g. "a task
  can't be marked done if it has open sub-tasks") lives in the `Service` layer and raises a
  domain exception, not a generic `IllegalArgumentException`.
- Don't duplicate structural checks in the service that `@Valid` already guarantees at the
  controller boundary.

## 9. Exception handling

- Custom, unchecked exceptions extending a common `InboxException` base, under
  `exception/` (e.g. `TaskNotFoundException`, `InvalidTaskStateException`).
- One `@RestControllerAdvice` (`exception/GlobalExceptionHandler`) maps exception types to HTTP
  status + a consistent error response shape:
  ```json
  { "status": 404, "error": "TASK_NOT_FOUND", "message": "Task 123 not found", "timestamp": "..." }
  ```
  This shape is a **local Inbox default**, not (yet) a cross-team standard — whether all
  services should share one error format is an open cross-team question, not something this
  document decides. Adopt a common format instead if/when that's agreed team-wide.
- No empty `catch` blocks, no catching `Exception`/`Throwable` just to swallow it, no returning
  a raw stack trace or exception message straight to the client.
- Only let an exception surface as a bare `500` when it's truly unanticipated — anything the
  business logic can foresee should be its own named exception with a proper status code.

## 10. Logging

- SLF4J via Lombok's `@Slf4j`, never `System.out.println`/`e.printStackTrace()`.
- Log at the `Service` layer for business-relevant events (task created, state transition,
  rejected operation) — not in `Controller` (would duplicate) or `Repository` (too low-level to
  be meaningful on its own).
- Levels: `ERROR` = unexpected failure needing attention, `WARN` = handled-but-notable (e.g.
  rejected invalid state transition), `INFO` = significant business events, `DEBUG` = detail for
  local troubleshooting only.
- Never log secrets, tokens, or full request/response bodies that might carry sensitive data.

## 11. Tests

- Every `Service` class with real logic gets a unit test that mocks its `Repository`/`Mapper`
  collaborators (Mockito) — this is where business-rule tests belong.
- Every `Controller` gets a slice test (`@WebMvcTest` + `MockMvc`) covering the happy path, a
  validation failure (400), and a not-found path (404) at minimum.
- Repositories are thin Spring Data interfaces — they generally don't need dedicated tests
  unless they carry a custom `@Query`, in which case that query gets a test.
- Test naming: `methodName_condition_expectedResult` (e.g.
  `completeTask_whenOpenSubtasksExist_throwsInvalidTaskStateException`).
- New logic without a corresponding test is treated as incomplete work, not a follow-up — see
  the bootcamp guide's Tests rule.

## 12. Configuration / environment variables

- All environment-specific or secret-shaped values (ports, profiles, future DB credentials, API
  keys) come from environment variables via `application.yml`'s `${VAR:default}` syntax —
  never a literal value baked into `application.yml` or Java code.
- `.env.example` is kept in sync with every variable `application.yml` references, with safe
  placeholder values — real `.env` files are never committed (already covered by `.gitignore`).
- No secret, token, password, or credential is ever hard-coded anywhere in `teams/inbox`, in a
  test, or in a comment — not even "temporarily."

## 13. Working with `shared/`

- `shared/` doesn't exist in the repo yet (as of this writing, only per-team module folders are
  merged into `main`) — the structure in the Foundation proposal is a target, not current fact.
- Nothing that is only meaningful to Team Inbox belongs under `shared/` once it exists — e.g.
  Inbox-internal DTOs, Inbox-specific config, or Inbox's own exception types stay in
  `teams/inbox`.
- Something only moves to (or gets defined directly in) `shared/` when at least one other team
  genuinely needs it too (an API/event contract another team consumes, a DB migration touching
  shared schema, a truly cross-cutting utility) **and** that's been agreed with the other
  team(s)/team leads — never as a unilateral change. This matches the bootcamp guide's rule #5.
- Because the Task model is Team Inbox's Foundation responsibility, whether (and how) the Task
  shape becomes a `shared/contracts` API/event contract for other teams to consume is itself a
  cross-team decision, not something this document settles.

## 14. Clean code principles

- Small, single-purpose classes and methods; a class doing more than one job (e.g. a "Service"
  that also formats HTTP responses) gets split.
- No duplicated logic — a second copy-paste of business logic is a signal to extract it, not to
  keep both.
- Names say what something is/does; a name needing a comment to explain it should usually be a
  better name instead.
- No dead code, no commented-out code, no `TODO` without a linked GitHub issue.
- Prefer composition over inheritance for anything beyond the entity/exception hierarchies above.
- Keep methods readable top-to-bottom without deep nesting — extract a guard clause / early
  return instead of nesting another `if`.
