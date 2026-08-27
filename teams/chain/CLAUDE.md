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

## Real repository contracts (from ruthHoltzman/dependency-repos branch)
- TaskRepository: findById, findAllById, findByParentTaskId, save
  (no atomic increment yet — in-memory HashMap, not real DB)
- TaskDependencyRepository: findByTaskId, findByDependsOnTaskId, save
  (idempotent), delete (returns void, not a count)
- Proposed addition (pending team approval, NOT YET MERGED):
  incrementBlockingCount/decrementBlockingCount/
  incrementOpenConstraintCount/decrementOpenConstraintCount

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
- Waiting on team lead's answer about whether a real DB is coming,
  or whether the in-memory contract is final for now
- Until real JPA-backed repositories exist, TaskEligibilityService ALSO
  uses in-process locking (per-task lock striping + a global
  dependency-graph lock) to make read-modify-write on blockingCount /
  openConstraintCount actually atomic - @Transactional alone doesn't
  cover this today. This locking is only correct for a single JVM owning
  the in-memory repositories — once persistence is real, add @Version or
  an atomic UPDATE for the counters and the locking can be retired.