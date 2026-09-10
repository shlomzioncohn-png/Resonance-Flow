# Graph Traversal Review Remediation Plan

**Scope of this document:** audit and remediation plan for the review comments on
`chain/Shifi/graph-traversal`, PR against the graph traversal API (dependencies/dependents/
ancestors/descendants/trace). Findings are discussed and decided one-by-one with the user before
being recorded here; everything recorded is implemented as a batch once the review pass is done.

**Branch:** `chain/Shifi/graph-traversal`.

**Status (2026-09-10): all 13 findings resolved.** 5 implemented directly on this branch
(#1, #2, #5, #6, #10 - commits `658e2ea`, `534fa55`, `8065796`, `7cb1324`, `8de4b5b`), verified by
`mvn clean test` (47 tests, 0 failures). 2 discussed and deliberately not acted on (#3, #4, #11 -
reasoning recorded per finding below). 4 handed off to their originating branch owner rather than
fixed unilaterally here (#7, #13 to Ruth Holtzman; #8, #9 to Riki) - findings sent directly, not
fixed on this branch. 1 documented only, no code change intended (#12).

| # | Finding | Disposition |
|---|---|---|
| 1 | `handleValidationError` unguarded `.get(0)` | **Fixed** |
| 2 | `Optional` as `Map` key | **Fixed** |
| 3 | `BadRequestException` dead abstraction | Not acted on |
| 4 | `GlobalExceptionHandler` test coverage gap | Not acted on |
| 5 | Performance benchmark never asserted | **Fixed** |
| 6 | Empty adjacency sets never pruned | **Fixed** |
| 7 | `TaskRepository.save()` contract not honored | Handed off to Ruth |
| 8 | Formatting inconsistency in `GlobalExceptionHandler` | Handed off to Riki |
| 9 | Stray no-op comment | Handed off to Riki |
| 10 | Boolean-flag control coupling in `collectReachableIds` | **Fixed** |
| 11 | Redundant existence checks in `getTrace` | Not acted on |
| 12 | Single global write lock | Documented only |
| 13 | `estimatedDurationMin` naming ambiguity | Handed off to Ruth |

---

## Findings

### Finding #1 — `GlobalExceptionHandler.handleValidationError` — unguarded `.get(0)` on field errors

**Problem:** `handleValidationError` (`GlobalExceptionHandler.java`, in `handleValidationError`)
does `ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage()`, assuming at least one
field-level error exists. `MethodArgumentNotValidException` can carry only class-level errors
(`getGlobalErrors()`) with an empty `getFieldErrors()` list - e.g. a cross-field constraint
annotated at the class level rather than on one property.

**Root Cause:** `GlobalExceptionHandler` was adopted wholesale from Riki's branch
(`Riki-Kaplan-API`) to fix an earlier review comment about missing generic error coverage. This
branch's own `ChainController` has zero `@Valid`/`@RequestBody` usage today (confirmed by
search) - it's 8 read-only `GET` routes with `@PathVariable` only - so this handler's bug has
never actually been exercised here. It's real, latent infrastructure code inherited alongside the
rest of the handler, not something this task's own endpoints trigger yet.

**Proposed Solution (decided):** Fall back to `getAllErrors()` (field + global errors combined,
guaranteed non-empty whenever the exception itself exists) when `getFieldErrors()` is empty:
```java
List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
String message = fieldErrors.isEmpty()
        ? ex.getBindingResult().getAllErrors().get(0).getDefaultMessage()
        : fieldErrors.get(0).getDefaultMessage();
```

**Files Affected:** `src/main/java/com/rnd/chain/exception/GlobalExceptionHandler.java`.

**Dependencies:** None.

**Tests:** New test asserting a `MethodArgumentNotValidException` with only global (class-level)
errors and an empty field-error list is handled without throwing, producing a 400 with a message
from the global error.

**Acceptance Criteria:** `handleValidationError` never throws `IndexOutOfBoundsException`
regardless of whether the validation failure is field-level, class-level, or both.

**Risks / Trade-offs:** None - strictly more defensive than today, no behavior change on the
already-working (field-level) path.

**Ownership note:** this file originated on Riki's branch, but the fix is small and
self-contained; decided to fix directly on this branch rather than waiting on her, and inform her
separately.

---

### Finding #2 — `Optional` used as a `Map` key

**Problem:** `InMemoryTaskRepository.childrenByParentId` is `Map<Optional<UUID>, Set<UUID>>`,
used purely because `ConcurrentHashMap` rejects `null` keys and a root task's `parentTaskId` is
legitimately `null`. Every read/write site (`findByParentTaskId`, the constructor's `indexChild`,
`reindexChild`) wraps/unwraps with `Optional.ofNullable(...)`. This is the exact anti-pattern
Effective Java (Item 55) warns against: `Optional` is documented as a return-type-only tool, never
a field/parameter/collection key type - it isn't even `Serializable`.

**Root Cause:** Not an oversight - the class's own Javadoc explains the choice explicitly
("`Optional<UUID>` (not `UUID`) is the map key because `ConcurrentHashMap` rejects null keys").
`Optional` was reached for as the null-safe wrapper that happened to be at hand (and does work
correctly as a key, since it has proper `equals`/`hashCode`), not as a considered fit for
`Optional`'s intended purpose. This file was hand-authored for this task (approved in chat
2026-09-03) - not inherited from a teammate's branch.

**Proposed Solution (decided):** Don't index root tasks at all - `findByParentTaskId(null)` is
never called anywhere: the only production call site
(`TaskTraversalService.childNeighborIds`) always passes a real, already-validated `taskId`;
`TaskRepository`'s own Javadoc documents the contract as "children of *the given task*" with no
mention of a root-listing case; and root detection (`getAncestors`) reads
`Task.getParentTaskId() == null` directly on the object, never through this index. The
`Optional.empty()`/root bucket in the map was write-only dead weight - populated on every root
task, read by nothing. Removing it removes the need to represent "no parent" as a map key at all:
```java
private final Map<UUID, Set<UUID>> childrenByParentId = new ConcurrentHashMap<>();

private void indexChild(Task task) {
    UUID parentId = task.getParentTaskId();
    if (parentId == null) {
        return; // root tasks aren't queried by parent - see findByParentTaskId
    }
    childrenByParentId.computeIfAbsent(parentId, key -> ConcurrentHashMap.newKeySet()).add(task.getId());
}
```
`findByParentTaskId(null)` returns `List.of()` immediately (defensive, in case a future caller
does pass `null`) without touching the map; `reindexChild` skips the removal step when
`previous.getParentTaskId() == null` (nothing was ever indexed there to remove).

Considered and rejected, in order: (1) a runtime-random `ROOT_SENTINEL` key - safe, but still an
invented value solving a problem that doesn't need solving; (2) a fixed sentinel constant (`new
UUID(0, 0)`) - rejected earlier for its collision risk with other code's placeholder conventions;
(3) splitting into a second `Set<UUID> rootTaskIds` structure - unnecessary once nothing needs to
query "all root tasks" in the first place.

**Files Affected:**
`src/main/java/com/rnd/chain/repository/InMemoryTaskRepository.java`.

**Dependencies:** None.

**Tests:** Existing `TaskTraversalServiceTest`/`TaskTraversalPerformanceTest` root/child-traversal
coverage (which already exercises tasks with `parentTaskId == null`, always queried by the root's
own real id, never by `null`) must continue to pass unchanged. No dedicated
`InMemoryTaskRepositoryTest` exists on this branch to update.

**Acceptance Criteria:** No `Optional` appears as a `Map` key type anywhere in the class;
`findByParentTaskId(null)` returns an empty list without throwing; `findByParentTaskId(realId)`
continues to return the correct children for both root and non-root parents.

**Risks / Trade-offs:** None identified - removes code (the `Optional` wrapping at every call
site) rather than adding an equivalent-complexity replacement.

---

### Finding #3 — `BadRequestException` is a dead abstraction; `NotFoundException` has exactly one subclass

**Problem:** `BadRequestException` (abstract) has zero subclasses anywhere in this branch, so
`@ExceptionHandler(BadRequestException.class)` in `GlobalExceptionHandler` is unreachable.
`NotFoundException` (abstract) has exactly one subclass (`TaskNotFoundException`).
`DuplicateDependencyException` doesn't use either hierarchy, extending `RuntimeException` directly.

**Root Cause:** `GlobalExceptionHandler`/`NotFoundException`/`BadRequestException`/
`DuplicateDependencyException`/`TaskNotFoundException` were adopted wholesale from Riki's branch
(`Riki-Kaplan-API`, commit `af303ce`) to fix an earlier review comment. On her branch,
`BadRequestException` is NOT dead - it has two real subclasses
(`InvalidConstraintException`/`InvalidDependencyException`), both actually thrown in her service
layer and exercised (indirectly, via mocked service calls) in her `ChainControllerTest`. Those two
subclasses were correctly *not* copied to this branch, since this branch's `ChainController` is
100% read-only `GET` traversal - there is no write endpoint here that would ever construct one.
The base class came along anyway as part of the wholesale adopt, with nothing left to extend it.
Confirmed this is not an issue to hand back to Riki - her branch has real usage and coverage for
this hierarchy; the gap only exists here, as a byproduct of what wasn't relevant to copy.

**Decision: NOT acted on.** Discussed at length (dead-code risk, YAGNI, `NotFoundException`'s
single-leaf abstraction, whether to report to Riki). Concluded the practical risk is low enough
not to be worth the change right now: it's inert, causes no bugs, misleads no test, and costs
nothing to leave alone unless/until a write endpoint actually needs a `BadRequestException`-family
exception on this branch. Recorded here so the reasoning isn't lost, not because the finding was
wrong - `BadRequestException` genuinely is unreachable dead code on this branch today.

**Files Affected:** None - no change made.

---

### Finding #4 — `GlobalExceptionHandler` has zero test coverage for 5 of its 6/7 branches

**Problem:** `ChainControllerTest.java` only tests `TaskNotFoundException`→404 and
`MethodArgumentTypeMismatchException`→400. `DuplicateDependencyException`, `BadRequestException`,
`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, and the generic
`Exception`→500 fallback have no test anywhere in the module - confirmed by search (no matches
for any of these types outside the handler itself).

**Root Cause:** Same as Finding #3 - `GlobalExceptionHandler` was adopted wholesale from Riki's
branch, but her matching test coverage lives in methods of *her* `ChainControllerTest` that
exercise *her* write endpoints (`addDependency`, `createConstraint`, etc.), none of which exist on
this branch. Checked Riki's branch directly: she has real, passing tests for
`DuplicateDependencyException`, `BadRequestException` (via her two concrete subclasses),
`MethodArgumentNotValidException`, and the generic 500 fallback - only
`HttpMessageNotReadableException` looks untested on her side too. So this isn't a gap to hand back
to her either; her coverage is fine for her branch, it just doesn't travel with the handler file
alone.

**Decision: NOT acted on.** Same reasoning as Finding #3: none of these branches are reachable by
any real traffic through this branch's actual (100% read-only `GET`) `ChainController` today -
they'd only be exercised by directly mocking the service layer to throw them, with no real trigger
path behind that mock. Not worth the effort now; revisit if/when write endpoints land on this
branch and these paths become genuinely reachable.

**Files Affected:** None - no change made.

---

### Finding #5 — Performance benchmark can never fail, yet runs on every default build

**Problem:** `TaskTraversalPerformanceTest.measureAndReport` computes
`boolean pass = p95 < TARGET_P95_MILLIS` and prints it, but never asserts on it. The test runs
unconditionally as part of the default `mvn test` (no `@Tag`, no Surefire exclusion) - 8 traversal
benchmarks x 13 iterations (3 warmup + 10 measured) against a 10,000-task/20,000-edge graph, on
every build, for zero automated regression protection.

**Root Cause:** Not an oversight - the class's own Javadoc tracks this explicitly as "Open
Question 7" ("Runs as part of the regular `mvn test` suite - no @Tag, no exclusion, no new
profile"), and states outright "the target is reported, never asserted." The measurement
infrastructure and result reporting were built correctly (satisfying the original task's "measure
against the target and document results" requirement), but the CI-policy question - should a miss
here fail the build - was consciously deferred, not decided. (Side note: the same Javadoc block
also references "PLAN.md" for further detail; no `PLAN.md` has ever existed on this branch - a
dangling reference, likely copied from another branch's version of this comment.)

**Proposed Solution (decided):** Option (a) - assert on `pass` with a real `assertTrue`, not just
print it:
```java
assertTrue(pass, () -> String.format("%s: p95=%dms exceeds target=%dms", label, p95, TARGET_P95_MILLIS));
```
Chosen over tagging-and-excluding (option b) specifically because of the risk that a separate
performance profile, once out of the default `mvn test` path, quietly stops being run at all -
"forgotten," not deliberately skipped - which would silently reopen the exact protection gap this
finding is about. An always-in-the-default-suite assertion can't be forgotten the same way.

**Files Affected:**
`src/test/java/com/rnd/chain/service/TaskTraversalPerformanceTest.java` (`measureAndReport`).

**Dependencies:** None.

**Tests:** This item *is* the test - no separate test needed, just making the existing one
actually assert.

**Acceptance Criteria:** `measureAndReport` fails the build (via `assertTrue`) when `p95` exceeds
`TARGET_P95_MILLIS` for any of the 8 benchmarked traversal types; passes today given the currently
measured p95 values, all well under the 300ms target.

**Risks / Trade-offs:** Asserting introduces some flakiness risk on a slow/loaded CI machine
(a transient environment slowdown could fail the build without a real code regression) - accepted
given the large existing margin between measured p95s and the 300ms target, and given the
alternative (tag-and-exclude) risks the benchmark being forgotten entirely rather than just
occasionally noisy.

---

### Finding #6 — Empty adjacency sets are never pruned (unbounded map growth under write churn)

**Problem:** `InMemoryTaskDependencyRepository.delete()` and `InMemoryTaskRepository.reindexChild()`
both remove an id from a `Set<UUID>` value but never check whether that `Set` is now empty and, if
so, remove the outer map entry too. Confirmed by reading both methods directly - neither has any
`isEmpty()`/`map.remove(key, ...)` step after the `Set.remove(...)` call.

**Root Cause:** Not an oversight in the sense of "forgot this exists" - it's the common gap in
writing an index: removing an element from the inner `Set` keeps the code functionally correct
(query results are still accurate), so nothing *breaks* today; the missing cleanup step only
matters over time, under write churn, which this branch's code doesn't yet produce. Confirmed no
call site anywhere in this branch's production code (`TaskTraversalService`, `ChainController`,
`config/`) ever calls `save`/`delete`/`update` on either repository - this branch is 100%
read-only. Same underlying reason as Findings #3/#4: real gap, currently unreachable.

**Proposed Solution (decided):** In `InMemoryTaskDependencyRepository.delete()` (both maps) and
`InMemoryTaskRepository.reindexChild()`'s old-siblings removal, after `set.remove(id)`, if
`set.isEmpty()` then `map.remove(key, set)` - the two-arg form, so a concurrent add landing between
the emptiness check and the removal doesn't get silently deleted along with the empty set it just
stopped being.

**Files Affected:**
`src/main/java/com/rnd/chain/repository/InMemoryTaskDependencyRepository.java`,
`src/main/java/com/rnd/chain/repository/InMemoryTaskRepository.java`.

**Dependencies:** None. Note: `InMemoryTaskRepository`'s fix touches the same `indexChild`/
`reindexChild` methods that §Finding #2's fix (dropping `Optional` as the map key) also touches -
land together to avoid re-reading/re-editing the same methods twice.

**Tests:** New test(s) verifying that after the last edge/child for a given key is removed, the
key itself is gone from the map (not just empty) - same reflection-based approach as needed
elsewhere in this codebase to observe private map state directly, since the public interface
can't distinguish "key absent" from "key present with an empty Set" by its return values alone.

**Acceptance Criteria:** After removing the only remaining edge/child under a given key, that key
no longer appears in the underlying map (verified via reflection or an equivalent internal check),
for both `InMemoryTaskDependencyRepository` and `InMemoryTaskRepository`.

**Risks / Trade-offs:** None - the two-arg `remove(key, value)` form is specifically designed to be
race-safe against a concurrent writer, so this doesn't reopen any concurrency issue.

---

### Finding #7 — `TaskRepository.save()` contract promise is not honored by the implementation

**Problem:** The interface Javadoc promises `save` "stores and returns an independent copy, so
later mutations don't leak across." `InMemoryTaskRepository.save` stores and returns the exact
same `Task` reference passed in - no copy anywhere. Currently harmless only because `Task` is
fully immutable today (every field `final`, no setters) - confirmed by reading the class.

**Root Cause:** Not introduced by this branch. Checked `chain/ruthHoltzman/dependency-repository-66`
directly: her original `InMemoryTaskRepository.save()` has the identical bug
(`tasksById.put(task.getId(), task); return task;`, no copy). The interface's Javadoc (also
authored on her branch) documents a guarantee her own implementation never honored. This branch's
hand-authored `InMemoryTaskRepository` rewrite (done only to fix `findByParentTaskId`'s
performance) faithfully preserved `save`'s existing behavior unchanged, since that wasn't the
part being touched - it neither introduced nor fixed the pre-existing gap.

**Decision: handed off to Ruth Holtzman, not this branch's problem to fix.** The interface
contract and the original implementation both originate on her branch; she owns the fix (either
implement the real defensive copy, or correct the Javadoc to state the weaker guarantee actually
provided). Shifra sent her the finding directly.

**Files Affected:** None on this branch - no change made here.

---

### Finding #8 — Formatting inconsistency in `GlobalExceptionHandler`

**Problem:** `GlobalExceptionHandler.java` uses 8-space indentation throughout (confirmed:
class-body members at 8 spaces, method parameters at 16) versus this project's consistent 4-space
style everywhere else (confirmed against `TaskNotFoundException.java` and others). No formatter/
linter config (`.editorconfig`, Checkstyle, Spotless) exists in the project to catch this
automatically.

**Root Cause:** The file was adopted wholesale from Riki's branch (commit `af303ce`), carrying her
original indentation style over unchanged - never run through a formatter after the copy.

**Decision: handed off to Riki, not this branch's problem to fix.** Same reasoning as Finding #7 -
the file's formatting originates on her branch, not from anything done in this task. Shifra sent
her the finding directly.

**Files Affected:** None on this branch - no change made here.

---

### Finding #9 — Stray no-op comment

**Problem:** `DuplicateDependencyException.java:2` has `//DuplicateDependency` directly under the
package statement, restating the class name with zero added information.

**Root Cause:** File adopted wholesale from Riki's branch (commit `af303ce`) - verified
byte-for-byte identical to her original, comment included.

**Decision: handed off to Riki, not this branch's problem to fix.** Same reasoning as Findings
#7/#8. Shifra sent her the finding directly.

**Files Affected:** None on this branch - no change made here.

---

### Finding #10 — Boolean-flag control coupling in `collectReachableIds`

**Problem:** `collectReachableIds(UUID startTaskId, Function<UUID, List<UUID>> neighborFn, boolean
directOnly)` toggles between two materially different traversal behaviors (single-level expansion
vs. full BFS) via an early `break` inside one method, called from all 6 direct/transitive
traversal methods (`getDirectDependencies`/`getAllDependencies`/etc., 3 pairs). Confirmed by
reading the method and all 6 call sites.

**Root Cause:** Original design choice made while implementing this task's traversal logic (not
inherited code) - collapsing the direct/transitive pair into one parameterized method to avoid
duplicating the BFS loop twice. Minor severity per the reviewer's own assessment ("not urgent
given current test coverage and documentation" - the existing Javadoc already documents both
modes clearly), but a real readability cost at each call site (`..., true)` / `..., false)` reads
opaquely without checking the method signature).

**Proposed Solution (decided):** Split into two methods sharing a private BFS-step helper, no
boolean flag anywhere:
```java
private List<UUID> expandNeighbors(UUID current, Function<UUID, List<UUID>> neighborFn,
                                    Set<UUID> visited, List<UUID> result) {
    List<UUID> discovered = new ArrayList<>();
    for (UUID neighbor : neighborFn.apply(current)) {
        if (visited.add(neighbor)) {
            result.add(neighbor);
            discovered.add(neighbor);
        }
    }
    return discovered;
}

private List<UUID> collectDirectNeighborIds(UUID startTaskId, Function<UUID, List<UUID>> neighborFn) {
    List<UUID> result = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    visited.add(startTaskId);
    expandNeighbors(startTaskId, neighborFn, visited, result);
    return result;
}

private List<UUID> collectAllReachableIds(UUID startTaskId, Function<UUID, List<UUID>> neighborFn) {
    List<UUID> result = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    visited.add(startTaskId);
    Deque<UUID> queue = new ArrayDeque<>();
    queue.add(startTaskId);
    while (!queue.isEmpty()) {
        queue.addAll(expandNeighbors(queue.poll(), neighborFn, visited, result));
    }
    return result;
}
```
Each of the 6 call sites switches from `collectReachableIds(taskId, fn, true/false)` to
`collectDirectNeighborIds(taskId, fn)` / `collectAllReachableIds(taskId, fn)` - self-documenting,
no flag to decode at the call site.

**Files Affected:** `src/main/java/com/rnd/chain/service/TaskTraversalService.java`.

**Dependencies:** None.

**Tests:** Existing `TaskTraversalServiceTest`/`TaskTraversalPerformanceTest` coverage for all 6
direct/transitive methods must continue to pass unchanged (behavior-preserving refactor).

**Acceptance Criteria:** `collectReachableIds` and its `boolean directOnly` parameter no longer
exist; all 6 traversal methods produce identical results to before, verified by existing tests.

**Risks / Trade-offs:** None - pure refactor, same algorithm, no behavior change.

---

### Finding #11 — Redundant existence checks in `getTrace`

**Problem:** `getTrace` calls `requireTask(taskId)` directly, then calls 7 sub-methods that each
call `requireTask(taskId)` again on the same id - 8 redundant existence checks for one logical
operation. Confirmed by reading `getTrace` and all 7 axis methods.

**Root Cause:** Original code for this task (not inherited) - `getTrace` was built by composing
the already-existing public axis methods (reusing the actual traversal logic, the right instinct),
at the cost of each of those methods re-validating existence independently.

**Impact assessed as negligible:** `requireTask` is an O(1) map lookup; the reviewer's own text
calls this "negligible runtime cost." Not a performance bug, purely a DRY/code-quality note.

**Decision: NOT acted on.** Discussed the fix shape (split each of the 7 axis methods into a
public UUID-validating wrapper + a private Task-accepting core, so `getTrace` can call the 7
private cores after its own single validation) and its cost: it *adds* code (7 methods become 14)
to save a already-negligible number of O(1) lookups. Concluded the added structural complexity
isn't worth it for a saving that's already assessed (by the reviewer) as immaterial.

**Files Affected:** None - no change made.

---

### Finding #12 — Single global write lock across all tasks/edges

**Problem:** Both `InMemoryTaskRepository` and `InMemoryTaskDependencyRepository` guard
`save`/`update`/`delete` with one coarse-grained `Object writeLock` per instance - every write
serializes against every other write, regardless of which task/edge it touches. Confirmed by
reading both classes.

**Root Cause:** Reasonable design choice for a read-only module - confirmed (again) that
`save`/`update`/`delete` are never called anywhere in this branch's production code, so no real
write contention exists today to motivate finer-grained locking. The Stage 6 performance
benchmark also never exercises the write path, so this limitation is unmeasured, not just
untested.

**Decision: document only, no code change.** The reviewer's own remediation asks for exactly
this - "Not blocking for this PR; flag as tracked tech debt for whoever builds the write
endpoints (per-key locking or a StampedLock/sharded-lock approach)" - not a code fix now. Recorded
here as the tracked note.

**Files Affected:** None - no change made.

---

### Finding #13 — Minor naming ambiguity in copied DTO

**Problem:** `estimatedDurationMin`/`getEstimatedDurationMin()` reads ambiguously as "minimum"
rather than "minutes." Present in both `Task.java` and `TaskResponse.java`.

**Root Cause:** `Task.java` is copied verbatim from `chain/ruthHoltzman/dependency-repository-66`
(confirmed by its own header comment) - the name originates there. `TaskResponse.java` is
original code for this task, but mirrors `Task`'s field name for consistency since it's built
directly from `Task` (`task.getEstimatedDurationMin()`).

**Decision: handed off to Ruth Holtzman for her `Task.java`; not acted on in this branch.** The
reviewer's own remediation explicitly says not to fix this unilaterally in this PR, since `Task`
is an unmodified import - renaming only `TaskResponse` would create a naming mismatch against the
source it mirrors. Shifra is flagging the ambiguity to Ruth for her `Task.java` specifically, not
proposing a coordinated rename that would also touch `TaskResponse.java` - that stays as-is for
now, independent of whatever Ruth decides on her side.

**Files Affected:** None on this branch - no change made here.

---
