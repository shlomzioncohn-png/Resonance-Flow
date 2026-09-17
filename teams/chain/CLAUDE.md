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

## Real repository contracts (from chain/ruthHoltzman/dependency-repository-66)
- TaskRepository: findById, findAllById, findByParentTaskId, save
  (no atomic increment yet — in-memory HashMap, not real DB)
- TaskDependencyRepository: findByTaskId, findByDependsOnTaskId, save
  (idempotent), delete (returns void, not a count)
- Proposed addition (pending team approval, NOT YET MERGED):
  incrementBlockingCount/decrementBlockingCount/
  incrementOpenConstraintCount/decrementOpenConstraintCount
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
- Task/Constraint/TaskDependency on THIS branch now use Lombok
  (2026-09-08, PLAN.md Finding #17) for accessors/equals-hashCode/builder
  - explicitly requested, reversing an earlier same-day decision not to
  (see README.md's 2026-09-07 fourth-round notes) because it would add a
  6th divergent DTO variant across the still-unmerged branches that also
  touch teams/chain/dto. That risk is real and unchanged; it's being
  tracked/handled separately from this branch, not resolved here. Task's
  equals/hashCode stays id-only and Constraint's stays constraintId-only
  (@EqualsAndHashCode(onlyExplicitlyIncluded = true) + explicit
  @Include) - do not let a future merge silently widen either to
  all-fields equality, since the per-task locking design leans on it.

## Open items
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
- RESOLVED: a real database will be available for Chain (no longer
  waiting on team lead's answer). @Transactional and the placeholder
  JPA/H2 dependencies stay exactly as-is until that migration actually
  happens — see PLAN.md's "Phase 3 detail — recommended transactional
  approach once real persistence lands" for the concrete, already-agreed
  migration checklist (JPA entities, JPA-backed repository
  implementations of the existing interfaces, retiring the in-process
  locking in favor of an atomic counter UPDATE + row locking, and a
  DB-level unique constraint on (task_id, depends_on_task_id) for edge
  idempotency). That migration is tracked as separate follow-up work, not
  part of this remediation PR.
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
