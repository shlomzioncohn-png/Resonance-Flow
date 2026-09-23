---
name: build-api
description: Generate a full REST API layer (Repository, Exceptions, DTOs, Mapper, Service, Controller, Tests) for an existing JPA entity in teams/inbox, following the exact conventions established by the User API (layering, DTO/error contract, Swagger, audit logging, actor identification). Invoke as "/build-api <entity>", e.g. "/build-api team" or "/build-api task". The entity name is the only argument.
---

# build-api

Generates the REST API layer for one entity in `teams/inbox`, replicating the standard already
implemented for `User` (see `src/main/java/com/rnd/inbox/{controller,service,mapper,repository,dto,exception}/User*`).
This skill does not invent a new standard — it applies the one that already exists, and asks only
when a real, entity-specific decision is required.

The argument is the entity name (e.g. `team`, `task`). Match it **literally** against the spec — do
not guess synonyms (e.g. `tenant` is not `organization`; if the literal name isn't found, stop and
say so, and suggest the user confirm the intended entity).

## Reference implementation — read these first, every time

Before generating anything, re-read the actual `User` files to refresh on the current shape of the
convention (it may have evolved since this skill was written):

```
model/entity/User.java
repository/UserRepository.java
exception/{UserNotFoundException,DuplicateEmailException,DuplicateUsernameException}.java
mapper/UserMapper.java
service/UserService.java
controller/UserController.java
dto/{UserCreateRequestDto,UserUpdateRequestDto,UserStatusUpdateRequestDto,UserResponseDto}.java
src/test/java/.../{service/UserServiceTest,mapper/UserMapperTest,controller/UserControllerTest}.java
```

Also re-read `CLAUDE.md` (§5-§14) — it governs layering, naming, REST rules, validation, exceptions,
logging. This skill's rules below are the `User`-specific lessons on top of it; `CLAUDE.md` still
wins on anything not covered here.

## Shared infrastructure — never recreate, always reuse

These already exist and are generic (not `User`-specific). Never generate a new version of any of
them, no matter which entity is being built:

```
dto/ErrorResponseDto.java, dto/FieldErrorDto.java, dto/PageResponseDto.java
exception/InboxException.java, exception/GlobalExceptionHandler.java
exception/ForbiddenActionException.java, exception/UnauthenticatedException.java
security/CurrentActor.java, security/CurrentActorArgumentResolver.java, config/WebMvcConfig.java
service/AuditService.java
config/OpenApiConfig.java (the OperationCustomizer that auto-fills 400/500 and error schemas)
```

If the entity being built references another entity that already has its own `XNotFoundException`
or `XSummaryDto` (e.g. `OrganizationNotFoundException`, `OrganizationSummaryDto`), reuse those too —
do not create a duplicate.

## Step 1 — Discovery (no writing, no questions yet)

1. Confirm `model/entity/{Entity}.java` exists. If it does not, **stop** — this skill does not create
   entities. Report that the entity is missing and do nothing else.
2. Read the entity's section in `docs/Inbox_Data_Schema_Specification.md` (field names, types,
   required/optional, `UNIQUE` markers, FK relationships, enum values).
3. Scan the whole `teams/inbox` source tree (not just the files this run would create) for anything
   already touching this entity or its relationships — existing repository, DTOs, exceptions,
   partial controllers, a related `SummaryDto`. Never assume a clean slate.
4. For each of the open questions below, decide whether the spec/existing code already makes the
   answer unambiguous. If so, resolve it silently and move on — do not ask about it in Step 2.

### Open questions checklist (ask only the ones that are genuinely ambiguous)

1. Which fields need a `Duplicate{Field}Exception` (uniqueness check)? — driven by `UNIQUE` /
   `Recommended UNIQUE` markers in the spec.
2. Does the entity have a `status` enum? If so, is a separate `PATCH /status` endpoint wanted, or is
   status change part of the regular update?
3. Which endpoints actually make sense for this entity? (e.g. an append-only log has no `Update`.)
4. Does the entity reference another entity that must appear as more than an id in the response
   (needs a `SummaryDto`)? Does that `SummaryDto` already exist?
5. What is the default value of `status` (or any other field the client doesn't send) on create? —
   this was a real product decision for `User` (`ACTIVE`), never assume it transfers to another
   entity.
6. Does `GET` (list) filter by anything (organization, status, ...), or return everything? — for
   `User` this was an explicit choice ("everything"), not a default.
7. Are there business-rule validations beyond generic CRUD (uniqueness, existence) that this entity
   needs?
8. Does this entity support a real `DELETE`, or — like `User` — is "deletion" a status change
   (`DISABLED`)? If there is no `status` field at all, this must be resolved explicitly (real
   `DELETE`, or some other mechanism) rather than defaulting either way.

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
- the answers/decisions from Steps 1-2

Wait for approval before writing anything. This is the one required stop before generation.

## Step 4 — Build (one continuous pass, no per-file pauses)

Once approved, generate in this order, applying every rule in "Fixed rules" below. Do not stop
between files — the approval in Step 3 already covers all of it. The only thing that reopens a stop
mid-build is discovering an existing file that needs a real decision to modify (not just complete) —
that still interrupts the pass.

```
1. repository/{Entity}Repository.java
2. exception/{Entity}NotFoundException.java  (+ Duplicate*Exception per Q1, + a referenced-entity
   NotFoundException per Q4 — only the ones that don't already exist)
3. dto/{Entity}CreateRequestDto.java, {Entity}UpdateRequestDto.java
   dto/{Entity}StatusUpdateRequestDto.java   (only if Q2 says so)
   dto/{Entity}ResponseDto.java
   dto/{RelatedEntity}SummaryDto.java        (only per Q4, and only if it doesn't already exist)
4. mapper/{Entity}Mapper.java
5. service/{Entity}Service.java
6. controller/{Entity}Controller.java
7. Tests: {Entity}ServiceTest, {Entity}MapperTest, {Entity}ControllerTest
   (Repository gets no test unless it carries a custom @Query.)
```

### Fixed rules (do not re-derive or re-litigate these — they are settled)

**Layering**
- `Controller → Service` only, never `Repository` directly.
- `Mapper → DTO, Entity` only, **never `Repository`**. If create/update needs a referenced entity
  (like `User` needs `Organization`), the **Service** resolves it via
  `repository.findById(id).orElseThrow(() -> new XNotFoundException(id))` — never
  `getReferenceById`, which doesn't check existence and defers a foreign-key failure to an
  unrelated, unhandled `500` at save time. The Service passes the already-resolved entity into the
  Mapper as a parameter.

**DTOs**
- Separate `CreateRequestDto` / `UpdateRequestDto` / `ResponseDto` — never one DTO for multiple
  purposes.
- An enum-shaped field on a **request** DTO is `String` + `@ValidEnum(enumClass = ...)`, never the
  enum type directly — a native enum field fails deserialization itself
  (`HttpMessageNotReadableException`, an unrelated exception type that falls through to `500`)
  before `@Valid` ever runs. A **response** DTO field uses the real enum type directly (Jackson
  serializes it fine, and it's type-safe).
- `@Size(max = N)` on every text field, matching the column length in the spec exactly.
- `UpdateRequestDto` is **partial-merge**, not full-replace: no field is `@NotBlank`/required on it
  (unless the team explicitly wants an exception for a specific field — ask, don't assume), and the
  Mapper's `applyUpdate` wraps every field in `if (dto.getX() != null) { entity.setX(...); }` so an
  omitted field keeps its existing value.
- Shared DTOs (`ErrorResponseDto`, `PageResponseDto<T>`, any reused `SummaryDto`) live in the flat
  `dto/` package, never duplicated per entity.

**Errors**
- Every new exception extends `InboxException`, passing `message`, an `UPPER_SNAKE_CASE` errorCode,
  and an `HttpStatus` to its constructor. Never touch `GlobalExceptionHandler` — it already handles
  any `InboxException` subclass generically.

**Swagger**
- Success responses (2xx) get an explicit `@Content(schema = @Schema(implementation = ...))`
  matching the real DTO.
- Error responses only need `responseCode` + `description` — no `@Content`. The global
  `OperationCustomizer` (`config/OpenApiConfig.java`) fills in the `ErrorResponseDto` schema
  automatically for anything without an explicit `application/json` content, and adds `400`/`500`
  to every operation automatically. Add `401` explicitly only on endpoints that take `@CurrentActor`.

**Actor + Audit** (always included for create/update, on every entity)
- `create`/`update` controller methods take `@CurrentActor UUID actorId` as a parameter (reusing the
  existing mechanism — do not build a new one).
- The Service resolves the actor via `userRepository.findById(actorId).orElseThrow(() -> new
  UnauthenticatedException(...))` **before** any other logic (fail-fast), then calls
  `auditService.createAuditLog("{ENTITY}_CREATED" / "{ENTITY}_UPDATED", "{Entity}", entityId,
  organization, actor, snapshotMap, null)` — `correlationId` is always `null` for this
  manually-triggered (non-event-driven) path.

**REST**
- `/api/v1/{entities}` — plural, kebab-case.
- Non-CRUD actions (like a status change) are a sub-resource path (`/{id}/status`), never a verb.

**Tests**
- Naming: `methodName_condition_expectedResult`.
- `{Entity}ServiceTest`: Mockito, mocks `Repository`/`Mapper`/`AuditService`, AssertJ.
- `{Entity}ControllerTest`: `@WebMvcTest({Entity}Controller.class)`, `@MockBean` the Service,
  `@Import({AppConfig.class, WebMvcConfig.class})` (a plain `@WebMvcTest` slice does not pick up
  `@Configuration` classes on its own — both `Clock` and the actor resolver need this).
- Cover, at minimum: happy path, validation failure (400), not-found (404), conflict (409) if
  applicable, missing/invalid actor (401) on create/update, and — for `UpdateRequestDto` — a
  partial-merge test proving an omitted field is preserved.

## Step 5 — Verify and report

1. Compile (main + test sources).
2. Run the new test classes for real — a green compile is not sufficient, the tests must pass.
3. Report: files created, files reused as-is, files modified (and why), and every decision made in
   Steps 1-2.
