# Chain PostgreSQL Persistence — Implementation Plan

**Scope:** replaces `InMemoryTaskRepository`/`InMemoryTaskDependencyRepository`
(and, per an expanded-scope decision below, `InMemoryDependencyHistoryRepository`) with real
PostgreSQL-backed implementations, without changing `TaskRepository`/`TaskDependencyRepository`/
`DependencyHistoryRepository`'s contracts or `TaskDependencyService`/`TaskEligibilityService`'s
public behavior. Constraint persistence stays out of scope (tracked separately, per the originating
task).

**A note on where this plan came from:** the task that requested it said to follow "the migration
approach already sketched" in `PLAN.md`'s own "Phase 3 detail - recommended transactional approach
once real persistence lands." That section does not exist anywhere in `PLAN.md` (947 lines checked
in full - it covers two unrelated, already-closed review-remediation rounds: Kafka infrastructure
and graph traversal) or anywhere else in the repository (repo-wide search, zero hits). Confirmed
with the requester before writing this plan: there is no other source to pull from - the approach
below is designed fresh from the patterns already present in the code (`TaskRepository#update`'s
per-id-lock contract, `TransactionRunner`'s "swap the in-memory impl for a real one" Javadoc,
`TaskEligibilityService`'s own locking-rationale Javadoc, issue #243, Inbox's established
Postgres/Flyway convention), not carried over from a document that turned out not to exist.

## Scope/Design Audit — Round 1 (2026-09-16)

A review pass before implementation started, checking every addition against three questions:
is it an **acceptance criterion**, an **existing contract/behavior** that must be preserved, or a
**technical correctness requirement**? Anything that was only "good design" is now labeled
**optional/follow-up** explicitly rather than blended in as if required. Outcomes:

| # | Question raised | Resolution |
|---|---|---|
| 1 | Is every added piece actually required by the acceptance criteria, or is some of it architectural extension? | Audited item-by-item — see the "Why" column now added throughout §1–§9. Nothing was dropped, but several items were re-tagged (§9 now separates a hard external blocker from genuinely optional follow-ups; `@Version` and the `dependency_history` migration are now explicitly marked as decisions beyond the literal AC, with their own justification). |
| 2 | Does `entityManager.merge(entity)` really give `save()`'s exact upsert contract, or was that an unverified assumption? | **Real risk found and fixed.** `merge()` on a `@Version`-annotated, client-assigned-id entity that was never loaded by the current persistence context risks Hibernate comparing/mismatching the incoming (always-`0`) version against the DB's real one, up to a spurious `OptimisticLockException` on the second `save()` of any row. §4's `save()` design is rewritten to a native `INSERT ... ON CONFLICT (id) DO UPDATE` statement instead — see §4 for the exact SQL and why it sidesteps the problem entirely rather than working around it. |
| 3 | Does the plan read as if `SELECT ... FOR UPDATE` and `@Version` are two mechanisms needed together for the same race? | Reworded §4/§5 to be explicit: pessimistic locking is the *sole* correctness mechanism for `TaskRepository#update()`; `@Version` protects only *other*, not-yet-existing write paths that might bypass `update()`. Not "two mechanisms for one problem." |
| 4 | Is the `fn_check_task_dependency_tasks_active` trigger required, or a DB-level business rule that should instead be application-level locking? | **Raised explicitly, decided: keep the trigger.** The AC/issue #243 language asks to *close* the race, not merely narrow it, and a DB-level check is the only option that holds regardless of whether every future write path cooperates with the service layer's locking discipline. The rejected alternative (reusing `taskRepository.update(id, identity())` purely for its locking side effect) was real but weaker - see §3 for the full trade-off as recorded. |
| 5 | Does `dependency_history.sequence` have a clear, stated reason to exist? | Yes - reworded in §3: `created_at` gives chronological order, `sequence` is a deterministic tie-breaker for entries created in the same timestamp. |
| 6 | Is CI/Helm correctly classified as "out of scope"? | **No - re-classified.** §9 now separates **Required External Coordination** (blocks full CI verification, not optional) from genuine **Optional Follow-ups** (native enums, `dependencyGraphLock` retirement) that were previously listed side by side under one misleading heading. |
| 7 | Should the step-by-step breakdown be smaller, independently-verifiable checkpoints? | Yes - §8 rewritten around 8 checkpoints, each endable with something concretely verifiable, so one variable changes at a time. |
| 8 | Is every remaining decision traceable to AC / contract / correctness, with non-required items marked as such? | §10's traceability table now carries a "Why" column classifying every row as **AC**, **Contract**, **Correctness**, or **Optional/Follow-up**. |

## 1. Decisions settled before this plan was written

Three cross-cutting questions came up during review that the originating task's text didn't
resolve (or, in one case, actively conflicted with an existing repo convention). Each was raised
and decided explicitly rather than guessed:

| # | Question | Decision |
|---|---|---|
| 1 | The task instructs adding a Postgres service to `teams/chain/docker-compose.yml`, but a root-level `docker-compose.yml` already runs one shared `postgres` service explicitly commented `used by chain, bridge, inbox`, matching Inbox's own `CLAUDE.md` ("one resonance_flow instance for the whole project... a per-team Postgres would mean a per-team copy of a schema that is supposed to be canonical. Don't add one here"). Reuse the shared instance, or add a second Chain-only one as the task literally says? | **Reuse the shared root Postgres.** Chain connects to it exactly like Inbox already does (env-var-driven datasource, `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`); `teams/chain/docker-compose.yml`'s own standalone dev flow reaches it via `host.docker.internal`, mirroring `teams/inbox/docker-compose.yml` exactly (which also has no Postgres service of its own). |
| 2 | Given decision #1, where do Chain's tables live inside that one shared database - the same `public` schema Inbox's canonical `tasks`/etc. already occupy, or a dedicated schema? | **Dedicated `chain` Postgres schema** (`chain.task`, `chain.task_dependency`, `chain.dependency_history`). Avoids a name collision with Inbox's own `tasks` table, and gives Chain its own `flyway_schema_history`, independently versioned from Inbox's migrations against the same physical server. |
| 3 | `TaskDependencyService.addDependency`/`removeDependency` write the edge *and* a `DependencyHistoryEntry` inside the same `TransactionRunner.runInTransaction` call. The originating task never mentions `DependencyHistoryRepository` at all - not in scope, or an oversight? | **In scope - migrate it too**, in this same task. Unlike Constraints (excluded for having "no existing repository/in-memory precedent"), `DependencyHistoryRepository` already has a full interface and a working in-memory reference implementation, and it sits directly inside the transactional write path being touched. Leaving it in-memory while `TransactionRunner` starts wrapping a real DB transaction would make `TaskDependencyService`'s own "recorded... in the same transaction" Javadoc literally false for the history half, and would silently drop the audit trail on every restart even though the graph itself now survives one. |

## 2. Architecture

**Technology: Spring Data JPA + Flyway**, not raw JDBC. `spring-boot-starter-data-jpa` is already
in `pom.xml` (added for exactly this purpose - see the existing H2-placeholder comment in
`pom.xml`/`application.yml`), Inbox already establishes the project's JPA+Flyway+Postgres
convention end to end, and `TaskEligibilityService`'s own Javadoc names the target explicitly:
*"the plan is to re-add real transactional semantics once persistence moves to a real JPA-backed
store (e.g. Postgres)."*

**Layering, following Chain's existing layer-based `repository/` package** (Chain is not
feature-packaged like Inbox - that convention is Inbox-specific, documented in Inbox's own
`CLAUDE.md`, not repo-wide):

```
repository/
├── TaskRepository.java                    (unchanged interface)
├── TaskDependencyRepository.java           (unchanged interface)
├── DependencyHistoryRepository.java        (unchanged interface)
├── TransactionRunner.java                  (unchanged interface)
├── InMemory*.java                          (unchanged - kept as the MockDataConfig fallback)
├── entity/
│   ├── TaskEntity.java
│   ├── TaskDependencyEntity.java
│   ├── TaskDependencyId.java               (@Embeddable composite key)
│   └── DependencyHistoryEntity.java
├── jpa/
│   ├── SpringDataTaskJpaRepository.java        (extends JpaRepository<TaskEntity, UUID>)
│   ├── SpringDataTaskDependencyJpaRepository.java
│   └── SpringDataDependencyHistoryJpaRepository.java
├── PostgresTaskRepository.java             (@Repository, implements TaskRepository)
├── PostgresTaskDependencyRepository.java   (@Repository, implements TaskDependencyRepository)
├── PostgresDependencyHistoryRepository.java(@Repository, implements DependencyHistoryRepository)
└── JpaTransactionRunner.java               (@Component, implements TransactionRunner)
```

**No entity ever crosses a service boundary** - each `Postgres*Repository` maps entity ↔ DTO
(`Task`/`TaskDependency`/`DependencyHistoryEntry`) internally, same as the in-memory
implementations' own DTO-in/DTO-out shape. `TaskDependencyService`/`TaskEligibilityService` are
untouched - the whole point of the interface contracts.

**Bean wiring stays automatic.** `MockDataConfig`'s beans are `@ConditionalOnMissingBean`. Once
`PostgresTaskRepository` etc. are plain `@Repository`/`@Component` classes picked up by component
scanning, Spring registers them as beans of type `TaskRepository`/etc. *before* `MockDataConfig`'s
`@Bean` methods are evaluated, so the in-memory beans back off automatically. **No change to
`MockDataConfig` itself** - directly satisfies the "no other service-layer code changes required"
acceptance criterion.

## 3. Schema design

```sql
-- V1__init_chain_schema.sql
CREATE SCHEMA IF NOT EXISTS chain;

CREATE TABLE chain.task (
    id                      UUID PRIMARY KEY,
    parent_task_id          UUID REFERENCES chain.task(id) ON DELETE SET NULL,
    owner_id                VARCHAR(255) NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    is_ready                BOOLEAN NOT NULL DEFAULT false,
    blocking_count          INTEGER NOT NULL DEFAULT 0,
    open_constraint_count   INTEGER NOT NULL DEFAULT 0,
    estimated_duration_min  INTEGER,
    score                   DOUBLE PRECISION,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMP,

    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'ACTIVE', 'DONE')),
    CONSTRAINT chk_task_blocking_count_non_negative CHECK (blocking_count >= 0),
    CONSTRAINT chk_task_open_constraint_count_non_negative CHECK (open_constraint_count >= 0)
);

CREATE INDEX idx_task_parent_task_id ON chain.task(parent_task_id);

CREATE TABLE chain.task_dependency (
    task_id             UUID NOT NULL REFERENCES chain.task(id) ON DELETE CASCADE,
    depends_on_task_id  UUID NOT NULL REFERENCES chain.task(id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (task_id, depends_on_task_id),
    CONSTRAINT chk_task_dependency_no_self_reference CHECK (task_id <> depends_on_task_id)
);

CREATE INDEX idx_task_dependency_depends_on_task_id ON chain.task_dependency(depends_on_task_id);

-- Closes issue #243 (TOCTOU between addDependency's existence check and a concurrent soft
-- delete): the FK constraints above only guarantee the referenced *row* still exists, not that
-- it's still non-soft-deleted - a soft delete is an UPDATE (sets deleted_at), not a DELETE, so it
-- never trips the FK. This trigger closes that specific gap at the DB level, atomically, as part
-- of the same INSERT the edge itself uses - no separate lock/check round trip needed.
--
-- Reconsidered in the Round 1 design audit against an application-level alternative (locking both
-- endpoint tasks via taskRepository.update(id, identity()) before inserting the edge, reusing the
-- same row-lock primitive as TaskRepository#update instead of adding DB-level procedural code).
-- Kept the trigger: the AC/issue #243 explicitly ask to *close* the race, not merely narrow it,
-- and only a DB-level check holds regardless of whether every future write path (including ones
-- outside this service, e.g. an admin script or a later direct-SQL migration) cooperates with the
-- service layer's locking discipline. The rejected alternative would only have "narrowed," not
-- "closed," the gap, and would have been an implicit, unenforced invariant ("every task mutation
-- must go through update(), never save()") rather than something the schema itself guarantees.
CREATE FUNCTION chain.fn_check_task_dependency_tasks_active() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM chain.task WHERE id = NEW.task_id AND deleted_at IS NOT NULL) THEN
        RAISE EXCEPTION 'task % is soft-deleted, cannot participate in a dependency edge', NEW.task_id;
    END IF;
    IF EXISTS (SELECT 1 FROM chain.task WHERE id = NEW.depends_on_task_id AND deleted_at IS NOT NULL) THEN
        RAISE EXCEPTION 'task % is soft-deleted, cannot participate in a dependency edge', NEW.depends_on_task_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_dependency_check_tasks_active
    BEFORE INSERT OR UPDATE ON chain.task_dependency
    FOR EACH ROW
    EXECUTE FUNCTION chain.fn_check_task_dependency_tasks_active();
```

```sql
-- V2__init_dependency_history.sql
CREATE TABLE chain.dependency_history (
    id                  UUID PRIMARY KEY,
    task_id             UUID NOT NULL,
    depends_on_task_id  UUID NOT NULL,
    action              VARCHAR(10) NOT NULL,
    source              VARCHAR(10) NOT NULL,
    source_ref          VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL,
    sequence            BIGSERIAL,

    CONSTRAINT chk_dependency_history_action CHECK (action IN ('ADDED', 'REMOVED')),
    CONSTRAINT chk_dependency_history_source CHECK (source IN ('MIND', 'USER', 'SYSTEM'))
);

-- created_at gives the chronological order; sequence is a deterministic tie-breaker for entries
-- created in the same timestamp (two writes landing in the same millisecond would otherwise tie).
-- Mirrors InMemoryDependencyHistoryRepository's own sequenceById/AtomicLong tiebreaker - a
-- DB-generated monotonic column does the same job without a Java-side counter.
CREATE INDEX idx_dependency_history_task_id_created_at
    ON chain.dependency_history(task_id, created_at DESC, sequence DESC);

-- Same dedup key as findBySourceAndSourceRef - null source_ref rows are exempt (USER changes
-- don't require one), matching the interface's "always false if sourceRef is null" contract.
CREATE UNIQUE INDEX uq_dependency_history_source_source_ref
    ON chain.dependency_history(source, source_ref) WHERE source_ref IS NOT NULL;
```

Deliberately **no FK** from `dependency_history` to `task`: an audit trail must survive the task it
refers to being deleted later - an `ON DELETE CASCADE` here would silently erase history, and no
FK at all avoids that without needing `ON DELETE SET NULL` semantics that don't fit a NOT NULL
audit column either.

`status`/`action`/`source` are plain `VARCHAR` + `CHECK`, not native Postgres enums (the heavier
convention Inbox uses, backed by `hypersistence-utils`). Rejected for this module: Chain's enums
are small (3, 2, 3 values), owned entirely within this module (no other team queries these
tables directly), and a native-enum `ALTER TYPE ... ADD VALUE` migration has sharper edge cases
than a `CHECK` constraint a later migration can just replace. Simplicity over consistency with
Inbox's heavier pattern, given no cross-team consumer to be consistent *for*.

## 4. Entity / adapter design

**`TaskEntity`** (`@Entity @Table(name = "task", schema = "chain")`) - hand-written getters/
setters/no-arg constructor (no Lombok, matching both Chain's and Inbox's existing convention),
`@Id private UUID id`, `@Enumerated(EnumType.STRING) status`, `@Version private long version`
(see §5). Mapping `TaskEntity ↔ Task` happens entirely inside `PostgresTaskRepository` - the
mapper never leaks either direction.

**`TaskDependencyEntity`** - `@EmbeddedId TaskDependencyId id` (`@Embeddable`, holding `taskId`/
`dependsOnTaskId`, matching the table's composite PK). `TaskDependency` itself stays a 2-field
record with no timestamp - `created_at` on the table is DB-only bookkeeping, not mapped back.

**`DependencyHistoryEntity`** - `@Id private UUID id`; `sequence` is
`@Column(insertable = false, updatable = false)` (DB-generated `BIGSERIAL`, read-only from JPA's
side, used only for the same-millisecond tiebreak `findByTaskId` already needs).

**Reads** filter `deleted_at IS NULL` at the query level - more efficient than fetching a
soft-deleted row just to discard it, and matches the interface's "soft-deleted tasks are treated
as not found" contract exactly. Three derived Spring Data methods on `SpringDataTaskJpaRepository`,
final signatures:
```java
Optional<TaskEntity> findByIdAndDeletedAtIsNull(UUID id);
List<TaskEntity> findByIdInAndDeletedAtIsNull(List<UUID> ids);
List<TaskEntity> findByParentTaskIdAndDeletedAtIsNull(UUID parentTaskId);
```
`PostgresTaskRepository.findById`/`findAllById`/`findByParentTaskId` call these three respectively
and map the result to `Task`.

**`save()` (upsert) - reconsidered in the Round 1 audit.** The original design assumed
`entityManager.merge(entity)` gives "insert if the id doesn't exist, replace if it does" for free.
That assumption doesn't hold safely here: `TaskEntity` carries `@Version`, `Task` (the plain DTO)
has no version field at all, and the adapter builds a **brand-new** `TaskEntity` from the DTO on
every `save()` call - one Hibernate never loaded in this persistence context. Merging a
versioned, client-assigned-id entity that Hibernate doesn't recognize as "the one it loaded" is a
known trouble spot: depending on the freshly-built entity's (always-`0`, since `Task` has no
version to carry over) version value, `merge()` can either silently miscompare against the row's
real stored version or throw `OptimisticLockException` on the second `save()` of any given task -
neither of which the interface's `save()` contract allows for (it documents an *unconditional*
overwrite, not one that can spuriously fail).

**Replaced with a native upsert.** Final mechanism: a `@Modifying @Query(nativeQuery = true)`
method on `SpringDataTaskJpaRepository` (not a raw `EntityManager` call in the adapter - stays
consistent with every other query on this entity going through the same Spring Data interface):
```java
@Modifying
@Query(value = """
    INSERT INTO chain.task (id, parent_task_id, owner_id, status, is_ready, blocking_count,
                             open_constraint_count, estimated_duration_min, score, version,
                             created_at, updated_at, deleted_at)
    VALUES (:id, :parentTaskId, :ownerId, :status, :isReady, :blockingCount,
            :openConstraintCount, :estimatedDurationMin, :score, 0,
            :createdAt, :updatedAt, :deletedAt)
    ON CONFLICT (id) DO UPDATE SET
        parent_task_id = EXCLUDED.parent_task_id, owner_id = EXCLUDED.owner_id,
        status = EXCLUDED.status, is_ready = EXCLUDED.is_ready,
        blocking_count = EXCLUDED.blocking_count,
        open_constraint_count = EXCLUDED.open_constraint_count,
        estimated_duration_min = EXCLUDED.estimated_duration_min, score = EXCLUDED.score,
        created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at,
        deleted_at = EXCLUDED.deleted_at, version = chain.task.version + 1
    """, nativeQuery = true)
void upsert(@Param("id") UUID id, @Param("parentTaskId") UUID parentTaskId,
            @Param("ownerId") String ownerId, @Param("status") String status,
            @Param("isReady") boolean isReady, @Param("blockingCount") int blockingCount,
            @Param("openConstraintCount") int openConstraintCount,
            @Param("estimatedDurationMin") Integer estimatedDurationMin,
            @Param("score") Double score, @Param("createdAt") LocalDateTime createdAt,
            @Param("updatedAt") LocalDateTime updatedAt, @Param("deletedAt") LocalDateTime deletedAt);
```
`PostgresTaskRepository.save(Task task)` calls `upsert(...)` with the DTO's fields, then calls
`findByIdAndDeletedAtIsNull(task.getId())` to read back and return the persisted row as a fresh
`Task` - a Spring Data `@Modifying` native query returns only an affected-row count, not a result
set, so the read-back is the final, decided way this method returns the persisted `Task` (not
`RETURNING *`, which `@Modifying` doesn't expose through Spring Data). One deterministic SQL
statement on the write itself, no read-then-compare, so there is nothing for Hibernate's version
machinery to get confused about.

Every column is fully replaced by whatever the caller passed (matching `save()`'s documented
"unconditional overwrite" semantics exactly - the in-memory implementation's own Javadoc: *"save is
an unconditional overwrite. Racing it against a concurrent update on the same id is a last-write-
wins race"*), including `created_at` - `save()` does not preserve any prior field selectively.
**`version` is the one exception: it is only ever incremented, never read or compared.** This is
deliberate, not an oversight - `save()`'s own contract explicitly disclaims any
concurrency-safety guarantee ("last-write-wins race"), so it must never participate in optimistic
locking or be capable of throwing `OptimisticLockException`; a blind `version + 1` on conflict
keeps the column monotonically increasing for `@Version`'s defense-in-depth purpose elsewhere
(§4's `update()` design, §5) without ever gating `save()` itself on it. The interface's
defensive-copy contract on `save()` ("mutations the caller makes... must not affect the stored
task, and vice versa") is satisfied because the adapter always maps DTO→SQL params→DB→a freshly
constructed `Task` from the read-back query - no object is ever shared between caller and store,
unlike the in-memory implementation's known gap here (`PLAN.md`'s own earlier Finding #7, handed
off to Ruth Holtzman, never fixed on that branch).

A dedicated test (§7) directly proves the upsert behavior against real PostgreSQL rather than
continuing to rely on an assumption about ORM semantics: two `save()` calls on the same id with
different field values leave exactly one row with the second call's values, and `version`
increments without ever throwing.

**`update(id, mutator)` (atomic per-id read-modify-write)** - `mutator` is an arbitrary
`UnaryOperator<Task>`, so it can't be turned into a single `UPDATE ... SET x = x + 1` the way a
known, fixed delta could. The literal match for the interface's own wording - *"performed as a
single operation under a per-id lock"* - is **pessimistic row locking**: `SELECT ... FOR UPDATE`,
final signature on `SpringDataTaskJpaRepository` (deliberately **not** filtering `deleted_at` in
the query itself - the soft-delete check happens in the adapter, step 2 below, using the already-
locked row):
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select t from TaskEntity t where t.id = :id")
Optional<TaskEntity> findByIdForUpdate(@Param("id") UUID id);
```
Called inside the surrounding transaction (see §5). The adapter then:
1. Returns `Optional.empty()` immediately if no row was found.
2. Returns `Optional.empty()` **without invoking `mutator`** if the locked row's `deletedAt != null`
   - this exact behavior is what `TaskRepositoryContractTest.update_leavesSoftDeletedTaskUntouched`
   asserts (a mutator that throws must never be invoked), so the check has to happen before the
   mutator call, using the already-locked entity's own field, not a second query.
3. Otherwise applies `mutator` to the current DTO, copies the result's fields back onto the
   **same managed entity instance** (not a fresh `save()` call) - Hibernate's dirty checking then
   emits the `UPDATE` automatically at transaction commit.

**`SELECT ... FOR UPDATE` is the sole correctness mechanism for `update()` - stated unambiguously,
since an earlier draft's wording risked reading as if two mechanisms were needed together for the
same race.** The pessimistic lock alone fully serializes every `update()` call for a given id;
nothing else is required for `blockingCount`/`isReady` consistency under concurrent writes (the
acceptance criterion this exists for).

`@Version` is added to `TaskEntity` too, but it protects a **different, not-yet-existing** class
of write path - one that bypasses `TaskRepository#update()` entirely and instead goes through a
plain JPA-managed write path Hibernate applies its own optimistic check to (this task's own code
never does that; `update()` uses the row lock, `save()` is the native upsert above and explicitly
never checks version). It is pure defense-in-depth against a *future* code path this task doesn't
add, not a requirement for anything `update()` needs today - not something this task's own code
paths need to catch/retry, and not required by any acceptance criterion. Kept because the
originating task explicitly names it ("Consider @Version"), at negligible cost (one column, no
behavior it needs to gate).

**`TaskDependencyRepository.save()` (atomic idempotent insert)** - `entityManager.persist(...)`
followed by an explicit `flush()` inside the method (so a PK conflict surfaces here, not at the
next unrelated flush point), catching `DataIntegrityViolationException` to return
`Optional.empty()` for "edge already existed, no-op" - the PK-conflict-as-signal approach, which is
the atomic DB-native version of the interface's "check-and-insert as one indivisible operation"
requirement, with no separate exists-check/race window. **Important distinction:** a PK-violation
`DataIntegrityViolationException` (the dedup case) must be told apart from the §3 trigger's raised
exception (a task in the edge is soft-deleted, issue #243's race) - the trigger's `RAISE EXCEPTION`
surfaces as a different, uncategorized SQL exception, not a constraint violation, so it propagates
instead of being swallowed as "already exists." A dedicated test covers this distinction (see §7).

**`TaskDependencyRepository.delete()`** - a derived Spring Data method on
`SpringDataTaskDependencyJpaRepository`, traversing through the `@EmbeddedId`'s two fields, final
signature:
```java
long deleteByIdTaskIdAndIdDependsOnTaskId(UUID taskId, UUID dependsOnTaskId);
```
The adapter returns `count > 0` for the interface's required `boolean`.

## 5. Transaction strategy

**`JpaTransactionRunner implements TransactionRunner`** wraps `Supplier<T> action` in a
`TransactionTemplate` (backed by the `PlatformTransactionManager` Spring Boot auto-configures once
a real `DataSource` exists - no manual bean needed). This replaces `InMemoryTransactionRunner`'s
"best-effort, single global lock, no real rollback" with an actual ACID transaction - directly
what `TransactionRunner`'s own Javadoc asks for ("DB-independent transaction boundary seam, so the
in-memory impl can later be swapped for a real one").

**What this changes for `TaskDependencyService`:** nothing in the class itself. Its
`recordHistoryOrCompensate` manual-rollback logic (save the edge, record history, compensate by
undoing the edge if history-recording throws) currently exists because `InMemoryTransactionRunner`
provides no real rollback. Once both the edge (`chain.task_dependency`) and the history entry
(`chain.dependency_history`) live in the **same** Postgres database and the same
`JpaTransactionRunner`-managed transaction, a thrown exception triggers a real `ROLLBACK` covering
both writes - the manual compensating action becomes redundant but not incorrect (if the real
rollback already undid the edge, the compensating `delete()`/`save()` call is a harmless no-op
against an edge that's already gone/already restored). **Not removing the compensating-action code
in this task** - it's still correct, still documented, and removing it is an unrelated cleanup, not
part of "swap the persistence layer."

**What this changes for `TaskEligibilityService`:** also nothing in the class itself. Its
`@Transactional` annotations, currently a no-op in practice (no JPA participation), start meaning
what they say once `TaskEntity` is a real JPA entity - the `SELECT ... FOR UPDATE` +
dirty-checked `UPDATE` inside `TaskRepository#update` now runs inside the same real transaction the
service method already opens. **This row lock is what makes `blockingCount`/`isReady` consistent
under concurrent writes - `@Version` plays no role in that guarantee** (see §4). `dependencyGraphLock` (the in-process `synchronized` block guarding
cycle detection and the cross-entity "is this dependency DONE" read) **stays as-is** - it isn't
redundant with per-row Postgres locking, because it serializes a multi-row DFS and a
read-together-with-an-edge-write decision, neither of which reduces to locking one row. Retiring
it in favor of a Postgres advisory lock (`pg_advisory_xact_lock`) scoped to the whole
dependency-graph mutation is a bigger, separate design change - explicitly
allowed to be deferred by the originating task's own wording ("full retirement can be a follow-up
if it doesn't fit this task's scope"), so it's listed under §9b as an optional follow-up, not
attempted here.

## 6. Configuration changes

**`pom.xml`** - add:
```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```
(matches Inbox's exact three-dependency set). **Remove** the `com.h2database:h2` placeholder
dependency and its comment block - no longer needed once a real `DataSource` exists.
`spring-boot-starter-data-jpa` stays (already present).

**`application.yml`** - replace the H2 placeholder block:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:resonance_flow}?currentSchema=chain
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: chain
  flyway:
    schemas: chain
    default-schema: chain
```
(`ddl-auto: validate`, never `update`/`create` - Flyway is the sole schema owner, matching Inbox's
convention exactly.)

**`teams/chain/docker-compose.yml`** - no new `postgres` service (per decision #1). Add the same DB
env vars Inbox's own team-level compose file passes through, reaching the DB via
`host.docker.internal`:
```yaml
environment:
  - DB_HOST=host.docker.internal
  - DB_PORT=${DB_PORT:-5432}
  - DB_NAME=${DB_NAME:-resonance_flow}
  - DB_USER=${DB_USER:-postgres}
  - DB_PASSWORD=${DB_PASSWORD:-postgres}
extra_hosts:
  - "host.docker.internal:host-gateway"
```

**Root `docker-compose.yml`** - the `chain` service block currently has no DB env vars and no
`depends_on: postgres` (unlike `inbox`'s block, which already has both). Add the matching block:
```yaml
chain:
  ...
  environment:
    - DB_HOST=postgres
    - DB_PORT=5432
    - DB_NAME=${DB_NAME:-resonance_flow}
    - DB_USER=${DB_USER:-postgres}
    - DB_PASSWORD=${DB_PASSWORD:-postgres}
  depends_on:
    postgres:
      condition: service_healthy
```

**`.env.example`** - replace the "no database variables yet" note with the same five `DB_*`
variables Inbox's `.env.example` already documents.

## 7. Test plan

**`PostgresTaskRepositoryContractTest extends TaskRepositoryContractTest`** (satisfies "the
existing `TaskRepositoryContractTest` suite passes against the new implementation" directly -
`createRepository(seed)` is the only method to implement). Needs a real Postgres reachable during
`mvn test`. Two sources are already available in this repo, neither is Testcontainers (not
currently used anywhere in the repo, and no existing convention to extend):
- **Local dev / this task's own verification:** the shared root `docker-compose.yml`'s `postgres`
  service (per decision #1).
- **CI:** `service-ci.yaml` already port-forwards a chart-managed Postgres to `localhost:5432`
  before running `mvn -B test`, gated on `.postgresql.enabled` in that service's own
  `values-<service>.yaml` - Inbox already has this set to `true`; **Chain's own
  `values-chain.yaml` currently has `postgresql.enabled: false`** and needs the same flip (see
  §9a - this is chart-owned, cross-team-coordinated config, not something to change unilaterally
  inside `teams/chain`).

Test-scope only, no production dependency: since a real Postgres is required either way, no new
test infrastructure dependency is needed beyond what `mvn test` already assumes once wired to the
sources above.

**New/updated unit tests, one per acceptance-criteria line:**

| Acceptance criterion | Test |
|---|---|
| Insert/retrieve Task | `PostgresTaskRepositoryContractTest` (inherited `save_insertsNewTask`, `findById_returnsTask_whenItExists`) |
| Insert/retrieve TaskDependency | New `PostgresTaskDependencyRepositoryTest.saveThenFind_returnsEdgeFromBothDirections`: `save` then `findByTaskId`/`findByDependsOnTaskId` round-trip |
| Task upsert, no duplicate row | `PostgresTaskRepositoryContractTest` (inherited `save_overwritesExistingTaskWithSameId`) **plus** a new `PostgresTaskRepositoryTest.save_upsertsViaNativeInsertOnConflict`: proves the native `INSERT ... ON CONFLICT` statement itself (§4) against real PostgreSQL - two `save()` calls on the same id, different field values each time → exactly one row, second call's values win, `version` increments both times without ever throwing `OptimisticLockException` (this is the test the Round 1 audit's §2 finding calls for - it replaces reliance on an unverified `merge()`-is-upsert assumption) |
| Dependency edge idempotent save | New `PostgresTaskDependencyRepositoryTest.save_isIdempotent_whenEdgeAlreadyExists`: `save()` twice on the same edge → second call returns `Optional.empty()`, exactly one row in `chain.task_dependency` (this same test also covers the "duplicate PK insert" FK/uniqueness case below - one no-op path, not two) |
| FK/uniqueness/self-dependency enforced at DB level | New `PostgresTaskDependencyRepositoryTest.save_throwsDataIntegrityViolation_whenReferencedTaskDoesNotExist` (FK) and `.save_throwsDataIntegrityViolation_whenSelfDependency` (`chk_task_dependency_no_self_reference`), both inserting directly via the JPA layer, bypassing the service |
| Updates work | `PostgresTaskRepositoryContractTest` (inherited `update_appliesMutatorAndPersistsResult`) |
| `blockingCount`/`isReady` consistent under concurrent writes | Adapt the existing `TaskEligibilityServiceConcurrencyTest` to run against `PostgresTaskRepository`/`PostgresTaskDependencyRepository` instead of the in-memory pair - same test, real backing store |
| Flyway migrations run cleanly against a fresh DB | `ChainApplicationTests.contextLoads()` (already `@SpringBootTest`) against a freshly-created database/schema is the direct check - Flyway runs on context startup |
| Soft-deleted-task scenarios (issue #243) | `PostgresTaskRepositoryContractTest` (inherited `findById_returnsEmpty_whenTaskIsSoftDeleted`, `update_leavesSoftDeletedTaskUntouched`, `findByParentTaskId_excludesSoftDeletedChildren`) **plus** new `PostgresTaskDependencyRepositoryTest.save_throwsException_whenReferencedTaskIsSoftDeleted`: soft-delete a task, then attempt `taskDependencyRepository.save()` referencing it → the §3 trigger fires, insert fails, no dangling edge is left (the actual #243 regression case, which the contract test alone can't exercise since it doesn't call the dependency repository) |
| `MockDataConfig` beans correctly displaced | Existing `MockDataConfigTest` continues to pass unmodified when a real `DataSource`/the new `@Repository` beans are on the context - no test change needed, just confirms no regression |

## 8. Step-by-step task breakdown - checkpointed

Rewritten in the Round 1 audit into 8 independently-verifiable checkpoints instead of one flat
10-item list, so each layer is proven working before the next one is built on top of it - fewer
simultaneous variables when something breaks.

**Checkpoint 1 - PostgreSQL + Flyway + connection, no entities yet.**
`pom.xml` (remove H2, add `flyway-core`/`flyway-database-postgresql`/`postgresql`, §6),
`application.yml` datasource/flyway block (§6), `V1__init_chain_schema.sql`,
`V2__init_dependency_history.sql` (§3). *Verify:* `mvn spring-boot:run` against the shared
Postgres (decision #1) starts cleanly, Flyway's `flyway_schema_history` table appears under the
`chain` schema, `ChainApplicationTests.contextLoads()` passes.

**Checkpoint 2 - `TaskEntity` + `PostgresTaskRepository`, in isolation.**
`repository/entity/TaskEntity.java`, `repository/jpa/SpringDataTaskJpaRepository.java`,
`repository/PostgresTaskRepository.java` (§4 - both the native-upsert `save()` and the
pessimistic-lock `update()`). *Verify:* checkpoint 3.

**Checkpoint 3 - run `TaskRepositoryContractTest` against it.**
New `PostgresTaskRepositoryContractTest extends TaskRepositoryContractTest` (§7) plus the new
`save_upsertsViaNativeInsertOnConflict` test proving §4's upsert design directly. *Verify:* the
full inherited contract suite is green, including the soft-delete cases, before touching
dependencies at all.

**Checkpoint 4 - dependency persistence.**
`TaskDependencyEntity`/`TaskDependencyId`, `PostgresTaskDependencyRepository` (§4), and the §3
trigger (`chain.fn_check_task_dependency_tasks_active`). *Verify:* all five
`PostgresTaskDependencyRepositoryTest` methods listed in §7 pass -
`saveThenFind_returnsEdgeFromBothDirections`, `save_isIdempotent_whenEdgeAlreadyExists`,
`save_throwsDataIntegrityViolation_whenReferencedTaskDoesNotExist`,
`save_throwsDataIntegrityViolation_whenSelfDependency`,
`save_throwsException_whenReferencedTaskIsSoftDeleted` (the direct #243 regression case).

**Checkpoint 5 - history + real transactions.**
`DependencyHistoryEntity`, `PostgresDependencyHistoryRepository`, `JpaTransactionRunner` (§4, §5).
*Verify:* `TaskDependencyServiceTest` passes unmodified against the new repositories; a forced
history-write failure still triggers the (now largely redundant, still-present) compensating
rollback in `TaskDependencyService` without leaving a dangling edge.

**Checkpoint 6 - concurrency.**
Adapt `TaskEligibilityServiceConcurrencyTest` to run against the Postgres-backed pair instead of
the in-memory one. *Verify:* same assertions as today, real backing store - this is where a wrong
locking assumption would actually surface.

**Checkpoint 7 - Docker / local dev wiring.**
`teams/chain/docker-compose.yml`, root `docker-compose.yml`, `.env.example` (§6). *Verify:* a
clean `docker compose up` from the repo root gives Chain a working DB connection end to end
(separately, §9 notes the CI/Helm side is not closeable from inside `teams/chain` alone).

**Checkpoint 8 - full regression pass + documentation.**
Full `mvn test` - confirm zero regressions in every test class not touched above
(`TaskTraversalServiceTest`, `ChainControllerTest`, `MockDataConfigTest`, etc.). Update
`teams/chain/README.md`/`CLAUDE.md` to drop the "no DB yet"/H2-placeholder language and point at
this plan.

## 9. Out of scope

Split in the Round 1 audit into two genuinely different categories that were previously listed
side by side under one "out of scope" heading, which understated the first one:

### 9a. Required external coordination - blocks full CI verification, not a normal follow-up

- **Kubernetes/Helm/ArgoCD wiring.** `values-chain.yaml` has `postgresql.enabled: false` today;
  Inbox's equivalent Postgres wiring lives in a dedicated `templates/service-inbox/configmap.yaml`,
  not a generic reusable template, so Chain needs an analogous `service-chain/configmap.yaml` plus
  flipping `postgresql.enabled: true` and adding a `dependencies: [{name: postgresql, port: 5432}]`
  entry to `values-chain.yaml`. This is chart-owned infra (root `CLAUDE.md`: *"Coordinate
  chart/template changes with its owner rather than editing the chart unilaterally"*), so it is not
  done as part of `teams/chain`'s own change set.
  **This is not an optional nice-to-have: without it, CI's `mvn -B test` step for Chain
  (`chain-ci.yaml` → `service-ci.yaml`) will start failing once the H2 fallback is removed**, since
  `service-ci.yaml`'s Postgres port-forward step (which `PostgresTaskRepositoryContractTest` and
  `ChainApplicationTests` both need to pass) is gated on this exact flag. Two acceptance criteria
  - "Flyway migrations run cleanly" and "`TaskRepositoryContractTest` passes" - **cannot be
  verified in CI until this chart change lands**, even though every line of Chain's own code for
  this task is complete and correct. Flagged here as a hard external dependency this task cannot
  close by itself, not filed alongside genuinely deferrable polish below.

### 9b. Optional follow-ups - deferrable without affecting this task's correctness or AC

- **Constraint persistence** - separate, already-tracked follow-up issue (per the originating
  task).
- **Full retirement of `TaskEligibilityService`'s `dependencyGraphLock`** - real DB-level atomicity
  now covers per-task counter mutation (`TaskRepository#update`'s row lock), but not the
  multi-row cycle-detection DFS or the cross-entity DONE-status read; replacing the in-process lock
  with, e.g., a Postgres advisory lock is a separate, larger design change (§5).
- **Native Postgres enum columns** for `status`/`action`/`source` - plain `VARCHAR` + `CHECK` used
  instead (§3), a deliberate simplification given no cross-team consumer of these tables.
- **Application-level locking instead of the §3 trigger** - considered and rejected in the Round 1
  audit (see §3's inline note), recorded here so it isn't re-litigated from scratch if the trigger
  approach turns out to need revisiting later.

## 10. Acceptance criteria traceability

"Why" classifies every row per the Round 1 audit's own framework: **AC** (a literal checklist
item), **Contract** (an existing interface/behavior being preserved), **Correctness** (needed to
keep data/state consistent, even if no single AC line names it), or **Optional** (good design, not
required - included anyway, cost noted).

| Acceptance criterion / decision | Where addressed | Why |
|---|---|---|
| Tasks inserted/retrieved from PostgreSQL | §3 `chain.task`, §4 `PostgresTaskRepository` | AC |
| Dependencies inserted/retrieved from PostgreSQL | §3 `chain.task_dependency`, §4 `PostgresTaskDependencyRepository` | AC |
| Task upsert - no duplicate row | §4 native `INSERT ... ON CONFLICT` | AC + Contract (`TaskRepository.save()`'s documented upsert semantics) |
| Dependency edge idempotent save | §4 `entityManager.persist` + `DataIntegrityViolationException` catch | AC + Contract (`TaskDependencyRepository.save()`'s documented atomicity) |
| FK, uniqueness, self-dependency CHECK enforced at DB level | §3 | AC |
| `chain.fn_check_task_dependency_tasks_active` trigger (issue #243) | §3 | AC (soft-deleted-task test coverage is explicitly listed) + Correctness |
| Updates work correctly | §4 `update()` design | AC + Contract (`TaskRepository.update()`'s per-id-lock wording) |
| `blockingCount`/`isReady` consistent under concurrent writes | §4 pessimistic lock (`SELECT ... FOR UPDATE`) | AC + Correctness - **sole mechanism**, not shared with `@Version` |
| `@Version` on `TaskEntity` | §4, §5 | Optional - originating task says "Consider," not required; defense-in-depth for write paths this task doesn't add |
| `dependency_history` migrated to Postgres | §1 decision #3, §3, §4 | Optional/expanded scope - not named by any AC line; included by explicit joint decision to keep `TaskDependencyService`'s "same transaction" Javadoc true |
| Flyway migrations run cleanly against a fresh DB | §3, §6, §8 checkpoint 1 | AC (verification blocked in CI without §9a) |
| `TaskRepositoryContractTest` passes against the new implementation | §7, §8 checkpoint 3 | AC (verification blocked in CI without §9a) |
| Insert/update/duplicate/invalid-reference/soft-deleted-task test coverage | §7 table | AC |
| `MockDataConfig` beans displaced, no other service-layer change | §2 "Bean wiring stays automatic" | AC + Contract |
| `teams/chain`/root `docker-compose.yml`, `.env.example` changes | §6, §8 checkpoint 7 | Correctness - necessary consequence of decision #1 (shared Postgres), not independently AC-named |
| `values-chain.yaml` / Helm chart changes | §9a | **Not this task's code** - required external coordination, blocks full CI verification of the AC rows above until resolved |
