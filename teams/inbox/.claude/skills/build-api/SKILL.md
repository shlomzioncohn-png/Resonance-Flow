---
name: build-api
description: Generate a full REST API layer (Repository, Exceptions, DTOs, Mapper, Service, Controller, Tests, and — where the entity needs it — role-scoped Authorization) for an existing JPA entity in teams/inbox, following the exact conventions established by the User API (layering, DTO/error contract, Swagger, actor identification, audit logging, role-based scoping). Invoke as "/build-api <entity>", e.g. "/build-api team" or "/build-api task". The entity name is the only argument.
---

# build-api

Generates the REST API layer for one entity in `teams/inbox`, replicating the standard already
implemented for `User` (see `src/main/java/com/rnd/inbox/user/User*`). This skill does not invent
a new standard — it applies the one that already exists, and asks only when a real,
entity-specific decision is required.

The argument is the entity name (e.g. `team`, `task`). Match it **literally** against the spec —
do not guess synonyms (e.g. `tenant` is not `organization` — that rename already happened once,
see below; if the literal name isn't found, stop and say so, and suggest the user confirm the
intended entity).

## Reference implementation — read these first, every time

Before generating anything, re-read the actual `User` files to refresh on the current shape of
the convention (it has evolved more than once since this skill was written — package layout,
partial-update semantics, and authorization were all reworked after the first version of this
skill shipped, so do not trust memory of "how User looks," re-read it):

```
user/User.java
user/UserRepository.java
user/{UserNotFoundException,DuplicateEmailException,DuplicateUsernameException}.java
user/UserRole.java                          (only if the entity needs its own enum — see below)
user/UserMapper.java
user/UserService.java
user/UserAuthorization.java, user/UserScope.java   (the role-scoping pattern — see below)
user/UserController.java
user/{UserCreateRequestDto,UserUpdateRequestDto,UserStatusUpdateRequestDto,UserResponseDto}.java
src/test/java/.../user/{UserServiceTest,UserAuthorizationTest,UserMapperTest,UserControllerTest}.java
```

Also re-read `CLAUDE.md` (§5-§14) — it governs layering, naming, REST rules, validation,
exceptions, logging, and (§5) the **package-by-feature** layout: every class for one entity lives
flat in one top-level package named after it (`user/`, `team/`, `tenant/`, `task/`, ...), not
split across `controller/`/`service/`/`repository/`/etc. Cross-cutting infrastructure lives under
`shared/`. This skill's rules below are the `User`-specific lessons on top of `CLAUDE.md`;
`CLAUDE.md` still wins on anything not covered here.

**Naming has moved once already** — `Organization` was renamed to `Tenant` mid-project
(`OrganizationNotFoundException` → `TenantNotFoundException`, `organization_id` → `tenant_id`,
etc.). If you find a stale reference to the old name anywhere while generating, that's a sign this
skill (or the code you're reading) is out of date — flag it, don't silently perpetuate it.

## Shared infrastructure — never recreate, always reuse

These already exist and are generic (not `User`-specific). Never generate a new version of any of
them, no matter which entity is being built:

```
shared/dto/ErrorResponseDto.java, shared/dto/FieldErrorDto.java, shared/dto/PageResponseDto.java
shared/exception/InboxException.java, shared/exception/GlobalExceptionHandler.java
shared/exception/ForbiddenActionException.java, shared/exception/UnauthenticatedException.java
shared/security/CurrentActor.java, shared/security/CurrentActorArgumentResolver.java,
    shared/security/ActorHeaderExtractor.java (shared X-Actor-Id parsing)
shared/security/RequireRole.java, shared/security/RoleAuthorizationInterceptor.java (the coarse,
    controller-level role gate — see "Actor, Audit, and Role-based Authorization" below; distinct
    from the per-entity {Entity}Scope/{Entity}Authorization fine-grained scoping)
shared/config/WebMvcConfig.java (registers both the actor-argument resolver and the role
    interceptor — it is constructor-injected with UserRepository for the latter; any
    @WebMvcTest slice that @Imports it now needs @MockBean UserRepository too, not just the
    entity's own Service — see "Tests" below), shared/config/AppConfig.java (Clock +
    JsonNullableModule beans)
shared/config/OpenApiConfig.java (the OperationCustomizer that auto-fills 400/500 and error schemas)
shared/config/JsonNullableValueExtractor.java + its registration in
    src/main/resources/META-INF/services/jakarta.validation.valueextraction.ValueExtractor
    (lets @Size/@Email see through JsonNullable<T> — see "Partial-update DTOs" below)
shared/messaging/validation/ValidEnum.java (+ ValidEnumValidator)
validation/OptionalNotBlank.java (+ OptionalNotBlankValidator)  — note: this one lives directly
    under com.rnd.inbox.validation, not under shared/ — an existing inconsistency, don't "fix" it
    silently while building an unrelated entity, just match it as-is
audit/AuditService.java
```

If the entity being built references another entity that already has its own `XNotFoundException`
or `XSummaryDto` (e.g. `TenantNotFoundException`, `TenantSummaryDto`), reuse those too — do not
create a duplicate.

## Step 1 — Discovery (no writing, no questions yet)

1. Confirm `{entity}/{Entity}.java` exists (package-by-feature — the entity's own top-level
   package). If it does not, **stop** — this skill does not create entities. Report that the
   entity is missing and do nothing else.
2. Read the entity's section in `docs/Inbox_Data_Schema_Specification.md` (field names, types,
   required/optional, `UNIQUE` markers, FK relationships, enum values).
3. Scan the whole `teams/inbox` source tree (not just the files this run would create) for
   anything already touching this entity or its relationships — existing repository, DTOs,
   exceptions, a partial controller, a related `SummaryDto`. Never assume a clean slate.
4. For each of the open questions below, decide whether the spec/existing code already makes the
   answer unambiguous. If so, resolve it silently and move on — do not ask about it in Step 2.

### Open questions checklist (ask only the ones that are genuinely ambiguous)

1. Which fields need a `Duplicate{Field}Exception` (uniqueness check)? — driven by `UNIQUE` /
   `Recommended UNIQUE` markers in the spec, and by any real DB unique index/constraint already in
   the migrations (`shared/database/migrations`) — the two must agree; if the spec says `UNIQUE`
   but no DB constraint backs it, that's worth flagging, not silently coding around (see
   "Uniqueness" below for why the DB constraint is the one that actually matters).
2. Does the entity have a `status` enum? If so, is a separate `PATCH /status` endpoint wanted, or
   is status change part of the regular update?
3. Which endpoints actually make sense for this entity? (e.g. an append-only log has no `Update`.)
4. Does the entity reference another entity that must appear as more than an id in the response
   (needs a `SummaryDto`)? Does that `SummaryDto` already exist?
5. What is the default value of `status` (or any other field the client doesn't send) on create? —
   this was a real product decision for `User` (`ACTIVE`), never assume it transfers to another
   entity.
6. **Does this entity need role-based access scoping at all** — and if so, what does each
   `UserRole` mean for *this* entity? **Never invent this mapping — ask**, unless the spec or an
   explicit prior decision already settles it. Phrase the question as a concrete choice, not an
   open essay prompt: offer "same as `User`" (`SYSTEM_ADMIN` everyone / `TENANT_ADMIN` their
   tenant / `TEAM_LEAD` their teams / `EMPLOYEE` themselves) as one option, since it is very often
   the right answer and re-deriving it from scratch every time is wasted effort — but never assume
   it silently. A `Task`, for instance, might scope `TEAM_LEAD` to tasks owned by their team rather
   than to tasks touching team members, or might not restrict `GET` at all — real alternatives the
   question should leave room for, not edge cases to bury in a footnote. If the entity has no
   actor-facing access concern at all (e.g. a pure lookup table), that's also a valid answer — say
   so explicitly and skip building `{Entity}Authorization` entirely rather than building a hollow
   one.
7. Are there business-rule validations beyond generic CRUD (uniqueness, existence, scoping) that
   this entity needs?
8. Does this entity support a real `DELETE`, or — like `User` — is "deletion" a status change
   (`DISABLED`)? If there is no `status` field at all, this must be resolved explicitly (real
   `DELETE`, or some other mechanism) rather than defaulting either way.
9. **Does any endpoint need the coarse, controller-level `@RequireRole` gate** — a plain "only
   these roles may call this endpoint at all," checked before the Controller method ever runs?
   This is a **separate decision from Q6**: Q6 is about *data-level* scoping (which records of
   this entity the actor may see/touch — enforced in the Service via `{Entity}Authorization`);
   `@RequireRole` is a *role-only* pre-check with no data dependency at all (enforced by
   `RoleAuthorizationInterceptor` before the Controller method runs). An endpoint can need one,
   both, or neither — `User.createUser` needs `@RequireRole({SYSTEM_ADMIN, TENANT_ADMIN})`
   *because* "can this role create users anywhere" has no per-record answer to defer to the
   Service; a `GET` whose only restriction is "within the actor's own tenant" needs no
   `@RequireRole` at all — `{Entity}Authorization` already covers it, and gating it again at the
   Controller would be redundant, not extra-safe. **Never add `@RequireRole` reflexively to every
   endpoint just because the entity has role scoping** — ask, per endpoint, whether a role-only
   (no-data) precondition genuinely exists; most endpoints won't have one.

## Step 2 — One consolidated question (only if anything is still open)

If every question above resolved unambiguously in Step 1, skip straight to Step 3 — do not ask
anything just to ask. Otherwise, ask **all** remaining open questions **together, in a single
message** — never drip them out one at a time.

## Step 3 — Plan, then one approval gate

Before writing any file, present a short plan:
- which files will be **created** (grouped by layer, per the list below)
- which existing files will be **reused as-is**
- which existing files will be **modified** (and exactly what changes — modifying an existing file
  is never silent, regardless of how obvious the change seems)
- the answers/decisions from Steps 1-2, **including the role-scoping decision from Q6** even when
  the answer is "this entity needs none", **and the coarse `@RequireRole` decision from Q9**, per
  endpoint (which endpoints get it, with which roles, and why the others don't)

Wait for approval before writing anything. This is the one required stop before generation.

## Step 4 — Build (one continuous pass, no per-file pauses)

Once approved, generate in this order, applying every rule in "Fixed rules" below. Do not stop
between files — the approval in Step 3 already covers all of it. The only thing that reopens a
stop mid-build is discovering an existing file that needs a real decision to modify (not just
complete) — that still interrupts the pass.

```
1. {entity}/{Entity}Repository.java
2. {entity}/{Entity}NotFoundException.java  (+ Duplicate*Exception per Q1, + a referenced-entity
   NotFoundException per Q4 — only the ones that don't already exist)
3. {entity}/{Entity}CreateRequestDto.java, {Entity}UpdateRequestDto.java
   {entity}/{Entity}StatusUpdateRequestDto.java   (only if Q2 says so)
   {entity}/{Entity}ResponseDto.java
   {relatedEntity}/{RelatedEntity}SummaryDto.java  (only per Q4, and only if it doesn't already exist)
4. {entity}/{Entity}Mapper.java
5. {entity}/{Entity}Scope.java, {entity}/{Entity}Authorization.java   (only if Q6 says this entity
   needs role scoping — see "Role-based authorization" below; skip entirely otherwise)
6. {entity}/{Entity}Service.java
7. {entity}/{Entity}Controller.java (apply `@RequireRole(...)` per the Q9 decision, per endpoint —
   most endpoints will have none)
8. Tests: {Entity}ServiceTest, {Entity}AuthorizationTest (only if step 5 ran), {Entity}MapperTest,
   {Entity}ControllerTest
   (Repository gets no test unless it carries a custom @Query — a role-scoping query like User's
   findAllByTeamLeadId always does, so it doesn't need a separate test beyond what
   {Entity}AuthorizationTest and {Entity}ServiceTest already exercise through it.)
```

### Fixed rules (do not re-derive or re-litigate these — they are settled)

**Layering**
- `Controller → Service` only, never `Repository` directly.
- `Mapper → DTO, Entity` only, **never `Repository`**. If create/update needs a referenced entity
  (like `User` needs `Tenant`), the **Service** resolves it via
  `repository.findById(id).orElseThrow(() -> new XNotFoundException(id))` — never
  `getReferenceById`, which doesn't check existence and defers a foreign-key failure to an
  unrelated, unhandled `500` at save time (`getReferenceById` is fine in a different context —
  event consumers linking an already-validated reference, e.g. `StructuredTaskMapper` — but never
  for a client-supplied id in this skill's endpoints). The Service passes the already-resolved
  entity into the Mapper as a parameter.

**DTOs**
- Separate `CreateRequestDto` / `UpdateRequestDto` / `ResponseDto` — never one DTO for multiple
  purposes.
- An enum-shaped field on a **request** DTO is `String` + `@ValidEnum(enumClass = ...)`, never the
  enum type directly — a native enum field fails deserialization itself
  (`HttpMessageNotReadableException`, an unrelated exception type that falls through to `500`)
  before `@Valid` ever runs. A **response** DTO field uses the real enum type directly (Jackson
  serializes it fine, and it's type-safe).
- `@Size(max = N)` on every text field, matching the column length in the spec exactly.

**Partial-update DTOs — three-state fields, not just "nullable"**
- `UpdateRequestDto` is partial-merge, not full-replace, but a plain nullable field can only tell
  "omitted" from "sent" — it cannot tell "sent as null, to deliberately clear the field" from
  "omitted." For any field where clearing it to `null` is a real, intended action (most optional
  text fields — `firstName`, `email`, `phone`, ...), wrap it in `JsonNullable<String>` (defaulted
  to `JsonNullable.undefined()`), and in the Mapper's `applyUpdate`:
  `if (dto.getX().isPresent()) { entity.setX(dto.getX().get()); }` — `isPresent()` is true both
  for an explicit value *and* an explicit `null`, so this correctly clears the field when the
  client sends `"x": null` while leaving it untouched when `x` is omitted entirely.
- For the handful of fields where a deliberate `null` makes no sense (`User.displayName`,
  `User.role` — you don't "clear" someone's role), keep the field a plain `String`
  (`JsonNullable` would be pointless complexity) with `@OptionalNotBlank` instead of `@NotBlank`:
  `null` passes (omitted, leave as-is), but an explicit `""` or whitespace-only value is rejected
  with `400` rather than silently wiping the field — the plain `if (dto.getX() != null)` in the
  Mapper is correct here specifically *because* `@OptionalNotBlank` already ruled out the
  ambiguous blank-string case before the Mapper ever sees it.
- Whichever fields end up `JsonNullable<String>`, `@Size`/`@Email` on them only actually validate
  because of the `JsonNullableValueExtractor` registered in `shared/config` + its
  `META-INF/services` entry (listed under "Shared infrastructure" above) — don't skip registering
  a new entity's DTO against it; there's nothing *to* register per-entity, this extractor already
  applies to every `JsonNullable<T>` field in the module, but it's worth knowing why the
  validation "just works" instead of assuming it's automatic for any wrapper type.
- Shared DTOs (`ErrorResponseDto`, `PageResponseDto<T>`, any reused `SummaryDto`) live in the flat
  `shared/dto/` package, never duplicated per entity.

**Errors**
- Every new exception extends `InboxException`, passing `message`, an `UPPER_SNAKE_CASE`
  errorCode, and an `HttpStatus` to its constructor. Never touch `GlobalExceptionHandler` — it
  already handles any `InboxException` subclass generically.

**Uniqueness — the pre-check alone is not enough**
- A `SELECT`-then-`INSERT`/`UPDATE` uniqueness pre-check (`assertXAvailable`, querying by the
  unique field before saving) has a real race window: two concurrent requests can both pass the
  pre-check before either has saved. Keep the pre-check (it gives a fast, precise `409` on the
  common non-racing path) but it is **not sufficient by itself** — the database's own unique
  constraint is what actually stops the race, and the save path must translate its rejection
  rather than let it fall through to a raw `500`. Wrap the repository `save(...)` call:
  ```java
  private {Entity} save{Entity}({Entity} entity) {
      try {
          return {entity}Repository.save(entity);
      } catch (DataIntegrityViolationException e) {
          throw translateUniqueConstraintViolation(entity, e);
      }
  }
  ```
  and translate by walking the cause chain for Hibernate's `org.hibernate.exception.
  ConstraintViolationException` (not the same class as Bean Validation's
  `jakarta.validation.ConstraintViolationException` — don't confuse them) and matching
  `getConstraintName()` against the actual DB constraint name from the migration. An unrecognized
  constraint name should be rethrown as-is, not guessed at — see `User.saveUser` /
  `translateUniqueConstraintViolation` / `extractConstraintName` for the exact shape to copy.

**Swagger**
- Success responses (2xx) get an explicit `@Content(schema = @Schema(implementation = ...))`
  matching the real DTO.
- Error responses only need `responseCode` + `description` — no `@Content`. The global
  `OperationCustomizer` (`shared/config/OpenApiConfig.java`) fills in the `ErrorResponseDto`
  schema automatically for anything without an explicit `application/json` content, and adds
  `400`/`500` to every operation automatically. Add `401` explicitly on every endpoint that takes
  `@CurrentActor` (that's now all of them if the entity has role scoping — see below), and `403`
  explicitly on every endpoint that can throw `ForbiddenActionException` — from *either* layer
  (`@RequireRole` and `{Entity}Authorization` both throw the same exception type, so the wire
  contract doesn't distinguish them, but the description should still say what actually triggers
  it). Write what the 403 actually means for *this* entity's rules — a role-only reason
  (`@RequireRole`) if that's the only gate on this endpoint, a scoping reason
  (`{Entity}Authorization`) if that's the only gate, or both spelled out if the endpoint has both —
  don't leave a generic "outside their tenant" description once role-scoping is richer than that
  (that exact staleness happened for `User` and had to be fixed after the fact).

**Actor, Audit, and Role-based Authorization**

`@CurrentActor` itself only resolves a `UUID` (`actorId`) — nothing more. The actor's *identity*,
though, is always a `User` row: even when building the API for a different entity, the Service
looks `actorId` up via `UserRepository.findById(actorId)` to get the full `User`, and that row's
`role` is the same `UserRole` enum (`SYSTEM_ADMIN`/`TENANT_ADMIN`/`TEAM_LEAD`/`EMPLOYEE`) every
other entity's authorization is built on. **Never invent a second role enum for a new entity.**

- `@CurrentActor UUID actorId` is a controller-method parameter on **every** endpoint that needs
  an identity — that now includes plain `GET`s, not just `create`/`update`, whenever the entity
  has role scoping (Q6). An endpoint with no scoping at all (e.g. a global read-only lookup table)
  can skip it.
- The Service resolves the actor via
  `userRepository.findById(actorId).orElseThrow(() -> new UnauthenticatedException(...))`
  **before** any other logic (fail-fast) — copy `User.findActorOrThrow` verbatim, don't
  re-implement it inside the new entity's service (inject `UserRepository` into the new Service
  for this one lookup even if the entity itself has its own repository).
- `create`, `update`, **and any status-change / sub-resource-action endpoint** (not just
  create/update — `User.updateStatus` writes an audit entry too, this was a real gap fixed after
  the fact) call
  `auditService.createAuditLog("{ENTITY}_CREATED" / "{ENTITY}_UPDATED" / "{ENTITY}_{ACTION}",
  "{Entity}", entityId, tenant, actor, snapshotMap, null)` — `correlationId` is always `null` for
  this manually-triggered (non-event-driven) path.

**Coarse (`@RequireRole`) vs. fine (`{Entity}Authorization`) — two independent layers, not a choice
between them**

Both can apply to the same endpoint, each answering a different question:

| | `@RequireRole` (coarse) | `{Entity}Authorization` (fine) |
|---|---|---|
| Question it answers | "Can this **role** ever call this endpoint?" | "Can this **actor** act on **this specific record/tenant/team**?" |
| Depends on | Nothing but the actor's `role` | The target record, tenant, team — actual data |
| Enforced by | `RoleAuthorizationInterceptor`, before the Controller method runs | `{Entity}Service`, via `{Entity}Authorization`, mid-method |
| Applies via | `@RequireRole({UserRole.X, ...})` on the Controller method | Constructor-injected into the Service, called explicitly |

Use `@RequireRole` only for a restriction with **no data dependency** — "TEAM_LEAD and EMPLOYEE
can never create a user, full stop, regardless of tenant" is role-only, so `User.createUser` has
it. Do not use it as a substitute for `{Entity}Authorization` — "TENANT_ADMIN can only manage
their own tenant" depends on *which* tenant, so that belongs in `{Entity}Authorization`, not in
`@RequireRole` (an endpoint gated only by `@RequireRole({TENANT_ADMIN})` with no follow-up scope
check would let any `TENANT_ADMIN` act on *any* tenant — a real authorization hole, not a
simplification). Decide per endpoint, per Q9 above; don't add `@RequireRole` reflexively wherever
`{Entity}Authorization` already exists — most endpoints need only the fine-grained check.

When Q9 says an endpoint needs it: annotate the Controller method
(`@RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN})`, mirroring `User.createUser`) —
nothing else to wire, `WebMvcConfig` already registers the interceptor globally, and a method with
no `@RequireRole` is simply unaffected by it (this is opt-in, not deny-by-default: an endpoint
this skill forgets to annotate is not thereby blocked, so getting Q9's answer right matters — it
is not a safety net you can rely on to fail closed).

**Role-based authorization (only if Q6 said this entity needs it)**

Follow `UserScope`/`UserAuthorization` exactly:
- `{Entity}Scope`: a `sealed interface` of `record`s, **pure data, no methods** — it describes
  *what the actor is limited to*, not how to act on it. What "belongs to the actor" means depends
  on the operation (list vs. check-one-existing vs. create), and that differs by entity, so don't
  copy `User`'s four variants (`All`/`WithinTenant`/`WithinTeamsLedBy`/`SelfOnly`) blindly — derive
  them from what Q6 actually decided for this entity. (A `Task` scoped by ownership might look
  different from one scoped by team assignment, for instance.)
- `{Entity}Authorization` (a `@Component`, constructor-injected into `{Entity}Service` the same
  way `{Entity}Mapper`/`AuditService` are): `scopeOf(User actor)` — the **only** place that
  switches on `UserRole` — plus whichever of `assertWithinScope(scope, target)` (existing-record
  checks: get/update/status) and `assertCanCreateIn(scope, ...)` (creation-destination check,
  shape depends on what "destination" means for this entity — a tenant, a team, something else)
  the entity's endpoints actually need. Every switch over the sealed `{Entity}Scope` is exhaustive
  by construction (the compiler enforces it, no `default` branch) — keep it that way; don't add
  one just to silence a warning.
- Any scope variant backed by a real relationship (e.g. "on a team led by X") gets a **query-level**
  repository method — an `EXISTS`-shaped check for `assertWithinScope`
  (`{Entity}Repository.existsInXLedBy(...)`, mirroring `UserRepository.existsInTeamLedBy`) and a
  filtered list query for the list endpoint (`{Entity}Repository.findAllByXLedBy(...)`, mirroring
  `findAllByTeamLeadId`) — never `findAll()` followed by filtering the results in Java.
- Write `{Entity}AuthorizationTest` as its own file, independent of `{Entity}ServiceTest` — mock
  only what `{Entity}Authorization` itself depends on (typically just the repository), and cover
  every `(scope variant) × (allowed/forbidden)` combination directly, the same shape as
  `UserAuthorizationTest`. In `{Entity}ServiceTest`, construct a **real** `{Entity}Authorization`
  (not a mock) wired to the same mocked repository — that way the service tests keep asserting on
  actual outcomes (200 vs 403) instead of verifying that a mock was called, and stay correct
  automatically if the authorization logic itself is refactored later.

**REST**
- `/api/v1/{entities}` — plural, kebab-case.
- Non-CRUD actions (like a status change) are a sub-resource path (`/{id}/status`), never a verb.

**Tests**
- Naming: `methodName_condition_expectedResult`.
- `{Entity}ServiceTest`: Mockito, mocks `Repository`/`Mapper`/`AuditService` (and any other
  repositories the service needs, e.g. `UserRepository` for actor resolution), a **real**
  `{Entity}Authorization` where one exists (see above), AssertJ.
- `{Entity}ControllerTest`: `@WebMvcTest({Entity}Controller.class)`, `@MockBean` the Service,
  `@Import({AppConfig.class, WebMvcConfig.class})` (a plain `@WebMvcTest` slice does not pick up
  `@Configuration` classes on its own — both `Clock` and the actor resolver need this; both
  classes are under `shared.config`). **Also `@MockBean UserRepository`** — `WebMvcConfig` is
  constructor-injected with it (for `RoleAuthorizationInterceptor`), so the slice context fails to
  load without this mock even if the entity has no role scoping of its own at all; in `setUp()`,
  stub `userRepository.findById(actorId)` to return a `User` with a permitted role by default (see
  `UserControllerTest.setUp()`) so tests unrelated to `@RequireRole` aren't incidentally blocked by
  it — only the tests that specifically target `@RequireRole` should override that stub.
- Cover, at minimum: happy path, validation failure (400), not-found (404), conflict (409) if
  applicable, missing/invalid actor (401) on every endpoint that takes `@CurrentActor`, a
  partial-merge test proving an omitted field is preserved *and* (for any field wrapped in
  `JsonNullable`) a separate test proving an explicit `null` clears it, and — if the entity has
  role scoping — a 403 case for every scope variant that can legitimately deny access on that
  endpoint, not just one representative case. For any endpoint carrying `@RequireRole` (Q9), also
  cover: a forbidden role gets 403 **and never reaches the Service**
  (`verify(service, never()).theMethod(...)`), and an actor id unknown to `UserRepository` gets 401
  — mirror `UserControllerTest`'s `createUser_whenActorRoleIs*_returns403WithoutCallingService` /
  `createUser_whenActorIdUnknownToRepository_returns401`.

## Step 5 — Verify and report

1. Compile (main + test sources).
2. Run the new test classes for real — a green compile is not sufficient, the tests must pass.
3. Report: files created, files reused as-is, files modified (and why), and every decision made in
   Steps 1-2 — including, explicitly, what Q6 (role scoping) and Q9 (`@RequireRole`) each resolved
   to and why.
