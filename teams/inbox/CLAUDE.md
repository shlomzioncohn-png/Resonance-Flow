# Team Inbox — Development Standard & Architecture

Source of truth for how code is written and reviewed in `teams/inbox`. Loaded automatically by
Claude Code for work inside this module, and the rulebook the `architecture-review` skill checks
against. Human reviewers should treat it the same way.

**Scope:** everything under `teams/inbox/`. Does not govern other teams' modules or `shared/` —
see §16 `shared/` for that boundary.

Cross-references below use `§N Name`. If a number and a name disagree, the **name** wins.

## 1. What Team Inbox owns

Team Inbox owns the **canonical Task data model** and the backend services / REST APIs around it.
Phase 1 (Foundation): provide the task/data model and API groundwork other teams build on.
Phase 2: the **Personal Task Inbox & Task Lifecycle** vertical — a user managing their own tasks
through their lifecycle.

Other teams consume what we expose (Mind writes tasks in, Chain reads them to build the
dependency graph, Lens reads the result). So the stability of our schema and API shape matters
more than shipping fast and reshaping later. Full system context:
`docs/Gong_Technical_Spec_EN_with_Flows.md`. Field-level schema:
`docs/Inbox_Data_Schema_Specification.md`.
## 2. Specifications — the data model is already specified

**Before adding, renaming or removing any field, entity, enum value or migration, read
`docs/Inbox_Data_Schema_Specification.md`.** It is the reviewed, authoritative definition of the
canonical schema. This file governs *how code is written*; that file governs *what the data is*.
Where they touch the same subject, the spec wins.

| Document | What it settles |
|---|---|
| `docs/Inbox_Data_Schema_Specification.md` | The 11 entities, their fields, types, constraints and enum values (its section 3); key design decisions (section 2); Inbox↔Chain ownership (section 3.8.1); open questions (section 5). |
| `docs/Gong_Technical_Spec_EN_with_Flows.md` | Cross-team data flow: the two ingestion paths, stage-by-stage flow, the topic/API table, audit-trail behaviour, and the lifecycle flow diagrams. |

Entities and enums, living in their owning domain module (§5), are the Java projection of that
spec. Migrations in `shared/database/migrations` are its physical form. All three must agree — if
the code and the spec disagree, that is a bug to raise, not a discrepancy to code around.

### Decisions already made — do not re-litigate in code

These come from the spec and are binding. They are listed here because each one is an easy thing
to get wrong by writing the "obvious" thing instead:

- **`tasks` is the canonical schema and Inbox is its sole authority.** Every source converges on
  this one representation.
- **No `is_completed` field.** `status = 'done'` is the source of truth; `completed_at` records
  when.
- **Task dependencies are not stored in Inbox** — Chain owns the dependency graph. Don't add a
  `depends_on` table here.
- **`is_available` is calculated by Chain and only materialized in Inbox.** Inbox does not
  compute it.
- **`priority_score` is Chain's too, on the same terms** — `1..10`, fractional allowed, higher
  means more important, NULL until Chain has scored the task. Delivered on
  `task.scheduling.updated`; omitting it leaves the stored value unchanged, so a message that
  updates only availability never wipes a score. Inbox does not compute it either.
- **OTP is stored in PostgreSQL** (hash + expiry). Don't introduce Redis for it.
- **No granular RBAC.** A basic `users.role` only, until a schema change is agreed.
- **`tier`, `role_context` and `confidence` are nullable** — they may be unset before Mind
  classifies. Don't make them required to simplify a mapper.
- **`correlation_id` is technical, not a business attribute** — set on the unstructured creation
  path, NULL for structured/manual. Don't expose it as a product field.
- **`owner_id` is not the assignee.** Ownership lives on `tasks.owner_id`; assignment lives in
  `assignments`. They are different concepts.

### Open questions — do not silently resolve

The spec's section 5 lists items explicitly awaiting a decision from the Inbox lead, mentors, or a
cross-team owner. As of this writing: audit-trail granularity (JSONB vs. per-field),
**ownership of the parent/child task hierarchy** (`tasks.parent_task_id` vs. Chain), whether
`users.manager_id` is needed alongside team lead.

If work requires one of these, **stop and get the decision** — implementing a guess here creates
a schema fact other teams will build on. Record the answer in the spec, then implement.

## 3. Commands

The database is **shared infrastructure**: one `resonance_flow` instance for the whole project,
with the schema owned by `shared/database/migrations`. It lives in the **root** compose file, not
in Inbox's — a per-team Postgres would mean a per-team copy of a schema that is supposed to be
canonical. Don't add one here.

There are two compose files, and neither alone gives you both Postgres and Kafka:

| File | Services | Inbox port | `DB_HOST` |
|---|---|---|---|
| `docker-compose.yml` (repo root) | `postgres`, `pgadmin`, `bridge`, `chain`, `inbox` — **no Kafka** | `${INBOX_SERVER_PORT:-8082}` → 8080 | `postgres` (compose network) |
| `teams/inbox/docker-compose.yml` | `inbox`, `kafka`, `kafka-ui` — **no Postgres** | `${SERVER_PORT:-8080}` | `host.docker.internal` |

They sit on separate Docker networks; the Inbox-local file reaches the database over the host's
published `5432`, so the root file has to be up first.

```bash
# build & test — from teams/inbox/
mvn test                      # unit + slice tests
mvn -B package                # build the jar (Java 21)
mvn -B package -DskipTests    # build without tests, as the Dockerfile does

# working local stack
docker compose -f ../../docker-compose.yml up -d postgres pgadmin   # database first
cp .env.example .env                                                # first run only
docker compose up --build                                           # inbox + kafka + kafka-ui

docker compose down           # stop; add -v to also drop the kafka volume
```

Postgres `${DB_PORT:-5432}` · pgAdmin `${PGADMIN_PORT:-5050}`, pre-wired to the local server via
`shared/database/pgadmin/servers.json` · Kafka UI `${KAFKA_UI_PORT:-8090}`.

Flyway runs from `shared/database/migrations`, mounted read-only into the container — read
§16 `shared/` before adding a migration.

Local Maven is not guaranteed on every dev machine; if `mvn` is unavailable, build through Docker.

> **Known gaps — say which compose file you mean until these are resolved.**
> Kafka is cross-team infrastructure (Bridge publishes, Inbox consumes) but currently exists only
> in Inbox's compose file — no other team's compose defines a broker, so Bridge cannot reach the
> one Inbox listens on. It most likely belongs in the root file beside Postgres, which is a
> cross-team change to agree on, not to push unilaterally (§16 `shared/`). The two files also
> publish Inbox on different ports (8082 vs. 8080).

## 4. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 | Fixed by `pom.xml`. `record` and other Java 16+ features are available. |
| Framework | Spring Boot 3.3.13 (Maven) | Current LTS-track Spring Boot line. Brings Flyway 10, which splits database support into separate modules — see `flyway-database-postgresql` in `pom.xml`. |
| Web layer | Spring MVC (`spring-boot-starter-web`) | Synchronous REST; no need for WebFlux at our scale. |
| Persistence | PostgreSQL + Spring Data JPA (Hibernate 5.5) | Decided project-wide. Enum columns map to native PG enums via `shared/model/type/LowercasePostgreSQLEnumType` + `hypersistence-utils`. |
| Migrations | Flyway, from `shared/database/migrations` | Shared schema — read §16 `shared/` before adding one. |
| Messaging | Kafka (`spring-kafka`) + `spring-retry` | How Bridge/Mind deliver tasks into Inbox — see §10 Event consumption. |
| Boilerplate | **No Lombok.** Hand-written getters/constructors/`equals`/`hashCode` | Not in `pom.xml`; existing entities are written by hand. Don't add `@Data`/`@Slf4j` — it won't compile. Adding it is a team decision, not a silent one. |
| Tests | `spring-boot-starter-test` (JUnit 5, Mockito, AssertJ), `spring-kafka-test` | Already on the classpath. |
| Packaging/run | Docker / Docker Compose | See §3 Commands. |

## 5. Project structure & packages

Package **by feature, not by layer**. Each domain module is one flat top-level package holding
every class that module owns — entity, enum, repository, service, controller, mapper, dto,
exception — whichever of those it actually has. The old layout (a `controller/`, `service/`,
`repository/`, `dto/`, `model/entity`, … each mixing every domain's classes together) is gone:
finding everything about `Task` meant checking half a dozen folders. Now it's one.

```
teams/inbox/src/main/java/com/rnd/inbox/
├── InboxApplication.java
├── task/               Task, TaskFeedback, Assignment (+ enums), TaskRepository,
│                       AssignmentRepository, TaskService, TaskIngestionPersistenceHandler,
│                       TaskCreatedPayload, TaskNotFoundException
├── user/               User, UserAuthIdentity, OtpRequest (+ enums), UserRepository,
│                       UserService, UserController, UserMapper, User*Dto,
│                       UserNotFoundException, DuplicateEmailException, DuplicateUsernameException
├── tenant/             Tenant, TenantStatus, TenantRepository, TenantNotFoundException,
│                       TenantSummaryDto
├── project/            Project, ProjectStatus, ProjectRepository (placeholder — no
│                       service/controller yet)
├── team/               Team, TeamMember, TeamMemberId, TeamStatus, TeamRepository
│                       (placeholder — no service/controller yet)
├── audit/              AuditLog, AuditLogRepository, AuditService — used by task and user,
│                       but owns real behavior of its own, so it's a module, not shared infra
├── outbox/              OutboxEvent, OutboxStatus, OutboxEventRepository, OutboxWorker,
│                       OutboxCleanupJob — generic transactional-outbox infra
├── structuredtask/      Kafka ingestion of structured-source task events (from BRIDGE, topic
│                       `bridge.structured.tasks`): StructuredTaskConsumer,
│                       StructuredTaskEventDto, StructuredTaskMapper, ConsistentAssignment(Validator)
├── taskcreationrequest/ Kafka ingestion of AI-extracted task-creation requests (from MIND, topic
│                       `task.creation.requested`): TaskCreationRequestConsumer,
│                       TaskCreationRequestEventDto, TaskCreationRequestMapper
├── scheduling/          Kafka ingestion of scheduling/availability updates (from CHAIN, topic
│                       `task.scheduling.updated`): SchedulingConsumer, SchedulingEventDto,
│                       SchedulingPersistenceHandler
│
└── shared/              cross-cutting infra, used by 2+ modules or fully generic — nothing here
    │                    is owned by, or named after, any one domain module
    ├── messaging/
    │   ├── kafka/        generic Kafka event-processing template — see §10
    │   └── validation/   generic bean-validation annotations (`@ValidUuid`, `@IsoInstant`, …)
    ├── event/            generic publish-side Kafka infra (Event, EventPublisher, KafkaEventPublisher)
    ├── security/         CurrentActor / CurrentActorArgumentResolver, wired globally in WebMvcConfig
    ├── config/           @Configuration, @ConfigurationProperties
    ├── exception/        InboxException (base), GlobalExceptionHandler, UnauthenticatedException,
    │                    ForbiddenActionException (currently unused)
    ├── dto/              ErrorResponseDto, FieldErrorDto, PageResponseDto — generic API shapes
    └── model/type/       Hibernate UserTypes (PG enum mapping)
```

Tests mirror this tree under `src/test/java/com/rnd/inbox/`.

**Why `shared/` exists:** early on, `dto/` and `exception/` were the only two packages left named
after a technical layer instead of a role — everything else (`security/`, `config/`, `messaging/`)
already read as "what it does." Grouping every cross-cutting package under one `shared/` parent
fixes that inconsistently-named leftover and makes the split explicit: a domain module at the top
level owns its own code, `shared/` never does — it's a landing spot classes earn by being used
from 2+ modules (or having no domain meaning at all), not a place to put something because it
doesn't obviously belong anywhere else.

**Naming rule:** a module is named for the action it performs, not for whoever sends it the data.
`structuredtask`, `taskcreationrequest` and `scheduling` are named after what they ingest (and the
Kafka topic that names it), not after the team that publishes to them (Bridge/Mind/Chain) — those
team names belong in comments and docs describing *where the event comes from*, never in a
package or class name. This bit us for real: those three packages used to be named `bridge.task`,
`mind.task` and `chain.scheduling`.

New module, same rule as before: create a domain package once there's an actual class to put in
it — don't pre-create an empty one for a concept that has no code yet. `project/` and `team/` are
the exception, kept as placeholders because they already hold a real (if minimal) entity +
repository.

## 6. Naming conventions

- Suffix by role: `TaskController`, `TaskService`, `TaskRepository`, `TaskCreateRequestDto`,
  `TaskResponseDto`, `Task` (entity — no `Entity` suffix, the package already says it),
  `TaskMapper`, `TaskNotFoundException`. Enums are named for the concept, not suffixed `Enum`
  (`TaskStatus`, not `TaskStatusEnum`).
- **Decision:** no `Service` interface + `Impl` pair by default. Single-implementation
  interfaces are ceremony at our scale. Introduce one only when a second implementation or a
  real mocking need actually exists.
- Packages: lower-case, no underscores, singular (`controller`, not `controllers`).
- REST paths: plural **kebab-case** nouns — `/api/v1/tasks`, `/api/v1/task-feedback`.
  Never camelCase or PascalCase in a URL.
- Test classes: `<ClassUnderTest>Test`, one per production class, mirrored package.
- Constants: `UPPER_SNAKE_CASE`.
- Booleans: the **field** carries no prefix (`blocked`), the **getter** reads as a question
  (`isBlocked()`). A field literally named `isBlocked` produces `getIsBlocked()` and serializes
  badly — don't.

## 7. Layer responsibilities

### Controller

- Only HTTP concerns: route mapping, (de)serialization, status codes, delegating to exactly one
  `Service` method per endpoint.
- DTOs in and out, never entities.
- No business logic: no branching on business rules, no computation loops, no data access.
- Never uses a `Repository` directly.

### Service

- Owns business logic and orchestration: business-rule validation (as opposed to input shape,
  which is controller-boundary bean validation — see §11 Validation), coordinating repositories,
  transaction boundaries (`@Transactional`), raising domain exceptions.
- **Accepts and returns DTOs. Entities never leave the service layer** — conversion happens here
  via a `Mapper`, not inline by hand, and not in the controller.
- May call other services inside `teams/inbox`. Never reaches into another team's module by Java
  import — cross-team goes over REST or Kafka (§10).

### Repository

- Data access only, as thin Spring Data interfaces.
- Returns and accepts **entities**, never DTOs.
- No business logic.

### DTO

- The API's public shape. Separate request and response DTOs when they diverge
  (`TaskCreateRequestDto` vs. `TaskResponseDto`) rather than reusing one class both ways.
- Bean-validation annotations (`@NotNull`, `@Size`, …) live on request DTOs.
- Never carry persistence annotations, never reach the `repository` layer.

### Entity

- The persisted domain model. Persistence annotations only — no bean-validation, no API-facing
  JSON annotations.
- Never returned from a controller, never accepted as a `@RequestBody`.

### Config

- `@Configuration` and `@ConfigurationProperties` only. No business logic.
- No literal secrets or environment-specific values — see §15 Configuration.

## 8. Dependency rules between layers

```
Controller → Service → Repository
Controller → DTO
Service    → Repository, Mapper, other Services (within teams/inbox)
Consumer   → Service          (same rules as Controller — see §10)
Mapper     → DTO, Entity
Repository → Entity
```

- Dependencies point strictly downward. A `Repository` never depends on a `Service`, an `Entity`
  never on a `DTO`, and nothing depends "up" into `controller`.
- **`Controller` must never call `Repository` directly** — not even for a trivial read. This is
  the most important rule here: it is the one most tempting to break under time pressure, and
  breaking it makes the rest of the layering meaningless.
- A `Service` may call another `Service` inside `teams/inbox`, but never imports from another
  team's module.

## 9. REST API rules

- Base path `/api/v1/...` — versioned from day one, since other teams depend on it.
- Resource-oriented URIs, correct verbs (`GET` read, `POST` create, `PUT`/`PATCH` update,
  `DELETE` remove). Non-CRUD actions are a sub-resource, not a verb in the path:
  `POST /api/v1/tasks/{id}/complete`.
- Only DTOs cross the wire. Never `Task` directly.
- Meaningful status codes: `200`/`201`/`204` success, `400` bad input, `404` missing,
  `409` conflict, `500` only for genuinely unexpected failures (§12 Exception handling).
- Every endpoint validates its request DTO (§11 Validation) before calling a `Service`.

> **Known gap — actor identity (`X-Actor-Id`) is trusted as sent, not verified.**
> `CurrentActorArgumentResolver` (`security/`) takes the actor id straight from the `X-Actor-Id`
> request header and only checks that it parses as a UUID and belongs to an existing user — there
> is no token, signature, or session behind it. Anyone who can reach the API can claim to be any
> user id they can guess or observe, and every audit log entry and every authorization check on
> Users (organization-scoping — §7 Service, `UserService.assertActorBelongsToOrganization`)
> ultimately trusts this header. This is a stand-in for a real authentication mechanism (session
> or JWT-backed identity) and must not be treated as sufficient once the API is reachable by
> anyone other than trusted internal callers.

## 10. Event consumption (Kafka)

Bridge and Mind deliver tasks into Inbox over Kafka, not over REST. A consumer is a second entry
point into the same domain and follows the same layering as a controller.

- A consumer is an **adapter**: deserialize, validate the event shape, delegate to exactly one
  `Service` method. No business logic, no direct `Repository` access.
- Each ingestion path is its own module (`structuredtask/`, `taskcreationrequest/`,
  `scheduling/` — see §5), holding its consumer and the event contract it consumes.
  `shared/messaging/kafka` and `shared/messaging/validation` hold only the transport-layer plumbing genuinely
  shared across all of them.
- Delivery is **at-least-once — every consumer must be idempotent.** Redelivery of the same
  event must not create a duplicate task. Deduplicate on the source's own identifier (source
  system + external id), not on a locally generated key.
- Distinguish failure kinds explicitly:
  - **Malformed / permanently invalid event** — never retried. Publish to the error topic with
    enough context to diagnose, then acknowledge. Retrying it forever blocks the partition.
  - **Transient failure** (DB down, timeout) — retried with backoff via `spring-retry`, and
    surfaced as its own exception type so it is never confused with the case above.
- Never log the full event payload — see §14 Logging.
- Topic names and consumer groups come from configuration, never hard-coded (§15).

## 11. Validation

- Structural validation (required fields, formats, sizes): `javax.validation` annotations on
  request DTOs, enforced with `@Valid` at the controller parameter.
- Business-rule validation (needs domain knowledge or a DB lookup — e.g. "a task can't be marked
  done while it has open sub-tasks") lives in the `Service` and raises a domain exception, never
  a bare `IllegalArgumentException`.
- Don't repeat in the service what `@Valid` already guarantees at the boundary.

## 12. Exception handling

- Custom unchecked exceptions extending a common `InboxException` base (`shared/exception/`),
  living in the module they belong to, not in `shared/exception/` itself (`TaskNotFoundException`
  is in `task/`, `UserNotFoundException` is in `user/`, …) — `shared/exception/` holds only the
  base type and truly cross-cutting exceptions (`UnauthenticatedException`).
- One `@RestControllerAdvice` — `shared/exception/GlobalExceptionHandler` — maps exception types to a
  status plus a single error shape:

  ```json
  {
    "status": 404,
    "error": "TASK_NOT_FOUND",
    "message": "Task 123 not found",
    "timestamp": "2026-08-26T10:15:30Z"
  }
  ```

  - `error` is a stable `UPPER_SNAKE_CASE` code owned by the exception type itself (each
    `InboxException` exposes one), not a string assembled at the throw site.
  - `timestamp` is UTC ISO-8601 (`Instant.now()`), always.
  - `message` is safe to show a caller: no stack traces, no SQL, no internal identifiers.
- This shape is a **local Inbox default**, not a cross-team standard. Whether all services share
  one error format is an open cross-team question — adopt a common format if and when agreed.
- No empty `catch`, no catching `Exception`/`Throwable` just to swallow it.
- A bare `500` is only acceptable for the genuinely unanticipated. Anything the business logic
  can foresee gets its own named exception and a proper status code.

## 13. Tests

- Every `Service` with real logic gets a unit test mocking its `Repository`/`Mapper`
  collaborators (Mockito). Business rules are tested here.
- Every `Controller` gets a slice test (`@WebMvcTest` + `MockMvc`) covering at minimum: happy
  path, validation failure (400), not-found (404).
- Every **consumer** (§10) gets a test covering: a valid event persists, a malformed event goes to
  the error topic without retrying, and **redelivery of the same event does not duplicate**.
- Repositories are thin interfaces and don't need tests unless they carry a custom `@Query` —
  then that query gets one.
- Naming: `methodName_condition_expectedResult`, e.g.
  `completeTask_whenOpenSubtasksExist_throwsInvalidTaskStateException`.
- New logic without a test is incomplete work, not a follow-up.

## 14. Logging

- SLF4J via `LoggerFactory.getLogger(X.class)` in a `private static final Logger log` field.
  Never `System.out.println` or `e.printStackTrace()`. (No `@Slf4j` — see §4, no Lombok.)
- Log at the `Service` layer for business-relevant events (task created, state transition,
  rejected operation) — not in controllers (duplicates) or repositories (too low-level to mean
  anything on their own). Consumers may log delivery-level facts (offset, event id) but not
  business events.
- Levels: `ERROR` unexpected failure needing attention · `WARN` handled-but-notable (rejected
  transition, event routed to the error topic) · `INFO` significant business events · `DEBUG`
  local troubleshooting detail.
- **Never log secrets, tokens, or full request/response/event bodies.** Log identifiers instead.

## 15. Configuration / environment variables

- Every environment-specific or secret-shaped value (ports, profiles, DB credentials, Kafka
  bootstrap servers, topic names, API keys) comes from an environment variable via
  `application.yml`'s `${VAR:default}` syntax — never a literal in YAML or Java.
- **`.env.example` stays in sync with every variable referenced by `application.yml` *and*
  `docker-compose.yml`**, with safe placeholder values. Real `.env` files are never committed.
  *(Known gap: `KAFKA_BOOTSTRAP_SERVERS` is set in `docker-compose.yml` but missing from
  `.env.example`, which also still carries a stale "DB choice not decided" note. Fix on next
  touch.)*
- No secret, token, password or credential is hard-coded anywhere in `teams/inbox` — not in a
  test, not in a comment, not "temporarily".

## 16. Working with `shared/`

- `shared/database/migrations` exists and is consumed by Inbox's Flyway config. A migration there
  touches **every** team's schema — treat it as a cross-team change: agree it first, and never
  edit a migration that has already been applied; add a new one.
- Nothing that is only meaningful to Team Inbox belongs in `shared/` — Inbox DTOs, config and
  exception types stay in `teams/inbox`.
- Something moves into `shared/` only when another team genuinely needs it **and** that has been
  agreed with the other team(s)/leads — never unilaterally.
- Whether the Task shape becomes a shared API/event contract is itself a cross-team decision,
  not something this document settles.

## 17. Clean code

- Small single-purpose classes and methods; split a class doing two jobs.
- Extract on the second copy-paste of business logic, don't keep both.
- No dead or commented-out code. No `TODO` without a linked GitHub issue.
- Guard clauses and early returns over deep nesting.
