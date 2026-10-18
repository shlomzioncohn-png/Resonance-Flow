# Chain — Context for Claude Code

## Task
Implementing dependency update flow: addDependency, removeDependency,
onTaskStatusChanged, recalculateTaskEligibility, onConstraintAdded,
onConstraintStatusChanged.

`onConstraintAdded(taskId, constraintId)` and `onConstraintStatusChanged(taskId, constraintId,
previousStatus, newStatus)` both take a `constraintId` now (2026-09-08, PLAN.md Finding #10) - a
per-task `Set<UUID>` of currently-open constraint ids (`openConstraintIdsByTask` in
TaskEligibilityService) makes a redelivered event a no-op instead of double-counting
openConstraintCount, mirroring the idempotency every other counter-mutating path in this class
already has (edgeExists() / the task's own stored previousStatus).

## Key decisions
- isReady = blockingCount==0 AND openConstraintCount==0
- Tasks CAN reopen from DONE (confirmed by team lead) - blockingCount
  must be restored on dependents when this happens
- Constraints CAN also reopen (PENDING -> DONE/WAIVED -> PENDING again)
- Cycle detection required in addDependency (full in-memory DFS - stack-
  based push/pop, no depth limit — not optimized yet, revisit only if
  it's a bottleneck)
- No retry logic (@Retryable) — let exceptions propagate to the caller
- Constraint persistence (issue #378, 2026-09-17 team-lead decision):
  Postgres via plain JDBC (JdbcTemplate), not JPA/Hibernate - Chain's
  repositories are already hand-written interfaces, not Spring Data, and
  the operations needed (upsert, atomic counter updates, row locking) are
  raw SQL anyway. Same decision applies to Task/TaskDependency once #377
  starts. Enums stored as VARCHAR + CHECK, not a native Postgres enum
  type (works the same regardless of persistence tech, simpler to change
  later). Table is `task_constraint`, not `constraint` - a reserved SQL
  keyword - matching this module's own `task_dependency` naming instead
  of Inbox's plural `tasks` convention.
- This branch (#378) writes Constraint persistence assuming #377 (Task/
  TaskDependency Postgres persistence) already landed, rather than
  building #377's task table ourselves - not our work to do. Accepted
  consequence: V1__create_task_constraint_table.sql's FK to task(id)
  won't apply, and PostgresConstraintRepositoryTest won't pass, until
  #377 actually lands. See README.md "Constraint persistence" for detail.
- Write order (implemented): persist to Postgres via ConstraintRepository
  before updating the in-memory Task counter, never the reverse. If the
  process fails in between, a redelivered event re-runs the (idempotent)
  repository call, which correctly reports "already there" / the real
  previous status, so the counter update proceeds correctly on retry.
  The reverse order is worse: a caller that skips the repository call
  once it believes an event was "already handled" can permanently lose
  a Constraint row instead of just delaying a counter update.

## Real repository contracts (from chain/ruthHoltzman/dependency-repository-66)
- TaskRepository: findById, findAllById, findByParentTaskId, save
  (no atomic increment yet — in-memory HashMap, not real DB)
- TaskDependencyRepository: findByTaskId, findByDependsOnTaskId, save
  (idempotent), delete (returns void, not a count)
- Proposed addition (pending team approval, NOT YET MERGED):
  incrementBlockingCount/decrementBlockingCount/
  incrementOpenConstraintCount/decrementOpenConstraintCount
- ConstraintRepository (issue #378, new): findByTaskId, findById,
  save (upsert by constraintId, returns whether the row was newly
  inserted), updateStatus (atomic status transition, returns the real
  previous status). Postgres-backed implementation is
  PostgresConstraintRepository (JDBC) - registered via a @Bean factory
  method (ConstraintRepositoryConfig) guarded with @ConditionalOnMissingBean,
  like TaskRepository/TaskDependencyRepository's beans in MockDataConfig -
  NOT via @Repository/@ConditionalOnMissingBean directly on the class
  (found not to reliably self-register that way - see "Open items").
  No in-memory implementation exists or is planned - unlike Task/
  TaskDependency, Constraint had no in-memory repository to begin with
  (per issue #378's own description).
- Task/TaskDependency/TaskStatus were merged from main (2026-08-27) after
  ruthHoltzman's DTO review landed there: Task now has a required ownerId,
  score is Double (was Integer), and construction is via Task.builder()
  only (the old public constructor is gone).
- That same branch (#66) also has a TaskDependencyService (existence /
  self-dependency / duplicate-edge checks, throws TaskNotFoundException /
  InvalidDependencyException / DuplicateDependencyException) that is NOT
  merged into main yet. It overlaps with TaskEligibilityService's
  addDependency/removeDependency (which additionally do cycle detection
  and blockingCount bookkeeping, which TaskDependencyService does not).
  Reconcile the two once #66 merges - probably TaskEligibilityService
  should delegate the raw edge validation/CRUD to TaskDependencyService
  and keep only cycle detection + counters.
  - 2026-09-22, PR #373 round-3 review (leaRevivo): this note predates
    #373's TransactionRunner/DependencyHistoryRepository work, so it only
    covers validation/CRUD delegation - it does NOT yet capture that
    TaskEligibilityService.addDependency/removeDependency also write to
    TaskDependencyRepository directly, bypassing TransactionRunner and
    DependencyHistoryRepository entirely. Dormant today (nothing wires
    TaskEligibilityService's addDependency/removeDependency to an
    endpoint), but whoever reconciles the two services must also route
    TaskEligibilityService's edge writes through TaskDependencyService/
    TransactionRunner - otherwise the audit-history feature silently
    misses changes made through that path. Tracked alongside issue #401
    (controller-level unification; the natural place this would first get
    wired to a live endpoint).

- Task/TaskDependency/Constraint use Lombok for accessors and the builder. `Task` keeps
  Lombok-generated id-only equals/hashCode (`@EqualsAndHashCode(onlyExplicitlyIncluded = true)` +
  `@Include` on id) - don't widen it to all fields. `Constraint`'s equals/hashCode are hand-written
  (id-only) on purpose, next to its hand-written `getReason`/`setReason`; don't re-add
  `@EqualsAndHashCode` there, since it generates nothing alongside the hand-written methods.

## Open items
- 2026-10-12, issue #580 (tenant isolation) - decisions made while implementing, each resolved
  directly rather than left implicit:
  - **Null-tenant policy is strict**: an edge needs both tasks' `tenant_id` non-null and equal;
    null on either side is rejected, same as a mismatch. Confirmed with the user that existing/
    test data without a tenant isn't meaningful enough to justify the weaker "skip when unknown"
    alternative the issue offered. Follow-up: make `task.tenant_id` `NOT NULL` once every row is
    backfilled through `TaskCreatedEventHandler`/`TaskUpdatedEventHandler`.
  - **`task.updated` claiming a different tenant than stored is rejected** (`TenantReassignmentException`
    -> `NonRetryableEventException`), not applied - confirmed directly with the Inbox team
    (Shlomzion Cohn, Slack) that `tenant_id` never legitimately changes after task creation, so
    this always signals a bug upstream. A still-null stored tenant is backfilled instead, not
    treated as a mismatch.
  - **Dropped the issue's item 3 (a Mind-side `tenant_id` cross-check) entirely, not deferred**:
    the user confirmed Mind was never meant to be a tenant source (only Inbox is), and separately,
    Mind's real event (`task-dependencies-proposed-event.proto`, now a cross-team, Relay-owned
    registered schema under `shared/proto/`, not a private Chain draft) has no such field at all -
    adding one would be a cross-team schema change, moot anyway since the per-edge same-tenant
    check already covers an edge Mind proposes.
  - **Mid-implementation discovery**: an earlier exploration pass for this issue was accidentally
    done against the unmerged `shifra/chain-pending-task-buffer-tables` branch instead of `main`
    (that branch's `PendingDependencyService`/pending-event-buffer feature does **not** exist on
    `main`) - re-verified everything actually touched against the real `main` before implementing.
    At that point `DependencyProposedEventHandler` called `TaskEligibilityService.addDependencies`
    directly (no buffer/reconcile layer), so the cross-tenant check/REJECTED-history write was
    added directly in that handler.
  - **Superseded by a concurrent rewrite merged the next day (2026-10-13, PR #609 +
    `chain/ruthHoltzman/582-task-completed-consumer` + the separate
    "Riki-Mind-Dependency-Events"/`2c425890` work)**: `DependencyProposedEventHandler` no longer
    calls `TaskEligibilityService.addDependencies` at all - it now calls
    `TaskDependencyService.addDependency` once per edge (missing target -> buffered via the new
    `UnresolvedDependencyService`/`unresolved_dependencies` table instead of retried; missing
    source task is still retried). Merged this branch's tenant check/REJECTED-history code down
    into `TaskEligibilityService`/`TaskDependencyService` to match, which actually **closes** the
    gap noted below for Mind's path too: since Mind's edges now go through the same
    `TaskDependencyService.addDependency` the REST path uses, a *successful* Mind-sourced add is
    recorded in `dependency_history` as well, not just a rejected one.
  - Also found incidentally while running the `@Tag("postgres")` suite for the first time in a
    while (unrelated to tenant work, not fixed, flagged to the team): `TaskRepositoryContractTest`'s
    `findByParentTaskId_returnsDirectChildren`/`findByParentTaskId_excludesSoftDeletedChildren`
    seed a child referencing a `parentTaskId` that's never itself inserted - fails the real FK
    constraint under `PostgresTaskRepositoryContractTest` (passes under the in-memory
    implementation, which doesn't enforce FKs). `PostgresConstraintRepositoryTest.save_nullReason_roundTripsAsNull`
    also fails independently (`reason` round-trips as `{}` instead of `null`) - also pre-existing,
    also unrelated.
- #649: TaskEligibilityService was split by domain - edges in `DependencyEdgeService`, constraints in
  `ConstraintEligibilityService`, counter helpers in `TaskCounterUpdater`. The graph lock is the
  `GraphLock` bean (see "Graph lock" below) shared by `DependencyEdgeService` and
  `TaskEligibilityService`; older notes below that name `TaskEligibilityService` for moved methods predate this.
- PR #468 review (2026-09-23, leaRevivo) findings addressed 2026-09-24:
  - Critical: `PostgresConstraintRepositoryTest`/`PostgresConstraintRepositoryConcurrencyTest`
    were committed active with no exclusion, and `values-chain.yaml`'s `postgresql.enabled`
    is still `false` - so CI's `mvn -B test` (same job as the Postgres port-forward step, which
    is a no-op without that flag) had nothing on 5432 and would fail for the whole team on
    every push to `teams/chain/**`, not just locally. Fixed with `@Disabled(...)` on both
    classes. **Also found while fixing this** (not called out by the review, but the same root
    cause): `ChainApplicationTests` and `ChainEventConsumerIntegrationTest` - two *pre-existing*
    tests unrelated to Constraint - were *also* newly broken by this branch, because
    `@SpringBootTest` boots the whole context, and Flyway migrates eagerly at startup once a
    real Postgres `DataSource` + `flyway-core` are on the classpath, not lazily on first use.
    Fixed at the root: `spring.flyway.enabled: false` by default (application.yml), with the two
    Postgres-backed test classes opting back in individually via
    `@TestPropertySource(properties = "spring.flyway.enabled=true")` - the same
    per-test-class-override pattern `ChainEventConsumerIntegrationTest` already used for
    `chain.kafka.consumed-topics`. Verified: `ChainApplicationTests`/
    `ChainEventConsumerIntegrationTest` pass again; the two Postgres tests report `Skipped`, not
    `Errors`. (The remaining local test failures, `ChainControllerTest`/`KafkaEventPublisherTest`,
    are an unrelated Mockito/ByteBuddy-vs-JDK25 issue specific to a local JDK newer than the
    project's Java 21 - confirmed CI itself runs `mvn -B test` inside a `maven:...-temurin-21`
    Docker image, per `teams/chain/Dockerfile`, so this doesn't reproduce in CI at all.)
  - Major: `onConstraintStatusChanged` never verifies `constraintId` actually belongs to
    `taskId` - `ConstraintRepository#updateStatus` operates on `constraintId` alone, so a caller
    passing a mismatched `taskId` could adjust the wrong task's counter, or mutate a constraint
    whose real owning task doesn't exist/is soft-deleted, uncaught. **Fixed** by checking the
    pair inside the SQL itself: `updateStatus` now takes `taskId` and matches on both
    `constraint_id` and `task_id` (in the lock and the update), so a mismatched pair matches no
    row and nothing is written - the check and the write are one atomic statement, no extra
    round trip. Covered by a repository test and a service-level test.
  - Minor (both fixed): a formatting slip in `TaskEligibilityServiceConcurrencyTest` (one of
    three mechanically-updated constructor calls was indented to the wrong block), and
    `onConstraintStatusChanged` was missing the Javadoc `onConstraintAdded` got.
- RESOLVED (2026-09-22): openConstraintCount's counter-strategy question
  (recompute-via-query vs keep the +1/-1 delta pattern) - decided on a
  third option, neither of the original two. `ConstraintRepository.save`
  reports whether Postgres actually inserted a new row (via `xmax = 0` in
  the `RETURNING` clause - Postgres's own signal, not a `SELECT COUNT(*)`)
  and `updateStatus` atomically returns the row's real previous status (a
  CTE under `FOR UPDATE`, read and written in one statement). Both are
  derived from Postgres itself, at no extra query cost beyond the write
  that already has to happen - no in-memory idempotency map needed at
  all, so the restart-desync failure mode this section used to describe
  (see below) doesn't apply here: there's no in-memory ledger left to
  desync. `TaskEligibilityService.onConstraintAdded`/
  `onConstraintStatusChanged` are wired to `ConstraintRepository`
  accordingly - this is what actually shipped, not the delta/map pattern
  PR #114 originally added those methods with.
- Real bug found and fixed while wiring this up: `PostgresConstraintRepository`
  was originally annotated `@Repository @ConditionalOnMissingBean(ConstraintRepository.class)`
  directly on the class - this silently failed to register as a bean at
  all (`NoSuchBeanDefinitionException` for `ConstraintRepository` when
  `TaskEligibilityService` needed it), because `@ConditionalOnMissingBean`
  does not reliably self-register when placed directly on a
  component-scanned class outside true auto-configuration. Fixed by
  registering it via a `@Bean` factory method instead
  (`ConstraintRepositoryConfig`), the same working pattern
  `MockDataConfig` already uses for `TaskRepository`/
  `TaskDependencyRepository` - `PostgresConstraintRepository` itself is
  now a plain class, not a `@Component`, matching `InMemoryTaskRepository`.
- Still waiting on team-lead/DevOps for the real Postgres DB name/user for
  Chain (dedicated DB + non-superuser user, per team-lead's answer to
  Q8-12) - application.yml/.env.example currently use placeholder values
  (DB_NAME=chain, DB_USER=chain_user/chain_password) pending that.
- pom.xml still lacks a real persistence layer, and Task/TaskDependency
  are plain DTOs, not JPA entities. @Transactional is kept on
  TaskEligibilityService's public methods deliberately (team decision:
  keep the transaction boundary in place now so there's no service-level
  change needed once a real DB lands) — spring-boot-starter-data-jpa is
  back in pom.xml for this, plus a placeholder H2 in-memory DataSource
  (application.yml) purely so Spring Boot's DataSourceAutoConfiguration
  doesn't fail app startup and a PlatformTransactionManager bean exists.
  No JPA entities/repositories use H2 - real data still lives in the
  in-memory TaskRepository/TaskDependencyRepository, so @Transactional
  does not actually cover Task/TaskDependency mutations yet.
- Waiting on team lead's answer about whether a real DB is coming,
  or whether the in-memory contract is final for now
- Graph lock: `DependencyEdgeService`'s and `TaskEligibilityService`'s graph-mutating methods run inside
  `GraphLock#runExclusive` (the old in-process `dependencyGraphLock`
  `synchronized` is gone - it released before commit). `PostgresGraphLock`
  takes `pg_advisory_xact_lock` with one constant key, first in the
  `@Transactional` method and before any task row lock, and it is released only at
  commit/rollback; `InMemoryGraphLock` (a `ReentrantLock`) backs in-memory
  runs and the unit tests. Known limit: one key means all graph writes queue,
  across every project (team-lead decision).
- Resolved: real persistence layer now exists - Postgres-backed
  `Postgres*Repository`/`JpaTransactionRunner` implementations
  (`repository/`), own `chain` database + own `chain` DB user (not the
  shared postgres superuser, not Inbox's resonance_flow database - see
  PR #417), Flyway-owned schema (`src/main/resources/db/migration/`).
  `Task`/`TaskDependency`/`DependencyHistoryEntry` stay plain DTOs -
  entities (`TaskEntity`/`TaskDependencyEntity`/`DependencyHistoryEntity`)
  are mapped to/from them entirely inside the adapters. The H2 placeholder
  is gone, and so are all `InMemory*` repositories, `MockDataConfig` and `InMemoryProcessedEventStore`
  (2026-10-15): Postgres is the only persistence, including `PostgresProcessedEventStore` (V10) for
  event dedup. The tests that used the in-memory fakes now run against Postgres (`PgRepos`).

- `TaskRepository#update()`'s atomicity is now real: `PostgresTaskRepository`
  uses a pessimistic row lock (`SELECT ... FOR UPDATE`) as the sole
  correctness mechanism for `blockingCount`/`openConstraintCount` under
  concurrent writes - `@Version` on `TaskEntity` is defense-in-depth for a
  different, not-yet-existing write path, not part of this guarantee.
  The graph lock (see "Graph lock" above) is still required on top of it: it
  serializes the multi-row cycle-detection DFS and the read-with-edge-write
  decisions, which a single-row lock cannot.
- Resolved (#384): Kafka topics wired in chart config - consume
  `task.dependencies.proposed` (addDependency), `task.dependencies.removed`
  (removeDependency, PROPOSED), `task.status.updated` (onTaskStatusChanged,
  PROPOSED - assumes Inbox's full 8-value status enum, not yet confirmed with
  Inbox); produce `task.scheduling.updated`. onConstraintAdded/
  onConstraintStatusChanged stay REST-triggered - no topic today, draft schemas
  exist only in case that changes later.
- 2026-08-30, addressed a teammate code review on PR #172:
  - Fixed: addDependency/removeDependency were only using
    the graph lock, not the per-task lock the other methods use for
    the same Task fields - no mutual exclusion between them. Now the
    counter mutation itself goes through the per-task lock too.
  - Fixed: adding a dependency on an already-DONE task incremented
    blockingCount with no future status transition ever able to
    decrement it back off (DONE is terminal until reopened) - permanently
    stuck dependents. addDependency/removeDependency now read the
    target's status (under the graph lock, so it can't race
    onTaskStatusChanged's own DONE transition) and only touch the
    counter for a currently-not-DONE target.
  - Disagreed with: the reviewer's deadlock claim. Per-task lock nesting
    only happens in onTaskStatusChanged (task, then its dependents,
    always upstream-to-downstream) and the graph is guaranteed acyclic,
    so no other call can be waiting on the reverse edge - traced through
    and did not find a viable cycle in the wait-for graph.
  - Fixed: TaskEligibilityService had no @Service/@Component - it was
    never a Spring bean at all, so @Transactional had no AOP proxy to
    attach to regardless of DataSource. Added @Service.
  - Fixed: unbounded taskLocks map (one Object kept forever per task id
    ever touched) - switched to a fixed-size (256) lock-striping array.
  - Fixed: duplicated edge-existence-check and requireTask/orElseThrow
    boilerplate - extracted to edgeExists()/requireTask() helpers.
  - Fixed: added Objects.requireNonNull guards on all public method
    parameters.
  - Fixed: Constraint.java had Hebrew comments and no constructor
    validation (Task has both) - translated and added validation.
  - Added: direct unit tests for InMemoryTaskRepository/
    InMemoryTaskDependencyRepository (previously only exercised via
    mocks in TaskEligibilityServiceTest), and a real multi-threaded
    concurrency test using the in-memory repos (not mocks) that would
    have caught the two Critical findings above.

# Project Instructions for Claude Code

## Terminal & Tool Permissions
You have full unrestricted permission to run terminal commands, edit files,
and use any tool — you do not need to ask for confirmation before acting.

However, you MUST follow these hard rules at all times:

### Never do these, under any circumstances:
- Never run `git push` (to any remote/branch) without explicitly telling me
  first and getting a clear "yes"
- Never run `git reset --hard`, `git rebase`, or rewrite git history
- Never delete files or folders (`rm`, `rm -rf`, `del`) without listing
  exactly what you're about to delete first, in your response
- Never run destructive database commands (DROP, TRUNCATE, DELETE without WHERE)
- Never modify `.env`, config files with secrets/API keys, or files outside
  this project's folder
- Never install global packages (`npm install -g`, `pip install --user`
  system-wide) — project-local installs only
- Never overwrite existing files wholesale when a smaller targeted edit
  would do

### Always do these:
- Before any git commit: show me what files changed and a one-line summary,
  in the same response as the commit — don't commit silently
- Before any `git push`: update the relevant README.md file(s) to reflect
  the current state of the code, in the same response as the push
- If a command could be destructive or hard to undo, pause and ask me
  explicitly, even though you generally don't need to ask
- If you're unsure whether something falls into the "never" list above,
  treat it as if it does — ask first
- Whenever you need my approval or confirmation to proceed with a task —
  such as running a shell command or executing a tool — play an audible
  system sound or alert to notify me immediately so I can switch to the
  terminal and approve it

## Code Comments
- Add comments only where the code isn't self-explanatory
- Keep comments concise — one line where possible
- All comments in code/commits/PRs in English (chat with the assistant can
  be Hebrew or English, whichever the conversation started in)

## After Every Change
At the end of each response, include a short summary:
- What was changed (1-2 sentences)
- Which files were modified/created/deleted

## Additional Guidelines
- Follow the existing code style already in the project
- Don't create new files unless necessary; prefer editing existing ones
- If a change is large or affects multiple files, briefly state the plan
  before starting
