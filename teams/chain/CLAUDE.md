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
- Cycle detection required in addDependency (full in-memory BFS, no
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
