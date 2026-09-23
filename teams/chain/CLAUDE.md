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

## Open items
- Resolved: real persistence layer now exists - Postgres-backed
  `Postgres*Repository`/`JpaTransactionRunner` implementations
  (`repository/`), own `chain` database + own `chain` DB user (not the
  shared postgres superuser, not Inbox's resonance_flow database - see
  PR #417), Flyway-owned schema (`src/main/resources/db/migration/`).
  `Task`/`TaskDependency`/`DependencyHistoryEntry` stay plain DTOs -
  entities (`TaskEntity`/`TaskDependencyEntity`/`DependencyHistoryEntity`)
  are mapped to/from them entirely inside the adapters. The H2 placeholder
  is gone; `MockDataConfig`'s in-memory beans remain only as the
  `@ConditionalOnMissingBean` fallback when no real `DataSource` is wired.
- `TaskRepository#update()`'s atomicity is now real: `PostgresTaskRepository`
  uses a pessimistic row lock (`SELECT ... FOR UPDATE`) as the sole
  correctness mechanism for `blockingCount`/`openConstraintCount` under
  concurrent writes - `@Version` on `TaskEntity` is defense-in-depth for a
  different, not-yet-existing write path, not part of this guarantee.
  `TaskEligibilityService`'s in-process `dependencyGraphLock` is
  **unchanged and still required** - it isn't redundant with per-row
  Postgres locking, since it serializes the multi-row cycle-detection DFS
  and a read-together-with-an-edge-write decision, neither of which
  reduces to locking one row. Full rationale is in the class Javadoc;
  summary: dependencyGraphLock guards the edges plus any DONE-status read
  that decides whether an edge currently counts against blockingCount.
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