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
- Cycle detection required in addDependency (full in-memory DFS, no
  depth limit — not optimized yet, revisit only if it's a bottleneck)
- No retry logic (@Retryable) — let exceptions propagate to the caller
- All code comments must be in English

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
  uses in-process locking (per-task lock striping + a global
  dependency-graph lock) to make read-modify-write on blockingCount /
  openConstraintCount actually atomic - @Transactional alone doesn't
  cover this today. This locking is only correct for a single JVM owning
  the in-memory repositories — once persistence is real, add @Version or
  an atomic UPDATE for the counters and the locking can be retired.
