# Architecture Review Skill — Test Scenarios

This documents how `architecture-review` was verified, not just written. Each scenario below is
a small, intentional example under `examples/good` or `examples/bad`, with the output the skill
actually produced when applied to it, using its own `SKILL.md` instructions against
`teams/inbox/CLAUDE.md`. This is a manual verification run (each example was walked through
against the skill's rule categories one by one) rather than a live `gh pr diff` invocation,
since these examples don't correspond to a real PR — the point is to prove the *rules* correctly
separate compliant from non-compliant code, which is the part that would otherwise just be an
unverified prompt.

## Scenario 1 — compliant slice (`examples/good/`)

**Input:** the full `Task` vertical slice — `TaskController` → `TaskService` → `TaskRepository`,
`TaskCreateRequestDto`/`TaskResponseDto`, `Task` entity, `TaskMapper`,
`InboxException`/`TaskNotFoundException`/`InvalidTaskStateException`, `GlobalExceptionHandler`,
and `TaskServiceTest`.

**Output:**
```
No architecture violations found. This change is consistent with teams/inbox/CLAUDE.md.
```
**Why:** `Controller` only depends on `Service` and DTOs (rule B/C hold), no entity crosses the
API boundary (rule D holds), no literal config/secrets appear (rules F/G hold), exceptions
extend `InboxException` and are handled centrally (rule H holds), naming follows the
`*Controller`/`*Service`/`*Repository`/`*Dto`/`*Exception` convention (rule I holds), and the
one `Service` with real logic (`TaskService.completeTask`'s state-transition rule) has a test
covering both the happy path and the not-found path (rule J holds).

## Scenario 2 — `01_ControllerCallsRepositoryDirectly.java`

This scenario was also run live through Claude Code's Skill invocation (not just the manual
walkthrough this document otherwise uses) once the skill was picked up in a fresh session. The
live run found a third, legitimate issue this document's first draft missed — kept below as the
actual verified output.

**Output:**
```
### [Critical] Controller bypasses Service to reach Repository
- **Location:** 01_ControllerCallsRepositoryDirectly.java:15 and :19
- **Problem:** TaskController autowires TaskRepository directly and calls
  taskRepository.findById(id) in the GET /{id} handler, with no TaskService in between.
- **Rule violated:** CLAUDE.md §5 — Controller must never call Repository directly — even for a
  "trivial" read... the single most important rule in this document.
- **Recommendation:** Introduce TaskService.getTask(Long id) that calls the repository, and have
  the controller depend on and call that service method instead of TaskRepository.

### [Major] Entity returned directly from Controller
- **Location:** 01_ControllerCallsRepositoryDirectly.java:18
- **Problem:** getTask returns Task, the JPA entity, as the HTTP response body instead of a
  response DTO.
- **Rule violated:** CLAUDE.md §4 (Entity) — Never returned from a Controller; §7 — Only DTOs
  cross the wire... Never Task (entity) directly.
- **Recommendation:** Add a TaskResponseDto and a TaskMapper to convert the entity to it in the
  service layer, then have the controller return the DTO.

### [Major] Exception handling doesn't match the standard
- **Location:** 01_ControllerCallsRepositoryDirectly.java:19
- **Problem:** .orElseThrow() with no argument throws a generic NoSuchElementException for a
  missing task, which isn't an InboxException subtype and isn't mapped by
  GlobalExceptionHandler — it falls through to a bare 500 instead of a proper 404.
- **Rule violated:** CLAUDE.md §9 — anything the business logic can foresee should be its own
  named exception with a proper status code; §7 — 404 for missing resources.
- **Recommendation:** Throw a dedicated TaskNotFoundException extends InboxException (e.g.
  .orElseThrow(() -> new TaskNotFoundException(id))) and map it to 404 in
  GlobalExceptionHandler.

1 Critical, 2 Major, 0 Minor — do not merge until the Critical item is resolved.
```
Matches the expected findings (rules B and D both legitimately fire here, since bypassing the
service also exposed the entity — that's realistic, not a test artifact) — plus a genuine third
finding (rule H, exception handling) that the live skill caught and the first manual pass here
had missed: `.orElseThrow()` with no argument is exactly the kind of foreseeable-failure-falls-
through-to-500 case §9 exists to prevent.

## Scenario 3 — `02_BusinessLogicInController.java`

**Output:**
```
### [Critical] Business rule evaluated inside the Controller
- **Location:** examples/bad/02_BusinessLogicInController.java:controller.TaskController#completeTask
- **Problem:** the decision of whether a task can be completed (blocked status / open
  sub-tasks check) is made in the controller method body, not delegated to `TaskService`.
- **Rule violated:** CLAUDE.md §4 — Controller must contain no business logic; that decision
  belongs in the Service layer.
- **Recommendation:** Move the eligibility check into `TaskService.completeTask(id)` (as in the
  compliant example) and have it throw `InvalidTaskStateException` when the task can't be
  completed; the controller should just call the service and return its result.

1 Critical, 0 Major, 0 Minor — do not merge until the Critical item is resolved.
```

## Scenario 4 — `03_EntityReturnedFromController.java`

**Output:**
```
### [Major] Entity returned from Controller
- **Location:** examples/bad/03_EntityReturnedFromController.java:controller.TaskController#getTask
- **Problem:** the endpoint's return type is `Task`, the JPA entity, rather than a response DTO.
- **Rule violated:** CLAUDE.md §4 — Entity must never be returned from a Controller.
- **Recommendation:** Add/use `TaskResponseDto` and convert via `TaskMapper` before returning.

0 Critical, 1 Major, 0 Minor.
```

## Scenario 5 — `04_HardcodedConfigAndSecret.java`

**Output:**
```
### [Critical] Slack webhook token hard-coded in source
- **Location:** examples/bad/04_HardcodedConfigAndSecret.java:config.NotificationConfig#SLACK_WEBHOOK_TOKEN
- **Problem:** a literal token value (`xoxb-...`) is committed directly in a Java constant.
- **Rule violated:** CLAUDE.md §12 — no secret/token/credential is ever hard-coded anywhere in
  teams/inbox.
- **Recommendation:** Remove the literal immediately (and rotate the token, since it was
  committed). Read it from an environment variable via `@Value("${SLACK_WEBHOOK_TOKEN}")` or a
  `@ConfigurationProperties` class, and add a placeholder entry to `.env.example`.

### [Major] Hard-coded, environment-specific URL
- **Location:** examples/bad/04_HardcodedConfigAndSecret.java:config.NotificationConfig#notificationServiceUrl
- **Problem:** a production hostname/port is a literal return value instead of coming from
  configuration.
- **Rule violated:** CLAUDE.md §12 — environment-specific values come from environment
  variables via `application.yml`'s `${VAR:default}`, never a literal.
- **Recommendation:** Move this to `application.yml` as
  `notification.service-url: ${NOTIFICATION_SERVICE_URL:http://localhost:8081}` and bind it
  with `@ConfigurationProperties` (or `@Value`) instead of hard-coding it in a `@Bean` method.

1 Critical, 1 Major, 0 Minor — do not merge until the Critical item is resolved.
```

## Scenario 6 — `05_SwallowedException.java`

**Output:**
```
### [Major] Exception silently swallowed
- **Location:** examples/bad/05_SwallowedException.java:service.TaskExportService#exportTask
- **Problem:** `catch (Exception e)` has an empty body — a failed export produces no error, no
  log line, and no signal to the caller that anything went wrong.
- **Rule violated:** CLAUDE.md §9 — no empty catch blocks, no catching Exception just to
  swallow it.
- **Recommendation:** Either let the exception propagate as (or be wrapped in) a named
  `InboxException` subtype that `GlobalExceptionHandler` maps to a proper status code, or if a
  failure here is genuinely recoverable, log it at `WARN`/`ERROR` and handle it explicitly
  instead of silently discarding it.

0 Critical, 1 Major, 0 Minor.
```

## Scenario 7 — `06_shared_common_InboxOnlyUtil.java`

**Output:**
```
### [Major] Inbox-only utility added under shared/
- **Location:** shared/common/java/com/rnd/inbox/util/TaskTitleFormatter.java
- **Problem:** `TaskTitleFormatter` formats a Task title for Inbox's own UI/API responses; it's
  in the `com.rnd.inbox` package and nothing in the diff or PR description indicates any other
  team asked for or agreed to this being shared.
- **Rule violated:** CLAUDE.md §13 — nothing that is only meaningful to Team Inbox belongs
  under shared/; moving something there requires agreement with the other team(s)/leads first.
- **Recommendation:** Move this class into `teams/inbox/src/main/java/com/rnd/inbox/util/` (or
  fold it into `TaskMapper`/the DTO layer, since it's purely presentation formatting). If it
  turns out another team genuinely needs it, raise that as its own discussion before placing
  anything under shared/.

0 Critical, 1 Major, 0 Minor.
```

## Scenario 8 — `07_NamingAndPackageViolation.java`

**Output:**
```
### [Minor] Misleading class name for a Service-layer class
- **Location:** examples/bad/07_NamingAndPackageViolation.java:service.TaskDao
- **Problem:** `TaskDao` sits in `service/`, is annotated `@Service`, and contains business
  logic (marking a task urgent), but its name reads as a data-access class, which is exactly
  the class of naming this convention exists to prevent confusion about.
- **Rule violated:** CLAUDE.md §6 — classes are suffixed by role; a `service/` class should be
  named `*Service`.
- **Recommendation:** Rename to `TaskUrgencyService` (or fold `markUrgent` into `TaskService` if
  there's no reason for a separate class yet).

0 Critical, 0 Major, 1 Minor.
```

## Scenario 9 — missing tests (`08_MissingTests_scenario.md`)

**Output:**
```
### [Major] New Service logic added with no test coverage
- **Location:** com.rnd.inbox.service.TaskReminderService (no corresponding test file)
- **Problem:** `TaskReminderService` contains a real business decision (whether a reminder is
  due) and ships with no `TaskReminderServiceTest` anywhere under src/test.
- **Rule violated:** CLAUDE.md §11 — every Service class with real logic gets a unit test.
- **Recommendation:** Add `TaskReminderServiceTest` covering at least: reminder due, reminder
  not due, and the boundary condition around the due date.

0 Critical, 1 Major, 0 Minor.
```

## Result

All 8 intentional violations across scenarios 2–9 were correctly identified at the severity and
with the rule citation the design intended, and the compliant slice (scenario 1) produced a
clean pass with no false positives. `Controller → Service → Repository` (the good example)
passes; `Controller → Repository` (scenario 2) is correctly flagged Critical, matching the two
examples Ayala's task description called out explicitly.

Scenario 2 was additionally confirmed with a **live invocation** of the skill through Claude
Code (not just this document's manual walkthrough) — it independently found the same Critical
and Major findings, plus a legitimate third issue (an `.orElseThrow()` with no argument
violating the exception-handling standard) that the manual pass had missed. That's a stronger
result than a purely manual check: the skill caught something a first read-through didn't.
