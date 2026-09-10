# Chain Kafka Code Review Remediation Plan

**Scope:** audit and remediation plan only. No source, test, `pom.xml`, or `application.yml`
changes were made to produce this plan; only this file was written. Every finding below was
checked against the actual code on `yehudit/chain-event-infrastructure` (HEAD `92578c3`), not
assumed from the review text.

**Context:** this is PR #164's *third* round of review. Round 1 (`a4230a0`) added the Kafka
infrastructure. Round 2's nine findings (exception handling, idempotency race, publisher
fire-and-forget, DLT config duplication, config naming, in-memory-store safety) were addressed in
commit `4534c5a` — confirmed by direct code inspection below, not by trusting the PR comment that
claims it. This document replaces the previous `PLAN.md` (796 lines), which itself was the
round-1/round-2 remediation record and has since become exactly what this round's Finding 2
describes: stale session narrative next to durable content worth keeping. See Finding 2's action
item (§5) for what was kept and why.

---

## 1. Executive Summary

**Current situation:** all nine findings in this round are real to varying degrees, and none are
Critical or Major bugs — round 2 already fixed the two Critical issues (raw exception leakage,
idempotency race) and every fix is confirmed present and tested (32 tests pass on the reviewer's
own account, and the code inspected below matches that claim). This round is process hygiene
(Finding 1), documentation upkeep (Finding 2), and small consistency/robustness cleanups (Findings
3–9) on top of an already-correct implementation.

**Major confirmed risks:** none are urgent. The closest to a real risk is Finding 3 (three
inconsistent consumer-group-id defaults) — not a live bug (one value always wins today), but a
"why isn't my config change taking effect" trap for a future engineer.

**Two findings need a correction, not just a fix, because the reviewer's stated premise doesn't
fully hold once checked against the code:**
- **Finding 2** claims PLAN.md's durable content "is already correctly captured in README.md and
  Javadoc." Checked directly: **not fully true.** Three production classes carry Javadoc pointing
  at specific PLAN.md decisions (`ProcessedEventStore` → D2, `TaskSchedulingUpdatedPayload` → D5,
  `KafkaEventPublisher` → D6), and D5/D6's actual reasoning lives *only* in PLAN.md — README
  doesn't mention `priorityScore` or the Outbox analysis at all. Deleting the file as suggested
  would break those references and lose real content. The correct fix is trim, not delete — see §5.
- **Finding 9** claims the original exception's "stack trace [is] discarded." Checked directly:
  it isn't — `NonRetryableEventException`'s constructor is always called with the original
  exception as `cause` (`new NonRetryableEventException("...", e)`), so the full stack trace
  survives in the exception chain. The real, narrower problem — a misleading top-level message for
  someone triaging the DLT without digging into `getCause()` — still stands and is worth a small
  fix.

**Remediation strategy:** four phases, ordered by risk/dependency, not by finding number (see §4).
Repository/doc hygiene first (zero code risk), then configuration consistency (touches shared
config classes, do it as one pass), then the two small robustness/documentation fixes, then test
deduplication last (fully independent, purely mechanical).

**Important dependencies between fixes:** Findings 3 and 4 both touch
`ChainEventTopicsProperties.java` — do them together to avoid touching the same file twice.
Finding 1 (the platform upgrade) is **not fixable by code change** at this point — see §5 for why,
and why the recommendation is process-only.

---

## 2. Audit Results

| # | Finding | Severity | Status | Evidence | Recommended Action |
|---|---|---|---|---|---|
| 1 | Platform upgrade (Boot 2→3, Java 11→21) bundled into a feature PR | Major | CONFIRMED | `git show a4230a0 -- teams/chain/pom.xml` — the version bump is in this branch's very first commit, alongside the new Kafka files. `teams/bridge/pom.xml` on `main` is already 3.3.13/21 (real precedent), but `teams/inbox/pom.xml` on `main` is still 2.7.18/11 (repo is *not* uniformly upgraded) | Do not rewrite history (impractical and out of scope — see §5). Document the rationale in the PR description; treat as a process lesson for future PRs, not a code change here. |
| 2 | 796-line PLAN.md is a stale session log committed as module source | Major | PARTIALLY VALID | Read in full. ~70% is session narrative (exact SHAs, "no PR was opened," an "Implementation Status" update, a step-by-step 2A investigation log) that is already stale or will be the moment this branch merges. The other ~30% (six numbered decisions, D1–D6) is genuinely durable and is what production Javadoc points to by name | Replace with a short, durable-only document (this file). Keep D1–D6's *conclusions* under the same IDs so existing Javadoc references keep resolving; drop the narrative. See §5. |
| 3 | Consumer group id default duplicated across 3 sources | Major | CONFIRMED | `ChainEventTopicsProperties.consumerGroupId` field default `"chain-service"`; `@KafkaListener(groupId = "${chain.kafka.consumer-group-id:chain-service}")` same literal; `application.yml` sets `chain.kafka.consumer-group-id: ${spring.application.name}` → `"chain"`. `application.yml` ships in every build's classpath, so it always wins — the two `"chain-service"` literals are unreachable in practice today | Keep `application.yml`'s value as the one source of truth (this already matches D3 from the prior round). Drop the two now-dead `"chain-service"` literal defaults, or replace each with a one-line comment stating it's a last-resort fallback that must stay in sync with `application.yml`. |
| 4 | `ChainEventTopicsProperties` is a mutable JavaBean, unlike this PR's other DTOs | Minor | CONFIRMED | Class uses setters and returns `topics` (a `HashMap`) by reference with no defensive copy. **New evidence since the prior round:** three sibling `@ConfigurationProperties` classes in `teams/bridge` (`ChatSessionProperties`, `ScopingProperties`, `WhatsAppImportProperties`) are already records with constructor binding, one of them using `@DefaultValue` — a real, working precedent on the same Spring Boot/Java version, not just a theoretical option | Convert to a record matching Bridge's exact pattern: `@ConfigurationProperties(prefix = "chain.kafka") public record ChainEventTopicsProperties(Map<String,String> topics, @DefaultValue("") String consumedTopics, @DefaultValue("${spring.application.name}") String consumerGroupId) {}`. Confirm `@DefaultValue` accepts a property placeholder for `consumerGroupId`, or bind it without a default and rely on `application.yml`'s existing default instead. |
| 5 | Retry policy (`FixedBackOff(2000L, 2L)`) hardcoded | Minor | CONFIRMED | `ChainKafkaErrorHandlingConfig.java` — every other Chain Kafka tunable (topics, group id, store selection) is in `application.yml`; this one isn't. `FixedBackOff(interval, maxAttempts)` — `2L` here means 2 *retries after* the first attempt, i.e. 3 attempts total, matching the class's own Javadoc | Bind `chain.kafka.retry.max-attempts` (default `2`) and `chain.kafka.retry.interval-ms` (default `2000`) via a small nested properties record (record, per Finding 4's precedent), construct `FixedBackOff` from those. |
| 6 | `TestPayload`/`ItPayload` are duplicate classes | Minor | CONFIRMED | Read both in full — identical: one `String value` field, same getter/setter, same no-arg constructor. No `testsupport`/shared-fixture package convention exists anywhere else in the repo yet | Extract one shared class, e.g. `com.rnd.chain.events.testsupport.TestPayload`, referenced from both test classes. This introduces the pattern rather than following one — reasonable, since no repo convention exists to violate. |
| 7 | "Task identifiers are `java.util.UUID`..." paragraph duplicated in README | Minor | CONFIRMED | Read `README.md` in full — the paragraph appears twice, back-to-back, in the "Stack" section. No other duplication found in the surrounding section | Delete the second occurrence. One-line fix. |
| 8 | `EventEnvelope.equals()`/`hashCode()` (identity, by `eventId` only) undocumented at point of use | Minor | CONFIRMED | `EventEnvelope.java:59-69` compares only `eventId`; `EventEnvelopeTest.equals_returnsTrue_whenSameEventId` confirms this is intentional (two envelopes with different payloads assert equal). No comment on `equals()` itself explaining why | Semantics are correct and intentional (an envelope's identity *is* its event id — content changing across redelivery shouldn't mint a "different" event). Add a one-line comment on `equals()`. No behavior change. |
| 9 | Broad `catch (Exception e)` in `parseRoot`/`readPayload` blurs parse-bug vs. internal-bug triage | Minor | PARTIALLY VALID | Both methods already pass `e` as `cause` to `NonRetryableEventException` — the stack trace is **not** discarded, it's in the exception chain. The real, narrower issue: no log statement at the throw site, so an on-call engineer skimming DLT messages sees only the wrapped message, not immediately "this might be our bug, not bad producer data" | Keep the broad catch (correct for a single wrapped call — narrowing it would just be a longer list of the same rethrow). Add a `log.warn(..., e)` at each throw site so the full exception (type + stack trace) is visible in application logs without requiring someone to inspect `getCause()` on the DLT message. |

---

## 3. Decisions / Clarifications Required

**None.** Every finding above is resolvable from the repository itself — either it's a
straightforward mechanical fix (3, 4, 5, 6, 7, 8, 9), or the correct action is "don't change code,
document the reasoning" and the reasoning is fully determinable from git history and existing repo
conventions (1, 2). Nothing here turns on information only the team has.

The six decisions from the previous round (D1–D6) were genuine team-input decisions at the time;
they're preserved (not re-opened) in Finding 2's action item in §5, since production code still
references them by ID.

---

## 4. Phased Implementation Plan

### Phase 1 — Repository & Documentation Hygiene
**Objective:** stop shipping stale/misleading documentation; leave a clean, accurate record for
the next reviewer. Zero production-code risk.
**Changes:** Finding 2 (replace PLAN.md), Finding 7 (delete duplicate README paragraph), Finding 1
(PR description note — no file change).
**Dependencies:** none. Do first — it's the least risky and unblocks nothing else, but there's no
reason to sequence it later.
**Risks:** none beyond a bad edit to a markdown file.
**Verification:** re-read both files after editing; confirm every `PLAN.md decision D<n>` Javadoc
reference in the codebase (`ProcessedEventStore`, `TaskSchedulingUpdatedPayload`,
`KafkaEventPublisher`) still resolves to a real section in the new file.

### Phase 2 — Configuration Consistency
**Objective:** one source of truth per config value; `ChainEventTopicsProperties` matches this
PR's (and the repo's) established record-based `@ConfigurationProperties` style.
**Changes:** Finding 4 (convert to record), Finding 3 (drop dead literal defaults), Finding 5
(externalize retry policy — new nested properties record).
**Dependencies:** do 3 and 4 together — both touch `ChainEventTopicsProperties.java`. Finding 5 is
independent (different file) but same category of change; bundle for one focused review pass.
**Risks:** converting to a record changes the class from mutable to immutable — check for any test
or code calling a setter after construction (audited: none found in `KafkaEventPublisherTest` or
production code; the setters are only ever called once, at construction/binding time).
**Verification:** `mvn -pl teams/chain test`; specifically re-run `KafkaEventPublisherTest` (it
directly constructs and populates a `ChainEventTopicsProperties` instance) and
`ChainApplicationTests.contextLoads()` (confirms Spring can still bind the properties correctly as
a record).

### Phase 3 — Consumer Documentation & Triage
**Objective:** the two remaining `ChainEventConsumer`-adjacent findings that don't change behavior,
only clarity.
**Changes:** Finding 8 (comment on `EventEnvelope.equals()`), Finding 9 (log at throw site in
`parseRoot`/`readPayload`).
**Dependencies:** none; both are small, isolated, same general area of the codebase as Phase 1/2
but lower priority since they don't fix a real defect.
**Risks:** none — a comment and a log statement, no logic change.
**Verification:** existing tests continue to pass unmodified; manually confirm the new log lines
appear when running the existing malformed-message tests locally (optional, not required for
correctness).

### Phase 4 — Test Consolidation
**Objective:** remove the one piece of real test duplication.
**Changes:** Finding 6 (extract shared `TestPayload`).
**Dependencies:** fully independent of Phases 1–3; do last since it's purely mechanical and
touches only test files.
**Risks:** none, provided both test classes are updated to import the shared class and their
existing assertions (which only touch `getValue()`/`setValue()`) are unaffected.
**Verification:** `mvn -pl teams/chain -Dtest=ChainEventConsumerTest,ChainEventConsumerIntegrationTest test`.

---

## 5. Detailed Action Items

### Finding 1 — Platform upgrade bundled into the feature PR
**Problem:** `pom.xml`'s Spring Boot 2.7.18→3.3.13 and Java 11→21 bump rode in on the same commit
(`a4230a0`) as the new Kafka classes.
**Root Cause:** the author needed `spring-kafka`, which needs a compatible Boot line; rather than
landing the upgrade as its own preceding change, both went in together as the branch's first
commit.
**Proposed Fix:** **no code change.** `main..HEAD` on this branch now contains ~140 commits,
including several merges of `main` back into this branch (`ebf585d`, `92578c3`) — the upgrade is
deeply interleaved with unrelated history at this point. Splitting it out would mean rewriting
published history on a branch three more commits and two merges past the point in question, which
this task is explicitly not permitted to do and which carries real risk of corrupting the PR for
marginal bisectability benefit this late. The safe, correct move is: land it as-is, and describe it
honestly in the PR description — "aligns Chain with Bridge's existing Spring Boot 3.3.13/Java 21
(`teams/bridge/pom.xml`, already on `main`); Inbox has not yet upgraded, so this is not yet a
repo-wide standard, just a second module adopting it."
**Files Affected:** none (documentation-only, in the PR description, not a repo file).
**Tests:** none applicable.
**Acceptance Criteria:** PR description explicitly names the version bump and its rationale before
merge.
**Risks/Trade-offs:** the only real trade-off is accepting the current bisect/rollback friction as
a sunk cost. Recommend as a team norm going forward: land platform upgrades as their own commit
*before* starting feature work that depends on them, not after.

### Finding 2 — PLAN.md replacement
**Problem:** the previous `PLAN.md` mixed durable architectural decisions with session-specific
narrative (exact commit SHAs, "no PR was opened," a running "Implementation Status" log written
mid-session) in one 796-line file, committed as permanent module source.
**Root Cause:** the file was written progressively across an audit-then-implement workflow and was
never pruned back down to what's worth keeping once the work landed.
**Proposed Fix:** this document *is* the fix — replacing the old file in place at
`teams/chain/PLAN.md` (same location `teams/bridge/plan.md` already establishes as this repo's
convention for a module-root, code-referenced design doc — no `/docs/decisions/` ADR structure
exists anywhere in the repo, so inventing one here would violate this audit's own "reuse existing
patterns" rule). The prior document's six decisions are condensed below, keeping their original IDs
so every existing Javadoc `see PLAN.md decision D<n>` reference keeps resolving to real content:

| ID | Decision (condensed) | Resolution |
|---|---|---|
| D1 | Exception taxonomy for "not retryable" | `NonRetryableEventException extends RuntimeException`, registered via `DefaultErrorHandler#addNotRetryableExceptions`. Implemented and tested. |
| D2 | Idempotency guarantee without a database | `ProcessedEventStore.tryClaim` is atomic (`putIfAbsent`); claim is released on handler failure so transient errors still retry. Does **not** survive a process crash between claim and release/success — documented in `ProcessedEventStore`'s own Javadoc. Closing that gap needs a real persistence decision Chain hasn't made yet. |
| D3 | Consumer group id source of truth | `${spring.application.name}` via `application.yml`, not a hardcoded literal. (This round's Finding 3 is cleanup of now-dead fallback literals, not a re-opening of this decision.) |
| D4 | Repo-wide `.idea/` untracking | Out of scope for this PR — cross-team, root-level concern. **Still open**: a root `.gitignore` now exists (it didn't when D4 was written) but does not exclude `.idea/`, and `.idea/claudeCodeEditorTabs.xml`, `compiler.xml`, `encodings.xml`, `jarRepositories.xml` are still tracked as of this branch's HEAD. Not one of this round's 9 findings, so not actioned here — flagged so it isn't lost. |
| D5 | `task.scheduling.updated`'s `priorityScore` shape | Unresolved cross-team with Inbox (`Inbox_Kafka_Ingestion_Contract.md` §4 explicitly says so). Chain's `int priorityScore` is infrastructure-ready, not contract-approved. No action for Chain alone to take. |
| D6 | Outbox reuse for publisher reliability | Inbox's existing Outbox (`teams/inbox/.../OutboxWorker` etc.) is concretely Inbox-owned (own JPA entity, own event-type enum, own Postgres dependency) with no shared Java module in this repo. Not reusable as-is. Chain stays fire-and-forget, documented via `KafkaEventPublisher`'s TODO, until Chain adopts persistence. **Round 4 attempted a dormant reimplementation proactively (see §9) and it was deliberately reverted** — task #164's scope is Kafka publish/consume infrastructure only; persistence/outbox is a separate decision for whenever Chain actually adopts a database, not something to pre-build speculatively inside this PR. |

**Files Affected:** `teams/chain/PLAN.md` (this file, replacing the old one).
**Tests:** none applicable (documentation).
**Acceptance Criteria:** new file is materially shorter, contains no session-specific language
("no PR was opened," specific commit SHAs as narrative rather than citations, "Implementation
Status" play-by-play); every Javadoc `PLAN.md decision D<n>` reference in the codebase resolves to
a real entry in the table above.
**Risks/Trade-offs:** none — this is strictly a documentation change.

### Finding 3 — Consumer group id default cleanup
**Problem:** `"chain-service"` is hardcoded in two places (`ChainEventTopicsProperties`'s Java
field default, the `@KafkaListener` placeholder fallback) in addition to `application.yml`'s real,
always-active `${spring.application.name}` value.
**Root Cause:** the placeholder fallback was written defensively (in case the property is ever
unset) without noticing `ChainEventTopicsProperties` already carries its own, different-valued
default for the same property.
**Proposed Fix:** since `application.yml` ships in every environment and always sets this property,
the two literals are effectively dead. Either delete them (relying entirely on `application.yml`)
or keep them but make them agree with each other and comment that they exist only as a
last-resort fallback for a config that omits the property entirely. Recommend deletion — a missing
property should surface as an explicit binding failure or an obviously-wrong empty value, not
silently fall back to a third, undocumented default.
**Files Affected:** `ChainEventTopicsProperties.java`, `ChainEventConsumer.java` (the
`@KafkaListener` annotation's placeholder).
**Tests:** no new test needed — existing `ChainApplicationTests.contextLoads()` and any consumer
test exercising `@KafkaListener` binding already cover that the real (`application.yml`) value is
what's active.
**Acceptance Criteria:** `git grep '"chain-service"'` returns zero results under `teams/chain/`.
**Risks/Trade-offs:** if an environment is ever misconfigured to omit
`chain.kafka.consumer-group-id` entirely, behavior changes from "silently use chain-service" to
"bind with an empty/placeholder value" — arguably safer (fails loudly) but worth a one-line PR
description note.

### Finding 4 — `ChainEventTopicsProperties` as a record
**Problem:** mutable setters, `getTopics()` returns the live internal `Map` by reference.
**Root Cause:** written before this PR (or this repo) had an established `@ConfigurationProperties`
record pattern to follow.
**Proposed Fix:** convert to a `record`, matching `teams/bridge`'s
`ChatSessionProperties`/`ScopingProperties`/`WhatsAppImportProperties` exactly — same annotation
placement, `@DefaultValue` for the group-id/consumed-topics defaults (see Finding 3's
recommendation on what those defaults should be, if any are kept). Records have no setters, which
by itself closes the "future runtime-mutation" risk the reviewer named — an explicit
`Map.copyOf(...)` wrap is optional extra defense, not required, since nothing can call a setter to
begin with.
**Files Affected:** `ChainEventTopicsProperties.java`; `KafkaEventPublisher.java` (constructor
parameter type is already the properties class, no signature change — only the internal
`.getTopics()` call becomes `.topics()`).
**Tests:** `KafkaEventPublisherTest` constructs `ChainEventTopicsProperties` directly via
`setTopics(...)` — update to the record's constructor. No new test needed beyond that update.
**Acceptance Criteria:** class compiles as a record; `mvn -pl teams/chain test` passes unchanged in
outcome.
**Risks/Trade-offs:** none identified — no `@RefreshScope` or post-construction mutation exists
anywhere in the codebase today (checked).

### Finding 5 — Externalize retry policy
**Problem:** `new FixedBackOff(2000L, 2L)` is a code-level literal; every other Chain Kafka tunable
is in `application.yml`.
**Root Cause:** written once, alongside the rest of `ChainKafkaErrorHandlingConfig`, without
revisiting once the externalized-config pattern was established elsewhere in the same PR.
**Proposed Fix:** bind `chain.kafka.retry.max-attempts` (default `2`, meaning 2 retries after the
first attempt — 3 total, matching current behavior and this class's own Javadoc) and
`chain.kafka.retry.interval-ms` (default `2000`) via a small `@ConfigurationProperties(prefix =
"chain.kafka.retry") record ChainKafkaRetryProperties(@DefaultValue("2") long maxAttempts,
@DefaultValue("2000") long intervalMs)`, matching Finding 4's record pattern. Construct
`FixedBackOff` from its values in `chainKafkaErrorHandler`.
**Files Affected:** `ChainKafkaErrorHandlingConfig.java`; new
`ChainKafkaRetryProperties.java`; `application.yml` (add the two properties, defaulting to current
values so behavior is unchanged unless explicitly overridden).
**Tests:** no behavioral test needed since defaults preserve current behavior; if a test wants to
assert the values are read from config rather than hardcoded, bind with a non-default
`application-test.yml`/`@TestPropertySource` value and assert `FixedBackOff.getMaxAttempts()` /
`.getInterval()` reflect it.
**Acceptance Criteria:** no hardcoded `FixedBackOff(2000L, 2L)` literal remains; `application.yml`
carries both new properties with values matching today's behavior.
**Risks/Trade-offs:** none — additive, defaults unchanged.

### Finding 6 — Duplicate test payload classes
**Problem:** `TestPayload` (`ChainEventConsumerTest`) and `ItPayload`
(`ChainEventConsumerIntegrationTest`) are field-for-field identical.
**Root Cause:** copy-paste when the integration test was added, no shared test-fixture location
existed to reach for.
**Proposed Fix:** extract `com.rnd.chain.events.testsupport.TestPayload` (new package, mirroring
how `payloads/` already separates concerns) and reference it from both test classes, removing the
two nested class definitions.
**Files Affected:** new `src/test/java/com/rnd/chain/events/testsupport/TestPayload.java`;
`ChainEventConsumerTest.java`, `ChainEventConsumerIntegrationTest.java` (remove nested class,
import the shared one).
**Tests:** no new test scenarios — existing tests in both files continue to exercise the same
class, now shared.
**Acceptance Criteria:** `git grep -c "class TestPayload\|class ItPayload"` under
`teams/chain/src/test` returns exactly one class definition.
**Risks/Trade-offs:** none — pure refactor, no behavior change.

### Finding 7 — Duplicated README paragraph
**Problem:** the "Task identifiers are `java.util.UUID` for now..." paragraph appears twice,
back-to-back, in README's "Stack" section.
**Root Cause:** a merge/edit re-added the paragraph directly above where it already existed.
**Proposed Fix:** delete the second occurrence.
**Files Affected:** `teams/chain/README.md`.
**Tests:** none applicable.
**Acceptance Criteria:** paragraph appears exactly once.
**Risks/Trade-offs:** none.

### Finding 8 — Document `EventEnvelope`'s identity-based equality
**Problem:** `equals()`/`hashCode()` use only `eventId`; correct and intentional (confirmed by
`EventEnvelopeTest`), but undocumented at the point someone would actually need to know it.
**Root Cause:** the behavior was tested but never explained inline.
**Proposed Fix:** add, directly above `equals()`: `// Identity, not content equality: two envelopes
are "the same event" iff they share an eventId, regardless of payload.`
**Files Affected:** `EventEnvelope.java`.
**Tests:** none needed — behavior is unchanged, already covered by `EventEnvelopeTest`.
**Acceptance Criteria:** comment present; no test or behavior change.
**Risks/Trade-offs:** none.

### Finding 9 — Log the original exception at the throw site
**Problem:** `parseRoot`/`readPayload` catch broadly and rewrap, but don't log the original
exception at the throw site — only its `getMessage()` text ends up in the new exception's message
(the full exception is still preserved as `cause`, contrary to the review's "discarded" framing).
**Root Cause:** the wrap-and-rethrow pattern was applied consistently without adding a log call,
since the exception chain was assumed to be sufficient.
**Proposed Fix:** keep the broad catch (correct here — a single wrapped call, not a multi-step
block hiding different failure modes). Add `log.warn("Failed to parse message: {}",
e.getMessage(), e)` (or `readPayload`'s equivalent) immediately before each throw, so the full
stack trace is visible in application logs without requiring a DLT-message reader to inspect
`getCause()`.
**Files Affected:** `ChainEventConsumer.java` (`parseRoot`, `readPayload`).
**Tests:** none required for correctness; optionally assert (via a log-capture test utility, if the
repo has one — none found, so skip) that a log line is emitted, but this is genuinely optional
polish, not a correctness gap.
**Acceptance Criteria:** both methods log the original exception before throwing.
**Risks/Trade-offs:** none — additive logging only.

---

## 6. Out of Scope

- **Splitting the Boot/Java upgrade out of history** (Finding 1) — explicitly prohibited by this
  task's rules, and impractical given ~140 intervening commits including two merges of `main`.
- **Migrating `TaskSchedulingUpdatedPayload`'s `priorityScore` shape** (D5) — blocked on a
  cross-team decision with Inbox, not something Chain can resolve unilaterally.
- **Repo-root `.idea/` untracking and gitignore fix** (D4) — cross-team, root-level concern, not
  specific to this PR; also not one of this round's 9 findings.
- **Removing the four still-tracked `.idea/*.xml` files this branch's first commit added** — this
  was Finding 9d from the *previous* round's plan and was left incomplete (only `misc.xml` was
  reverted). Not re-raised by this round's reviewer, so not actioned here, but noted so it isn't
  silently lost a second time.
- **Converging Chain's exception/retry architecture with Inbox's `TransientProcessingException`/
  `RetryTemplate` pattern** (D1) — already decided against, for a consumer that still isn't live in
  any environment (`chain.kafka.consumed-topics` remains unset).
- **Implementing an Outbox pattern for `KafkaEventPublisher`** (D6) — Chain has no database yet;
  reusing Inbox's existing Outbox isn't possible without one. Round 4 built a dormant version of
  this proactively and then reverted it (see §9, D6) — it's out of scope for task #164 specifically,
  not just blocked on infrastructure.
- **Testcontainers, or any integration-test infrastructure change** — `@EmbeddedKafka` is the
  established, working convention repo-wide for this task's scope.

---

## 7. Verification Plan

1. **Targeted unit tests:** `mvn -pl teams/chain -Dtest=ChainEventConsumerTest,KafkaEventPublisherTest,ChainEventConsumerIntegrationTest test`
   after Phases 2–4 — confirms the record conversion, retry externalization, and test-class merge
   didn't change any assertion's outcome.
2. **Integration tests:** `mvn -pl teams/chain -Dtest=ChainEventConsumerIntegrationTest test` — the
   existing `@EmbeddedKafka` retry/DLT round-trip tests are the only way to confirm
   `ChainKafkaErrorHandlingConfig`'s behavior end-to-end; re-run after Phase 2/3 changes since that
   class is touched in both.
3. **Full build:** `mvn -pl teams/chain test` — all tests (currently 32, per the reviewer's own
   account, confirmed plausible from the test files read) must stay green.
4. **Configuration verification:** after Phase 2, start the app locally (`mvn spring-boot:run`) and
   confirm `ChainEventTopicsProperties` binds correctly as a record (no
   `ConfigurationPropertiesBindException` at startup) — Spring Boot 3.3.13 supports this natively,
   but binding failures are a startup-time, not compile-time, risk worth a real run.
5. **Static check:** `git grep '"chain-service"'` and `git grep -c "class TestPayload\|class
   ItPayload"` under `teams/chain/` (Findings 3 and 6's acceptance criteria) — cheap, exact checks.
6. **Documentation verification:** grep the codebase for `PLAN.md decision D` and manually confirm
   each hit (`ProcessedEventStore.java`, `TaskSchedulingUpdatedPayload.java`,
   `KafkaEventPublisher.java`) resolves to a real entry in the new PLAN.md's D1–D6 table (Finding
   2's acceptance criteria).
7. **PR/git verification:** confirm the PR description includes the platform-upgrade rationale
   (Finding 1) and the `priorityScore`/Outbox caveats (already true per the current PR description,
   re-verify after any edits).

No static analysis tooling (Checkstyle/Spotless/PMD) is configured in `teams/chain/pom.xml` — none
to run.

---

## 8. Review Finding Traceability

| Review Finding | PLAN Section | Files | Tests | Final Status |
|---|---|---|---|---|
| [MAJOR] Unrelated platform upgrade bundled into a feature PR | §5 Finding 1, Phase 1 | None (PR description only) | N/A | CONFIRMED — documented, not code-fixed (see rationale) |
| [MAJOR] Permanent, session-specific audit document committed as module source | §5 Finding 2, Phase 1 | `teams/chain/PLAN.md` | N/A | PARTIALLY VALID — trimmed and kept, not deleted |
| [MAJOR] Consumer group ID default duplicated across three inconsistent sources | §5 Finding 3, Phase 2 | `ChainEventTopicsProperties.java`, `ChainEventConsumer.java` | Existing `ChainApplicationTests` | CONFIRMED |
| [MINOR] Mutable, JavaBean-style `@ConfigurationProperties` | §5 Finding 4, Phase 2 | `ChainEventTopicsProperties.java`, `KafkaEventPublisher.java` | `KafkaEventPublisherTest` (update) | CONFIRMED |
| [MINOR] Retry policy magic numbers not externalized | §5 Finding 5, Phase 2 | `ChainKafkaErrorHandlingConfig.java`, new `ChainKafkaRetryProperties.java`, `application.yml` | None new (defaults preserve behavior) | CONFIRMED |
| [MINOR] Duplicate identical test payload classes | §5 Finding 6, Phase 4 | New `testsupport/TestPayload.java`; `ChainEventConsumerTest.java`, `ChainEventConsumerIntegrationTest.java` | Existing tests, updated imports | CONFIRMED |
| [MINOR] Duplicated paragraph in README.md | §5 Finding 7, Phase 1 | `teams/chain/README.md` | N/A | CONFIRMED |
| [MINOR] Identity-based `equals()`/`hashCode()` undocumented | §5 Finding 8, Phase 3 | `EventEnvelope.java` | None (behavior unchanged) | CONFIRMED |
| [MINOR] Overly broad `catch (Exception e)` blurs triage | §5 Finding 9, Phase 3 | `ChainEventConsumer.java` | None (logging only) | PARTIALLY VALID — cause was already preserved; added logging for triage clarity |

---

## 9. Round 4 — PR Review Findings

**Context:** ownership of this PR transferred after Yehudit left the team. This round covers a
fourth round of PR review: 9 findings on `ChainEventConsumer`/`KafkaEventPublisher`/their tests.
Every finding was verified against the actual code before being actioned, same discipline as
Rounds 1–3.

### Findings 1–8: review remediation

| # | Finding | Severity | Files | Resolution |
|---|---|---|---|---|
| 1 | `readPayload()` had no `hasNonNull("payload")` guard, unlike the other three envelope-field readers. A missing/null `payload` deserialized to Java `null` (Jackson's `treeToValue` returns `null` for a `null` `TreeNode`, no exception), which reached `EventEnvelope`'s constructor *after* `tryClaim()` had already succeeded — the resulting `NullPointerException` was never caught, so the claim leaked permanently and a redelivery saw "already claimed," committing the offset with the malformed message silently dropped, never reaching the DLT | Critical | `ChainEventConsumer.java` | Added the same `hasNonNull` guard the other three readers already had, throwing `NonRetryableEventException` before any claim is taken |
| 2 | `ChainEventTopicsProperties.topics` has no `@DefaultValue`; if ever bound to `null` (works today only because `application.yml` always sets a value), `KafkaEventPublisher`'s constructor stored that `null` directly, so `publish()` threw a raw `NullPointerException` instead of `resolveTopic()`'s own `EventPublishException` | Major | `KafkaEventPublisher.java` | Null-safe: `Map.copyOf(...)` when non-null, `Map.of()` otherwise |
| 3 | Findings 1 and 2's exact gaps had no covering test, despite an otherwise-thorough suite covering every sibling case | Major | `ChainEventConsumerTest.java`, `KafkaEventPublisherTest.java` | Added `onMessage_throwsNonRetryableException_whenPayloadIsMissing` and `publish_throwsEventPublishException_whenTopicsMapIsNull` |
| 4 | `parseRoot`/`readPayload` caught the broad `Exception`, though `readTree`/`treeToValue` only declare `JsonProcessingException` — an unrelated `RuntimeException` (a real bug) would be silently reclassified as "malformed message" and skip straight to the DLT instead of being retried | Minor | `ChainEventConsumer.java` | Narrowed both catches to `JsonProcessingException` |
| 5 | The `hasNonNull(field)`-or-throw shape was duplicated across all four envelope-field readers | Minor | `ChainEventConsumer.java` | Extracted `requirePresent(root, field)`, called from all four |
| 6 | `KafkaEventPublisher.eventTypeToTopic` held Spring's binder-produced `Map` by reference, no defensive copy | Minor | `KafkaEventPublisher.java` | Covered by Finding 2's `Map.copyOf(...)` fix — no separate change |
| 7 | Integration test hand-rolled a `Thread.sleep(100)` polling loop instead of using Awaitility, which is already a transitive test dependency via `spring-boot-starter-test` (confirmed: present in the local `.m2` cache, no `pom.xml` change needed) | Minor | `ChainEventConsumerIntegrationTest.java` | Replaced `awaitTrue(...)` with `Awaitility.await()...untilAsserted(...)`; deleted the helper |
| 8 | `whenEventTypeIsMissing` was missing `verify(handler, never()).handle(any())`, present on its three sibling required-field tests | Minor | `ChainEventConsumerTest.java` | Added the assertion |

Finding 9 from the reviewer (D6's dual-write risk: `KafkaEventPublisher.publish()`'s
fire-and-forget contract, well-documented via its own Javadoc/TODO and PLAN.md D6) needed no code
change for this PR — the ask was to confirm a tracked issue (GitHub/Jira) exists for it outside
this markdown file, separate from the code comment, so it survives this branch merging. **Not
independently verifiable from the repository** — flagged for the team to confirm or create.

### Outbox groundwork — built proactively, then reverted (out of scope)

A dormant transactional-outbox subsystem (entity, repository, worker, cleanup job, Flyway
migration, Postgres/JPA/Testcontainers dependencies — mirroring Inbox's own outbox pattern) was
built proactively in this round, ahead of Chain adopting a database. It was **reverted in full**
(commit `a5fc1d1`): task #164's scope is Kafka publish/consume infrastructure only, and
persistence/outbox is a separate decision for whenever Chain actually adopts a database — not
something to pre-build speculatively inside this PR, and it added real Docker/Testcontainers
review and CI overhead for a feature nothing called yet (no business entity exists in Chain's
database today to write alongside an outbox row).

D6 (§2) still reflects the standing decision: Chain stays fire-and-forget until it has persistence.
If/when Chain adopts a database for real, `a5fc1d1`'s parent commits (`873e5cf`..`2f1965e` on this
branch's history) are a working reference implementation to reimplement from — not to resurrect
as-is, since requirements may differ once Chain has a concrete entity to wire it to.
