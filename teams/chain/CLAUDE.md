# Chain — Context for Claude Code

## Task
Implementing dependency update flow: addDependency, removeDependency,
onTaskStatusChanged, recalculateTaskEligibility, onConstraintAdded,
onConstraintStatusChanged.

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

## Open items
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
- Until real JPA-backed repositories exist, TaskEligibilityService ALSO
  uses in-process locking to make read-modify-write on blockingCount /
  openConstraintCount actually atomic - @Transactional alone doesn't
  cover this today. This locking is only correct for a single JVM owning
  the in-memory repositories — once persistence is real, add @Version or
  an atomic UPDATE for the counters and the locking can be retired. Full
  rationale is in the class Javadoc; summary: dependencyGraphLock guards
  the edges plus any DONE-status read that decides whether an edge
  currently counts against blockingCount, per-task locks (striped over a
  fixed array, not one object per task id, to keep memory bounded) guard
  a single task's counters, and dependencyGraphLock is always acquired
  before a per-task lock, never after, so the two can't deadlock.
- 2026-08-30, addressed a teammate code review on PR #172:
  - Fixed: addDependency/removeDependency were only using
    dependencyGraphLock, not the per-task lock the other methods use for
    the same Task fields - no mutual exclusion between them. Now the
    counter mutation itself goes through the per-task lock too.
  - Fixed: adding a dependency on an already-DONE task incremented
    blockingCount with no future status transition ever able to
    decrement it back off (DONE is terminal until reopened) - permanently
    stuck dependents. addDependency/removeDependency now read the
    target's status (under dependencyGraphLock, so it can't race
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