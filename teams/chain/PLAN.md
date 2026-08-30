# PLAN.md — Chain Kafka Event Infrastructure: Review Remediation Plan

**Scope of this document:** audit and remediation plan only. No source code was modified to produce
this plan. No PR was opened. All findings below were checked against the actual repository, not
assumed from the reviewer's description.

**Branch audited:** `yehudit/chain-event-infrastructure` (origin), HEAD `63e2e26`, on top of
`a4230a0` ("add Kafka event infrastructure for Chain ... (#113)"). This branch is **not yet merged
into `main`** — `git log main..HEAD` shows both commits are only on this branch.

---

## 1. Executive Summary

The PR adds a first, genuinely reusable Kafka publish/consume skeleton for Chain: a generic
`EventEnvelope<T>`, an `EventPublisher`/`KafkaEventPublisher` pair, a `ChainEventConsumer` that
routes by `eventType` to `EventHandler<T>` beans, a `ProcessedEventStore` abstraction with an
in-memory implementation, and a `DefaultErrorHandler`/DLT wiring. The shape is right and the code
is honest about its own gaps — three of the four TODO comments in this PR are the *right* TODOs
(envelope shape pending, in-memory store not production-safe, taskId type pending Inbox's decision).

The review is largely correct, but two of its assumptions do not hold once checked against the rest
of the monorepo, and one of its "undocumented" claims is not actually true:

- **The Spring Boot 3 / Java 21 upgrade is not invented.** `teams/bridge/pom.xml` is *already* on
  Spring Boot 3.3.13 / Java 21. Chain is simply catching up to an upgrade another team already made
  on `main`-adjacent branches. It should still be a separate commit (process hygiene), but it is not
  an architectural surprise.
- **An Outbox pattern already exists in this monorepo, but it is not reusable by Chain as built.**
  It lives entirely inside `teams/inbox` — its own JPA entity, its own scheduled worker, its own
  event-type enum, its own `EventPublisher` — with no shared Java module backing it. Even the
  branch named `yehudit/bridge-outbox-integration` turns out, on inspection, to add more Inbox-
  internal code (`teams/inbox/.../TaskService.java`, `OutboxEvent.java`), not anything in
  `teams/bridge`, which still has zero database or Outbox code of its own. Full analysis in §2A.
  The reviewer's instinct (fire-and-forget publish is a real gap) is right; "add Outbox to this PR"
  is not the correct remediation — not because Outbox is being handled elsewhere as a clean shared
  service, but because copying Inbox's specific implementation isn't possible without first giving
  Chain a database it doesn't have, and re-deriving a Chain-specific Outbox from scratch is a bigger
  scope decision than this remediation PR should absorb.
- **There is a real, unmerged, in-progress cross-team contract document** for exactly the two things
  the review calls "invented not-yet-approved contracts": the envelope's field naming, and
  `task.scheduling.updated`'s payload. `teams/inbox/docs/Inbox_Kafka_Ingestion_Contract.md` (on
  `esti/inbox-kafka-ingestion-contract`) confirms Chain's camelCase field naming is correct, and
  **explicitly flags `priorityScore` as an unresolved cross-team question** — not settled, not
  Chain's to decide alone. This upgrades several "DECISION REQUIRED" items from guesswork to
  "here is the actual open question on record."

The two Critical findings (#1 exception handling, #2 idempotency race) are both **confirmed real**
against the code, and Inbox's parallel Kafka stack (`TransientProcessingException`,
`RetryTemplate`, DB-unique-constraint idempotency) gives a working precedent for the *shape* of the
fix — without requiring Chain to copy Inbox's architecture wholesale, which would be over-engineering
for a component that currently has no live topic to consume from at all (`chain.kafka.consumed-topics`
is unset, so `ChainEventConsumer` is disabled by `@ConditionalOnProperty` in every environment today).

The most important architectural risk is not any single finding — it's that **none of this has ever
been run**. `teams/chain/pom.xml` has no `spring-kafka-test` dependency, there is no `@EmbeddedKafka`
test anywhere in Chain, and this sandbox has no network access to verify a build. Phase 1 of the
implementation order (below) starts with confirming the module currently builds and its existing
tests currently pass — before changing anything.

---

## Implementation Status (update — Phases 1–6 implemented)

All code changes described in Phases 1–6 have been written and are sitting in the working tree on
`yehudit/chain-event-infrastructure`, **uncommitted** — nothing has been committed or pushed; that
stays the user's own step, per this session's established workflow. Two things changed from the
plan as originally written, both worth reading before running `mvn test`:

1. **`ProcessedEventStore` grew a second method, `release(UUID eventId)`, not just `tryClaim`.**
   Implementing the plan's original single-method design (claim once, keep the claim forever)
   turned out to silently break the *existing* transient-retry guarantee: `DefaultErrorHandler`
   retries by re-invoking the same listener method for the same message, so a permanent claim taken
   before calling `handler.handle()` would make the *second* retry attempt see "already claimed" and
   skip the handler entirely — swallowing a transient failure as a silent no-op instead of retrying
   it. The implemented fix claims right before calling the handler and **releases the claim if the
   handler throws**, so a retry (same-delivery or a later redelivery) can claim and attempt the
   event again. This preserves Phase 1's "transient failures keep retrying exactly as they do today"
   goal, which the plan's originally-described design would not have. The crash-after-claim gap (D2)
   is now narrower and more accurate than originally written: it only applies to a process crash
   between claiming and releasing/succeeding, not to any handler exception.
2. **Finding 9d was corrected during implementation** — see the updated table row and Phase 6.4
   above. Only `.idea/misc.xml` was a pre-existing tracked file this PR modified; the other four
   `.idea/*.xml` files are new files this PR's own commit added. `misc.xml`'s content has been
   restored to match `main` directly in the working tree; the other four still need `git rm` as part
   of finalizing this PR's commits (not done automatically, since deleting files was outside the
   scope of what this pass touched).

**Both items flagged as unverified have now been verified on a real machine — `mvn -pl teams/chain
test` passes, all 32 tests green:**
- `ChainKafkaErrorHandlingConfig`'s `KafkaTemplate<String, String>` parameter (finding 4b) compiles
  cleanly against `DeadLetterPublishingRecoverer`'s constructor — no change needed.
- `ChainEventConsumerIntegrationTest.java` needed two fixes once actually compiled/run against this
  project's real `spring-kafka-test` version:
  1. `KafkaTestUtils.getRecords(Consumer, long)` doesn't exist on this version — only the
     `Duration`-based overload does. Fixed by wrapping the three millisecond timeouts in
     `Duration.ofMillis(...)`.
  2. The class-level `@DirtiesContext` was left at its default `classMode` (`AFTER_CLASS`), so all
     three `@Test` methods were sharing one embedded broker, one consumer group, and one running
     listener container for the whole class — one test's retry/DLT activity could leak into the
     next test's assertions. Fixed by setting
     `classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD` so each test gets an isolated
     Spring context.

**Separately, an environment-only issue showed up on the verifying machine, unrelated to this PR's
code:** its only installed JDK is 25, and the Mockito/Byte Buddy version pulled in transitively can't
instrument Java 25 bytecode yet (`"Java 25 (69) is not supported by the current version of Byte
Buddy"`) — this affected `KafkaEventPublisherTest`'s mocks specifically. The workaround is the JVM
flag `-Dnet.bytebuddy.experimental=true`, passed at test-run time; with it, all 4 previously-erroring
`KafkaEventPublisherTest` cases pass cleanly. A permanent fix (adding that flag to
`teams/chain/pom.xml`'s surefire `<argLine>`) is a project-config decision left to the user — not
applied here, and not a statement about which Spring Boot version Chain targets (that stays 3.3.13,
per §2 above; the Byte Buddy version is a transitive test-scope dependency, independent of that).

All 32 tests pass. The remaining pending step is finalizing the commit (see Phase 6.4 for the
`.idea` file cleanup still outstanding) — not further code changes.

---

## 2. Repository Findings

**Chain module today** (`teams/chain`): Spring Boot 3.3.13, Java 21 (already on this branch), single
`KafkaTemplate<String, String>` auto-configured by Spring Boot Kafka autoconfiguration from
`application.yml`'s `spring.kafka.producer.key/value-serializer: StringSerializer`. No JPA, no
database — Chain is still 100% in-memory (`teams/chain/CLAUDE.md`, confirmed in an earlier session
on `ShifraAisenstain`). `ChainEventConsumer` is gated by
`@ConditionalOnProperty(prefix = "chain.kafka", name = "consumed-topics")`, and
`chain.kafka.consumed-topics` is **not set anywhere** in `application.yml` — so today, in every
environment, the consumer bean does not exist and no `@KafkaListener` is ever registered. This
matters for severity throughout: several findings describe risk that is real *once the consumer goes
live*, but is not yet live.

**No CI exists in this repository.** `.github/workflows` has no files at all. There is no automated
gate on Java version, tests, or anything else. This changes the calculus on "should the version
bump ride in this PR" (there is no CI matrix to break) but also means there is currently no safety
net catching any of this — which raises the value of the tests this plan adds.

**No root `.gitignore` exists.** `.idea/` is tracked at the repo root from *before* this PR (8
files already tracked pre-existing). This PR's working tree modified 5 of those already-tracked
files (`claudeCodeEditorTabs.xml`, `compiler.xml`, `encodings.xml`, `jarRepositories.xml`,
`misc.xml`) — almost certainly IntelliJ re-writing them on project open, not a new file added to
tracking. `teams/chain/.gitignore` already excludes `.idea/` for the module itself; the root-level
tracking is pre-existing, repo-wide technical debt this PR incidentally touched.

**Sibling teams already have working Kafka infrastructure — on unmerged branches, not `main`.**
Neither Inbox's Kafka stack nor the Outbox implementation nor the ingestion contract doc is in
`main` yet. They are real, working, tested code (with `@EmbeddedKafka` integration tests), but they
are **not an authoritative merged convention** — treat them as the strongest available precedent,
not as law Chain must follow to the letter.

Key precedents found (all on `origin/inbox-team` unless noted):

| Convention | Where established | What it does |
|---|---|---|
| Retryable vs. non-retryable exception split | `KafkaConfig.kafkaRetryTemplate` + `TransientProcessingException` | `SimpleRetryPolicy` retries **only** `TransientProcessingException`; everything else fails fast |
| Consumer-side integration testing | `spring-kafka-test` + `@EmbeddedKafka` (not Testcontainers) | `BridgeTaskConsumerIntegrationTest`, `KafkaEventPublisherIntegrationTest` |
| Producer retry with backoff | `teams/inbox/.../event/KafkaEventPublisher.java` | Manual `ScheduledExecutorService` retry loop, classifies `RetriableException` from the cause chain |
| Consumer-side idempotency | `Inbox_Kafka_Ingestion_Contract.md` §6 | A **DB unique index** on the natural key — atomic by construction, not a check-then-act flag |
| Outbox pattern | `Implement-Transactional-Outbox-for-Reliable-Kafka-Publishing(#157)` branch | `OutboxEvent` entity + `OutboxWorker` polling job, Postgres-backed, Inbox/Bridge-specific |
| Java 21 records | *(searched all of `origin/inbox-team`, `main`, this branch)* | **Not used anywhere in the repo yet** — this PR would be the first |
| `@Autowired` on `@Bean` parameters | `teams/inbox/.../config/KafkaConfig.java` and others | Never used — parameters are injected implicitly, no annotation |

**The cross-team contract document** (`teams/inbox/docs/Inbox_Kafka_Ingestion_Contract.md`, draft,
unmerged) is the single most load-bearing piece of evidence in this audit. Its §4 is *specifically
about* `task.scheduling.updated` and states, verbatim: "the INBOX consumer for this topic is not
built yet" and "`priorityScore` shape not final ... don't build or publish against a guessed shape
here — get the decision recorded in the spec first." Chain's `TaskSchedulingUpdatedPayload` already
guessed a shape (`int priorityScore`). That predates this PR and is out of this PR's scope to fix,
but it is directly relevant to whether `task.scheduling.updated` publishing should be presented as
"done" — it should be presented as "infrastructure-ready, payload shape not yet cross-team-approved."

---

## 2A. Outbox Reusability Analysis

Requested explicitly as a follow-up before finalizing the Outbox decision: is the existing Outbox
implementation actually reusable by Chain, or does it just exist "somewhere in the repo"? Inspected
on `origin/Implement-Transactional-Outbox-for-Reliable-Kafka-Publishing(#157)` (the source of the
merged-into-`inbox-team` implementation) and `origin/yehudit/bridge-outbox-integration` (the
branch name that most directly implies cross-team reuse).

### Module boundaries and ownership

Every Java file is under `com.rnd.inbox.*`, physically inside `teams/inbox/src/main/java`:
`model/entity/OutboxEvent.java`, `model/enums/OutboxStatus.java`, `repository/OutboxEventRepository.java`,
`service/OutboxWorker.java`, `service/OutboxCleanupJob.java`. There is no `shared/src/main/java`
anywhere in this repository — the only thing under `shared/` is `shared/database/migrations/` (SQL)
and `shared/database/pgadmin/`, `shared/database/seeds/` (non-Java). **There is no Java module a
second team could add as a Maven dependency to get this code.** Each team module
(`teams/chain`, `teams/inbox`, `teams/bridge`, ...) has its own independent `pom.xml` with no
parent POM tying them together (confirmed in §2: no root `pom.xml` exists) and no
`<dependency>` between team modules exists anywhere in the repo.

**Direct evidence it hasn't actually been reused, even by its own author:** `git log` on
`yehudit/bridge-outbox-integration` shows exactly two commits beyond the merged Outbox PR —
"Add pending Outbox row on Bridge task creation" (`21d539a`) and "Add AuditLog.record() factory and
fix tests" (`bfe57d5`). Both touch only `teams/inbox/src/main/java/com/rnd/inbox/...` (`TaskService.java`,
`OutboxEvent.java`, `AuditLog.java`). Despite the branch name, **no file under `teams/bridge` is
touched at all**, and `teams/bridge/pom.xml` has zero database/JPA/Hibernate/Flyway dependencies.
What "Bridge outbox integration" actually means is: Inbox's own `TaskService`, when it creates a
task that originated from Bridge's `bridge.structured.tasks` topic (see
`Inbox_Kafka_Ingestion_Contract.md` §2), now also writes a row to Inbox's own outbox table. This is
Inbox extending its own use of its own Outbox to one more of its own code paths — not Bridge (the
team/module) adopting Outbox as reusable infrastructure. That is the single strongest piece of
evidence for this section's verdict: if the same engineer who wrote Outbox didn't reach for it as
shared infrastructure when the obvious next opportunity came up, it is not designed as shared
infrastructure today, whatever the branch name suggests.

### Dependencies

`OutboxEvent.eventType` is typed as `com.rnd.inbox.event.EventType` — Inbox's own enum, with an
Inbox-specific `.getTopic()` method used by `OutboxWorker` to resolve where to publish. The column
is stored as `VARCHAR`, not a native Postgres enum, specifically (per the code's own comment)
*"because EventType is owned by a teammate's package... not defined at the DB level here"* — i.e.
the author already anticipated other things might read this table and deliberately kept the DB
schema from hard-coding Inbox's Java enum. That's a genuinely good instinct for future reuse, but it
only decouples the **schema**, not the **Java code that reads and writes it**. `OutboxWorker` also
depends on Inbox's own `EventPublisher`/`Event` interfaces (different types from Chain's
`EventPublisher`/`EventEnvelope` built in this PR — same concept, incompatible types), and
`SOURCE_SERVICE = "inbox"` is a compile-time literal, not configuration.

### Persistence model

Postgres-backed, via Spring Data JPA + Hibernate + a native `outbox` table
(`shared/database/migrations/V3__add_outbox_table.sql`) with a partial index for the worker's
pickup query and `FOR UPDATE SKIP LOCKED` row claiming (`OutboxEventRepository.claimPendingBatch`).
This is a solid, standard Outbox schema design. But: **Chain has no Postgres dependency at all**
(`teams/chain/pom.xml` — no JPA, no JDBC driver, nothing; confirmed again for this analysis) and
**no Postgres service in `teams/chain/docker-compose.yml`** (grepped — zero results), consistent
with the earlier-established fact that Chain is 100% in-memory today (`teams/chain/CLAUDE.md`,
`ProcessedEventStore`'s own TODO). Even setting aside code reuse entirely, Chain reusing *the table*
would mean Chain connecting to whatever Postgres instance Inbox uses — a shared-database-across-
services decision, which is a materially bigger architectural commitment than "add an Outbox," and
one this plan is not positioned to make unilaterally (it affects service boundaries and operational
ownership, not just Chain).

### Event model

`OutboxEvent.payload`/`metadata` are `Map<String, Object>` mapped via `JsonBinaryType` to `jsonb`
columns — untyped at the Java level, which is actually reuse-friendly in principle (any service
could stuff any JSON-serializable payload in). The blocker isn't the payload shape, it's everything
around it (`eventType`, `EventPublisher`, `SOURCE_SERVICE`) being Inbox-specific as covered above.

### Configuration

`OutboxWorker` takes `outbox.worker.batch-size` / `max-retries` / `backoff-base-seconds` /
`backoff-max-seconds` / `poll-interval-ms` as `@Value`-injected properties with defaults — these are
generic and would transfer cleanly to a second service. This is the one piece of the design that
*is* already reusable-shaped (externalized config, no Inbox-specific literals) — it just isn't
factored out from the class that also hardcodes `SOURCE_SERVICE` and depends on Inbox's `EventType`.

### Publishing mechanism

`OutboxWorker.pollAndPublish()` is `@Scheduled` + `@Transactional`, running inside Inbox's own
Spring application process. Chain runs as a **separate Spring Boot application** (`ChainApplication`,
its own JVM, its own `spring.application.name: chain`). A shared DB table — even if Chain could
write to one — does not give Chain a shared *worker process*; Chain would need its own
`@Scheduled` poller running inside Chain's own app either way. There is no sense in which "reuse
the Outbox" could mean "let Inbox's worker publish Chain's events" — that was never how this is
built, and would be a much stranger coupling (one service's background job responsible for another
service's business events) than anything under discussion here.

### Verdict: not reusable as built, and not because it's "elsewhere in scope" — because it's a
### service-owned implementation, not a library

Every layer — entity, repository, worker, event-type resolution, source-service literal — is
concretely Inbox's. Nothing is packaged for cross-service consumption, and the one team that has
since had an opportunity to reuse it (Bridge, via `yehudit/bridge-outbox-integration`) didn't reuse
the Java code either — it extended Inbox's own use of it instead. Reusing today's Outbox for Chain
is not a configuration or dependency-declaration exercise; it would require either duplicating the
~300-line entity/repository/worker/cleanup pattern inside `teams/chain` (real code, real tests, and
still blocked on Chain adopting Postgres/JPA first — itself a decision this plan can't make), or a
genuine extraction of a shared library (new Maven module, e.g. `shared/outbox` or similar, with its
own `pom.xml`, publishing a JAR both `teams/inbox` and `teams/chain` depend on, generalizing
`EventType`→topic resolution and `SOURCE_SERVICE` into constructor parameters instead of hardcoded
Inbox types) that does not exist today and that only Inbox's team can safely carve out of its own
running code without regressing it.

### Extraction vs. reimplementation — which is preferable, and when

**Not now, for Chain, as part of this remediation.** Two independent blockers make this a "later"
decision rather than a "which approach" decision: Chain has no database (a prerequisite for either
option), and there is exactly one existing real implementation to generalize from — extracting a
shared abstraction from a single concrete case tends to guess the wrong abstraction (the classic
"wait for three examples" argument), especially here where the two candidate reuse sites (Inbox
itself, and a hypothetical Chain) don't yet even agree on an event envelope shape (§8/D5).

**What would change this recommendation:** if/when Chain actually adopts a database (a decision
already flagged elsewhere in this plan as blocking multiple other items — see D2, `ProcessedEventStore`),
and a second service besides Inbox needs outbox-style reliable publishing, that is the natural
trigger to extract the reusable ~70% (schema shape, claim-batch query, worker polling/retry/backoff
loop) into a real shared module — at that point duplicating the pattern a second time from scratch
has a real, measurable cost (two independently-drifting implementations of the same correctness-
critical mechanism), which justifies the extraction investment in a way that a single, still-
hypothetical Chain use case doesn't yet. Until then, recommend Inbox's team keep the schema's
existing decoupling (VARCHAR `event_type`, generic `payload`/`metadata` JSON columns) as-is — it's
already quietly making a future extraction easier without costing anything today.

---

## 3. Code Review Finding Validation

| # | Finding | Severity (reviewer) | Status | Evidence | Recommended Action |
|---|---|---|---|---|---|
| 1 | Consumer exception handling: raw NPE/IllegalArgumentException/DateTimeParseException on malformed events, no not-retryable config, `throws Exception` | Critical | **CONFIRMED** | `ChainEventConsumer.java:45,64-85` — `root.get("eventType").asText()` on a missing field NPEs; `UUID.fromString`, `Instant.parse` throw unchecked on bad input; `ChainKafkaErrorHandlingConfig` has no `addNotRetryableExceptions` | Introduce a business exception for permanent parse/routing failures; register it as not-retryable; remove `throws Exception`. See Phase 1. |
| 2 | Idempotency check-then-act race (`isProcessed` → `handle` → `markProcessed`) | Critical | **CONFIRMED** | `ChainEventConsumer.dispatch:64-85`, `InMemoryProcessedEventStore.java:25-33` — two separate map operations, nothing atomic between them | Not a drop-in fix — see §4/Phase 2. Minimum viable fix (`putIfAbsent`-based atomic claim) closes the concurrent-duplicate case; the crash-after-success case needs a persistence decision that Chain hasn't made yet. |
| 3 | Publisher is fire-and-forget, dual-write problem, undocumented | Significant | **PARTIALLY CONFIRMED** | `KafkaEventPublisher.publish:32-47` — confirmed fire-and-forget (logged only, `EventPublisherTest.publish_doesNotThrow_whenKafkaSendFailsAsynchronously` explicitly asserts this). "Undocumented" is real (no TODO exists here, unlike every other gap in this PR). "No existing mitigation" needs a caveat: Outbox exists for Inbox, but per the full analysis in §2A it is Inbox-owned application code (own entity, own worker, own event types, own Postgres dependency) with no shared Java module — **not something Chain can currently adopt**, not "already solved elsewhere and this PR should just plug into it" | Do not add Outbox to this PR — it isn't a matter of plugging in, Chain would need a database first. Add the one paragraph of documentation the other TODOs already have, referencing §2A's verdict. Revisit Outbox-for-Chain once Chain has a database, per §2A's "what would change this recommendation." |
| 4a | `DeadLetterPublishingRecoverer`'s custom resolver duplicates Spring Kafka's default | Significant | **CONFIRMED** | `ChainKafkaErrorHandlingConfig.java:27-37` — the lambda `(record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())` is exactly `DeadLetterPublishingRecoverer`'s documented default destination resolver | Simplify to `new DeadLetterPublishingRecoverer(kafkaTemplate)`. Note a separate, real risk while touching this: partition-preserving DLT resolution fails if the `.DLT` topic doesn't have at least as many partitions as the source topic — worth a one-line comment either way. |
| 4b | `KafkaTemplate<Object, Object>` vs. the app's only real `KafkaTemplate<String, String>` | Significant | **CONFIRMED in source, functional impact NOT verified** | `ChainKafkaErrorHandlingConfig.java:29` requests `KafkaTemplate<Object, Object>`; `KafkaEventPublisher.java:24` requests `KafkaTemplate<String, String>`; `application.yml` only configures `StringSerializer`. Whether Spring's generic-aware autowiring actually resolves this today could not be verified — this sandbox has no network access to run `mvn` | Align both to `KafkaTemplate<String, String>`. Before any other change in this file, run `mvn -pl teams/chain test` on a machine with real Maven to confirm whether `ChainApplicationTests.contextLoads()` currently passes as-is — this tells you whether this is latent or already broken. |
| 4c | `@Autowired` on `@Bean` method parameter is redundant | Significant | **CONFIRMED** | `ChainKafkaErrorHandlingConfig.java:29` — Spring has injected `@Bean` method parameters without `@Autowired` since 4.3; no other config class in the repo (`teams/inbox/.../KafkaConfig.java`) uses it | Remove the annotation. Zero behavior change. |
| 5 | Undocumented Spring Boot 2→3 / Java 11→21 upgrade | Significant | **PARTIALLY CONFIRMED** | `git show a4230a0 -- teams/chain/pom.xml` — real upgrade, real diff. But `teams/bridge/pom.xml` is *already* on 3.3.13/Java 21 — this is not an invented target, Chain is catching up to a sibling module. No `javax.*` usage exists anywhere in `teams/chain` today (verified via repo-wide grep), so the `javax`→`jakarta` migration risk the reviewer names does not actually apply to this module | Still split into its own commit/PR for review hygiene and bisectability — but the PR description should say "aligns Chain with Bridge's existing Spring Boot 3.3.13/Java 21," not present it as a novel upgrade. See §11. |
| 6 | In-memory idempotency store has no production guardrail | Significant | **CONFIRMED** | `InMemoryProcessedEventStore.java` — plain `@Component`, no `@Profile`, no property gate, would be auto-wired into any environment including production as-is | Add a property-driven guard (see Phase 2/6). Low effort, meaningfully reduces the "silently wrong in prod" risk the TODO already names but doesn't prevent. |
| 7 | `chain.kafka.topics` (Map, bound) vs. `chain.kafka.consumed-topics` (inline String, unbound) asymmetry; hardcoded `groupId` | Significant | **CONFIRMED** | `ChainEventTopicsProperties.java` binds only `topics`; `@KafkaListener(topics = "${chain.kafka.consumed-topics:}", groupId = "chain-service")` — both the topic list and the group id are inline SpEL, not on any `@ConfigurationProperties` class | Fold both into one properties class; externalize `groupId` (recommend `${spring.application.name}-service` or a dedicated property — see Decisions Required). |
| 8 | Event contract / envelope structure | — | **DECISION REQUIRED / EXTERNAL CONTRACT** (see §4) | `EventEnvelope.java`'s own TODO already flags this as pending; `Inbox_Kafka_Ingestion_Contract.md` (draft, unmerged) confirms camelCase is the right call for field naming but leaves `priorityScore`'s shape explicitly open | Do not invent further. Chain's current camelCase choice is *correct* per the one document that addresses it — keep it. Do not change `TaskSchedulingUpdatedPayload`'s `priorityScore` type speculatively. |
| 9a | `eventType` extracted twice (routing, then logging) | Cosmetic | **CONFIRMED** | `ChainEventConsumer.onMessage` reads `root.get("eventType").asText()`, then `dispatch` reads it again from the same `root` | Pass it as a parameter from `onMessage` to `dispatch`. Trivial, zero risk. |
| 9b | `ProcessedEvent`/`TaskSchedulingUpdatedPayload` as records | Cosmetic | **PARTIALLY CONFIRMED — good candidates, but this is the first record in the repo** | Both are immutable, final-field, no-inheritance, plain data holders with no framework-serialization surprises (`ProcessedEvent` is never Jackson-(de)serialized; `TaskSchedulingUpdatedPayload` is Jackson-serialized on publish only — records serialize fine with Jackson's default introspection) | Safe to convert. Flag explicitly in the PR description that this establishes the repo's first use of records, since no other module has one yet — a one-line heads-up, not a blocker. |
| 9c | `Collectors.toMap` on duplicate handler registration throws a generic `IllegalStateException` | Cosmetic | **CONFIRMED** | `ChainEventConsumer.java:41-43` — no merge function supplied | Supply an explicit merge function that throws a named exception identifying both handler classes and the conflicting event type. |
| 9d | `.idea/*.xml` committed, likely missing root `.gitignore` | Cosmetic | **CONFIRMED (corrected during implementation)** | No root `.gitignore` exists (true). Checked precisely via `git show a4230a0 --stat`: of the 5 `.idea/*.xml` files this PR's commit touches, only `misc.xml` is a genuine modification of an already-tracked file (`10 +-` in the stat, confirmed present on `main`); the other 4 (`claudeCodeEditorTabs.xml`, `compiler.xml`, `encodings.xml`, `jarRepositories.xml`) are **newly added by this PR's own commit** (`git show main:<path>` fails with "not in main" for all four) — an earlier pass of this plan mischaracterized all 5 as pre-existing/only-modified; corrected here | `misc.xml` restored to `main`'s content directly in the working tree (done, bypassing a stuck local `.git/index.lock` from an interrupted git operation — see implementation notes). The other 4 are genuinely new files this branch introduced; since they're already committed (`a4230a0`), the fix is `git rm claudeCodeEditorTabs.xml compiler.xml encodings.xml jarRepositories.xml` (paths under `.idea/`) as part of finalizing this PR's commits, not a working-tree revert. |

---

## 4. Decisions Required

### D1 — What does "not retryable" mean for Chain's consumer, and what's the exception's name/hierarchy?
**Why it matters:** blocks Phase 1 entirely; changes retry/DLT behavior for every message.
**Options:**
- (a) One exception, e.g. `NonRetryableEventException extends RuntimeException`, thrown for
  malformed JSON / missing `eventType` / bad `eventId` / bad `occurredAt` / payload mapping
  failures; registered via `DefaultErrorHandler#addNotRetryableExceptions`. Stays inside Chain's
  existing Spring Kafka container-level retry architecture.
- (b) Mirror Inbox's `TransientProcessingException` + `RetryTemplate` shape exactly, inverting the
  polarity (retry only on a named transient exception instead of skip on a named permanent one).
**Recommended:** (a). It's a two-class change, keeps Chain on the Spring-native
`DefaultErrorHandler`/DLT mechanism it already has working, and doesn't require adopting Inbox's
whole `EventProcessingTemplate`/manual-ack architecture for a consumer that isn't live yet. Revisit
if/when Chain's Kafka stack needs to converge with Inbox's for real operational consistency.
**Consequences:** handler-level failures (e.g. a DB timeout inside `handler.handle()`, once handlers
exist) remain retryable by default, matching current behavior; only parsing/routing failures change.

### D2 — What idempotency guarantee is Chain actually promising, given it has no database yet?
**Why it matters:** the review's "fix" instinct (atomic claim) only closes the concurrent-duplicate
race. It does **not** close the crash-after-handle-before-mark case — nothing can, without
transactionally tying "mark processed" to whatever `handler.handle()` actually mutates, and Chain's
handlers don't exist yet (no `TaskCreatedEventHandler` etc. has been written).
**Why the repository doesn't already answer this:** `ProcessedEventStore`'s own Javadoc says the
storage technology is undecided; Chain doesn't have a `TaskRepository`-analogous persistent store
for anything yet outside the in-memory `TaskEligibilityService` work.
**Options:**
- (a) Minimum viable now: make the store's check-and-mark atomic (`ConcurrentHashMap.putIfAbsent`
  as an atomic *claim*, not a plain map write) — closes the "two threads, same event, concurrency >
  1" race precisely. Document plainly (in the same TODO style already used) that "processed" is
  claimed before, not after, business-side success is durable — so a crash mid-handle currently
  reprocesses on redelivery, which is safe only if handlers are themselves idempotent or
  side-effect-free on partial failure. That constraint doesn't exist yet because no handler exists
  yet — write it down now so the first real handler author sees it.
- (b) Defer any change and keep `isProcessed`/`markProcessed` as two calls, accepting the race,
  since `concurrency` is 1 by default and no topic is configured — ship purely as documentation.
- (c) Block on a persistence decision (DB-backed unique constraint, matching Inbox's proven
  approach) before writing a single handler.
**Recommended:** (a). It's a real, bounded improvement that costs one method rewrite, and it's
honest about what it does and doesn't guarantee — which is what Rule 6 asks for. (c) is the *right*
end state but shouldn't block this PR; the in-memory store already carries a TODO saying it isn't
production-ready.
**Consequences:** the store's return contract must change from "check, then separately mark" to a
single atomic "claim" call; `ChainEventConsumer.dispatch` needs a matching one-line change.

### D3 — Should the consumer group id be a dedicated Chain property or `${spring.application.name}`-derived?
**Why it matters:** affects every future consumer group Chain adds, and how ops/other teams read
Chain's Kafka footprint.
**Options:** dedicated `chain.kafka.consumer-group-id` property (defaulting to
`${spring.application.name}`) vs. hardcoded literal (current) vs. reusing `spring.application.name`
directly with no override property.
**Recommended:** a dedicated property defaulting to `${spring.application.name}`, so it's
overridable per-environment without a code change but doesn't silently drift if `application.name`
is ever repurposed for something else (e.g. a future multi-module split within Chain).
**Consequences:** touches `application.yml` and `ChainEventConsumer`'s `@KafkaListener` annotation.

### D4 — Repo-wide `.idea/` untracking and a root `.gitignore`
**Why it matters:** every team's PR will keep showing spurious `.idea/*.xml` diffs until this is
fixed once, at the root.
**Why this is not Chain's or this PR's decision alone:** the tracked files are shared repo-root
state (`.idea/Resonance-Flow.iml`, `.idea/modules.xml` etc. reference every module, not just Chain).
**Options:** (a) fix repo-wide in a separate, dedicated PR (add root `.gitignore`, `git rm --cached
.idea`); (b) leave as-is and just avoid touching `.idea/` files in future commits by being
disciplined about `git add`.
**Recommended:** (a), but as its own PR, not bundled here. This PR should only revert the 5
`.idea/*.xml` files it happens to have modified.
**Consequences:** none for this PR beyond a clean diff, once the revert is done.

### D6 — Reuse Inbox's Outbox, reimplement a Chain-specific one, or extract a shared module?
**Why it matters:** determines how (not whether — see §2A, this PR should not implement Outbox
regardless) Chain's eventual publish-reliability gap gets closed.
**Why the repository doesn't already answer this:** analyzed in full in §2A. Short version: Inbox's
Outbox is real, working, and Postgres-backed, but it is concretely Inbox's — its own JPA entity, its
own `EventType` enum, its own hardcoded `SOURCE_SERVICE`, no shared Java module backing it, and no
parent POM or inter-module dependency mechanism in this repo that would let Chain depend on Inbox's
code even if the classes were reusable in shape. Tellingly, the one branch whose name implies
cross-team reuse (`yehudit/bridge-outbox-integration`) turned out, on inspection, to add more code
inside `teams/inbox` rather than anything in `teams/bridge`.
**Options:**
- (a) Chain implements its own Outbox later, independently, once it has a database — duplicating
  the pattern (entity/repository/worker/cleanup), not the code.
- (b) Extract a shared Java module now (e.g. `shared/outbox`, its own `pom.xml`, published as a JAR
  both `teams/inbox` and `teams/chain` depend on) generalizing `EventType`→topic resolution and
  `SOURCE_SERVICE` into constructor parameters.
- (c) Do nothing until Chain has a concrete, funded need — keep fire-and-forget with logging as a
  documented, accepted tradeoff indefinitely.
**Recommended:** (a), deferred — and only once Chain actually adopts a database (unresolved
elsewhere in this plan, see D2). Extracting a shared module (b) from a single existing
implementation risks generalizing the wrong things (Chain and Inbox don't even agree on an event
envelope shape yet — §8/D5); it becomes the right call once a *second* real consumer of the pattern
exists and the duplication cost is measured, not projected. (c) is a reasonable interim state, not a
permanent one, given the dual-write problem the reviewer correctly identified.
**Consequences:** no code changes now either way. The PR description should state this reasoning
explicitly (per Phase 3.1) so nobody reads "Outbox exists in the repo" as "this PR should have used
it."

### D5 — `priorityScore`'s type/shape for `task.scheduling.updated`
**Why it matters:** `TaskSchedulingUpdatedPayload.priorityScore` is currently `int`. The one
document that discusses this field on record (`Inbox_Kafka_Ingestion_Contract.md` §4) states its
producer and semantics are **not yet agreed** between Chain and Inbox.
**This is `EXTERNAL CONTRACT REQUIRED`** — not something this plan or this PR can resolve. Flagging
it so nobody reads "Chain publishes `task.scheduling.updated`" as "the contract is final." No code
change is proposed here; this is a call-out for the PR description and for whoever owns the
cross-team spec conversation next.

---

## 5. Phase 1 — Critical Reliability & Error Handling

**Goal:** malformed/permanent-failure messages stop being retried and reach the DLT immediately;
transient failures keep retrying exactly as they do today; the consumer no longer leaks raw parser
exceptions across its public contract.

1. **Before touching anything:** run `mvn -pl teams/chain test` (and ideally `mvn -pl teams/chain
   spring-boot:run`) on a machine with real Maven/JDK 21, to get a real baseline — does
   `ChainApplicationTests` currently pass? This resolves D-4b's open "is this actually broken today"
   question before any other change lands on top of it.
2. Add `NonRetryableEventException extends RuntimeException` (per D1) to
   `teams/chain/src/main/java/com/rnd/chain/events/`, alongside the existing `EventPublishException`
   (same package, same style — a message + optional cause constructor).
3. In `ChainEventConsumer.onMessage`/`dispatch`, wrap each currently-unchecked failure point
   (`objectMapper.readTree`, `root.get("eventType").asText()` on a null node,
   `UUID.fromString(eventId)`, `Instant.parse(occurredAt)`, `objectMapper.treeToValue(payload, ...)`)
   so each throws `NonRetryableEventException` with a message naming which field/step failed. Do
   **not** wrap `handler.handle(event)` — a handler failure is exactly the transient case that
   should keep retrying.
4. Remove `throws Exception` from both `onMessage` and `dispatch` — now that every checked exception
   is caught and rethrown as the new unchecked type, this is possible cleanly.
5. In `ChainKafkaErrorHandlingConfig`, add
   `errorHandler.addNotRetryableExceptions(NonRetryableEventException.class)` (a genuine constructor/
   builder-level or setter call depending on final Spring Kafka API shape — confirm exact method
   name against the `spring-kafka` version pulled by Spring Boot 3.3.13 when implementing).
6. Fix D-4c (`@Autowired` removal) and D-4b (align to `KafkaTemplate<String, String>`) in the same
   file while it's open — same file, same phase, low risk to bundle.
7. Fix 4a (simplify the DLT resolver to the 2-arg `DeadLetterPublishingRecoverer` constructor) here
   too, since it's the same class and same review comment cluster. Add a one-line comment noting the
   partition-count caveat for future readers.

**Dependency:** none of Phase 2/3/4 requires this phase to land first at the code level, but it
should land first anyway — it's the highest-severity, lowest-risk, most self-contained change, and
every new consumer test written in Phase 5 should assert against the *new* exception type, not the
old raw ones.

---

## 6. Phase 2 — Idempotency & Concurrency

**Goal:** the concurrent-duplicate race (two threads, same event, both see "not processed") is
closed. The crash-after-handle race is explicitly documented as an open limitation, not silently
left unaddressed.

1. Change `ProcessedEventStore`'s contract from two methods (`isProcessed` / `markProcessed`) to one
   atomic claim method, e.g. `boolean tryClaim(UUID eventId)` returning `true` if this call is the
   one that transitioned the event from unclaimed to claimed (semantically a `putIfAbsent`).
   Keep `markProcessed`-equivalent info (the full `ProcessedEvent` record) by claiming with the
   `ProcessedEvent` payload directly: `boolean tryClaim(ProcessedEvent event)` backed by
   `ConcurrentHashMap.putIfAbsent(event.getEventId(), event) == null`.
2. Update `InMemoryProcessedEventStore` to implement `tryClaim` atomically via `putIfAbsent`.
3. Update `ChainEventConsumer.dispatch` to call `tryClaim` **before** invoking `handler.handle(...)`,
   using its boolean result in place of the current `isProcessed` check — this is the actual fix for
   the race the review describes (claim happens atomically before work starts, not after).
4. Document, in the class Javadoc (matching the existing TODO style), that a handler failure or
   process crash after a successful claim does **not** currently retry that event automatically from
   this store's perspective on redelivery — Kafka's own redelivery-on-uncommitted-offset is what
   provides the retry path, and this claim step will now (correctly) treat the redelivered copy as
   "already claimed" and skip it. **This is a real behavior change worth calling out explicitly to
   reviewers**: today (check-then-act), a crash after `isProcessed=false` but before `markProcessed`
   means the retry redelivers and reprocesses. After this fix (claim-then-act), the same crash means
   the retry redelivers and is **skipped** as already-claimed — i.e., the fix trades "the concurrent-
   duplicate bug" for "an event whose handler crashed after claiming it is never retried by this
   store." This is *not* worse than today's actual guarantee (today's guarantee was already broken
   under concurrency), but it is a different failure mode, and it's the direct consequence of D2
   not being fully resolvable without persistence. Flag this explicitly in the PR description.
5. Guard `InMemoryProcessedEventStore` against accidental production use (ties to D2/finding 6):
   add a property-driven `@ConditionalOnProperty` (e.g.
   `chain.events.processed-store: in-memory` as the default, matching value required to activate this
   bean) so a future persistent implementation can coexist and be selected by config, and so
   swapping the property to an unrecognized value with no matching bean fails application startup
   fast instead of silently defaulting to the unsafe implementation.
6. Concurrency tests (Phase 5) must actually exercise `concurrency > 1` semantics at the unit level —
   two threads racing `tryClaim` on the same `eventId`, asserting exactly one returns `true`.

**Dependency:** independent of Phase 1 at the code level (different files), but do Phase 1 first —
Phase 1 establishes the exception-handling pattern that Phase 2's tests should reuse for consistency.

---

## 7. Phase 3 — Publishing & Configuration

**Goal:** publisher failure is at least as observable/documented as every other known gap in this
PR; Kafka configuration stops being two asymmetric shapes under one prefix; the group id is
externalized.

1. **Outbox: do not implement.** Add the same style of TODO comment already used elsewhere in this
   PR to `KafkaEventPublisher`, explaining the fire-and-forget tradeoff. Per §2A/D6, be precise in
   the comment and PR description: Inbox's existing Outbox is not adoptable as-is (own entity, own
   worker, own event-type system, no shared module, and Chain has no database) — this is a "revisit
   once Chain has persistence" follow-up (option (a) in D6: reimplement the pattern, not import the
   code), not a "just wire in the existing one" task. This directly answers finding #3 without scope
   creep (Rule 4/Rule 5) and without overstating what's actually available to reuse.
2. Fold `chain.kafka.topics` and `chain.kafka.consumed-topics` into one properties shape. Recommend
   renaming the class to something that doesn't overclaim (`ChainEventTopicsProperties` today only
   covers publish topics) — either broaden it to genuinely own both directions
   (`publishTopics`/`consumedTopics` as two maps/lists on the same `@ConfigurationProperties(prefix =
   "chain.kafka")` class), or rename it to `ChainPublishTopicsProperties` and add a symmetrical
   `ChainConsumedTopicsProperties`. Broadening one class is simpler and keeps one binding prefix —
   recommended.
3. Implement D3: externalize the consumer group id as a property defaulting to
   `${spring.application.name}`, referenced from the new properties class or directly in
   `application.yml` + `@KafkaListener(groupId = "${chain.kafka.consumer-group-id}")`.
4. Re-verify `ChainEventConsumer`'s `@ConditionalOnProperty` still gates correctly once the consumed-
   topics property moves onto the broadened properties class (the property *name* the
   `@ConditionalOnProperty` checks must still exist even when unset, i.e. don't accidentally require
   the whole properties block to bind before the conditional evaluates).

**Dependency:** should follow Phase 1 (shares `ChainEventConsumer`) but is otherwise independent of
Phase 2.

---

## 8. Phase 4 — Event Contract & Handler Architecture

**Goal:** confirm (not redesign) the envelope and handler-registration behavior; fix the one real
correctness gap (duplicate handler registration) and the one real duplication (double `eventType`
extraction).

1. **Envelope:** no structural change. `EventEnvelope`'s existing TODO stays — it correctly flags
   that the final shape depends on cross-team agreement Chain doesn't control. Confirmed via §2:
   camelCase field naming is validated as correct by the one document that addresses it.
2. **`task.scheduling.updated`:** infrastructure (topic config, publish path, serialization) is
   ready and tested. The payload shape (`priorityScore` as `int`) is **not** cross-team-confirmed —
   see D5. Recommend the PR description say so explicitly rather than implying the contract is
   settled.
3. Fix `Collectors.toMap` duplicate-handler registration (finding 9c):
   ```
   handlers.stream().collect(Collectors.toMap(
       EventHandler::getSupportedEventType,
       h -> h,
       (first, second) -> { throw new IllegalStateException(
           "Duplicate EventHandler for event type [" + first.getSupportedEventType() + "]: "
           + first.getClass().getName() + " and " + second.getClass().getName()); }));
   ```
4. Fix double `eventType` extraction (finding 9a): have `onMessage` extract it once and pass it into
   `dispatch` as a parameter (dispatch already re-derives `eventId`/`eventType`/etc. from `root` a
   second time — pass `eventType` through, leave the rest as-is since they're only read once each
   already).
5. Unknown event type: current behavior (log + return, no retry, no DLT) is correct and already
   tested (`onMessage_ignoresMessage_whenEventTypeIsUnknown`) — no change needed, just confirming
   review didn't flag this as broken and neither do we.

**Dependency:** depends on Phase 1 only for the exception type used inside `dispatch` (both touch
the same method) — sequence Phase 1 first to avoid a merge conflict on the same lines, not because
of a logical dependency.

---

## 9. Phase 5 — Testing & Verification

**Established repo convention confirmed:** JUnit 5 + Mockito for unit tests; `spring-kafka-test` +
`@EmbeddedKafka` for Kafka integration tests (confirmed via `origin/inbox-team`'s
`BridgeTaskConsumerIntegrationTest`, `KafkaEventPublisherIntegrationTest`) — **not** Testcontainers,
which does not appear anywhere in this repository. Add `spring-kafka-test` (test scope) to
`teams/chain/pom.xml`; do not introduce Testcontainers.

**Unit tests (Mockito, existing style):**
- `InMemoryProcessedEventStoreTest` (currently missing entirely — new file): `tryClaim` returns
  `true` exactly once for a given `eventId` under sequential calls; a concurrency test with two
  threads racing `tryClaim` on the same `eventId` asserting exactly one wins (`ExecutorService` +
  `CountDownLatch`, matching the pattern already used in
  `TaskEligibilityServiceConcurrencyTest` elsewhere in this repo).
- Update `ChainEventConsumerTest`: replace `assertThrows(Exception.class, ...)` and
  `assertThrows(JsonMappingException.class, ...)` with `assertThrows(NonRetryableEventException.class,
  ...)`; add cases for missing `eventType` field (NPE today), non-UUID `eventId`, and unparseable
  `occurredAt` — all should now throw `NonRetryableEventException` with a message identifying the
  bad field.
- Update `KafkaEventPublisherTest`: add a serialization-failure case (currently entirely untested
  per finding 8/review) — e.g. a payload type that throws during Jackson serialization, asserting
  `EventPublishException` and that `kafkaTemplate.send` was never called.
- Add a duplicate-handler-registration test asserting the new named exception and that it names
  both conflicting classes.
- Add a multiple-event-types routing test if not already adequately covered (existing
  `onMessage_routesToCorrectHandler_whenMultipleHandlersAreRegistered` already covers this —
  confirm it still passes with the new exception type changes, no new test needed).

**Integration tests (new, `@EmbeddedKafka`):**
- A consumer integration test that publishes a real malformed message to an embedded topic and
  asserts it reaches the `.DLT` topic **without** retrying (verify via consumer poll on the DLT
  topic, and via a counter/spy asserting the handler or retry path was invoked exactly once, not
  three times) — this is the one thing no unit test can prove, since `DefaultErrorHandler`'s retry/
  DLT wiring only actually runs inside a real listener container.
- A second integration test for the transient-failure path: a handler that throws a plain
  `RuntimeException` (not the new non-retryable type) N times then succeeds, asserting it *does*
  retry (up to 3 attempts, ~2s apart, per the existing `FixedBackOff` config) before either
  succeeding or landing on the DLT if it never succeeds.
- A `task.scheduling.updated` publish-and-consume round-trip test against an embedded broker,
  confirming serialization/deserialization symmetry for the approved (camelCase) envelope shape.

**Idempotency tests:**
- Duplicate sequential event: publish the same `eventId` twice, assert the handler runs once.
- Duplicate concurrent event: covered by the `InMemoryProcessedEventStoreTest` concurrency case
  above at the store level; optionally also at the `ChainEventConsumer` level with two threads
  calling `dispatch` for the same envelope.
- Explicitly do **not** write a test asserting "idempotency survives a restart" — the in-memory
  store cannot provide that guarantee, and asserting it would be testing a false claim (Rule 6:
  don't claim idempotency is fixed unless the semantics are actually safe).

---

## 10. Phase 6 — Code Quality & Repository Hygiene

1. Convert `ProcessedEvent` and `TaskSchedulingUpdatedPayload` to Java `record`s (finding 9b) —
   confirmed safe candidates (see §3). Note in the PR description that this is the first record in
   the repository.
2. Fix 9a (duplicate `eventType` extraction) — see Phase 4.4, listed here too since it's a Phase
   4/6 boundary item; do it once, in Phase 4.
3. Remove the `@Autowired` on the `@Bean` parameter (finding 4c) — see Phase 1.6.
4. `.idea/misc.xml`'s working-tree content has already been restored to match `main` directly (a
   pre-existing tracked file this PR modified). `.idea/claudeCodeEditorTabs.xml`,
   `.idea/compiler.xml`, `.idea/encodings.xml`, and `.idea/jarRepositories.xml` are genuinely new
   files this PR's commit (`a4230a0`) introduced (confirmed — not on `main` at all) — remove them
   with `git rm .idea/claudeCodeEditorTabs.xml .idea/compiler.xml .idea/encodings.xml
   .idea/jarRepositories.xml` as part of finalizing this PR's commits (finding 9d, corrected).
5. Do **not** add a root `.gitignore` or untrack `.idea/` repo-wide as part of this PR — that's D4,
   a separate cross-team decision and PR.

---

## 11. Exact Files Affected

| File | Change | Reason | Phase |
|---|---|---|---|
| `teams/chain/src/main/java/com/rnd/chain/events/NonRetryableEventException.java` | **New file** | Business exception for permanent parse/routing failures (D1) | 1 |
| `teams/chain/src/main/java/com/rnd/chain/events/ChainEventConsumer.java` | Wrap parse/route failures in `NonRetryableEventException`; remove `throws Exception`; pass `eventType` once instead of re-extracting; use `tryClaim` instead of `isProcessed`+`markProcessed`; supply merge function to `Collectors.toMap` | Findings 1, 2, 9a, 9c | 1, 2, 4 |
| `teams/chain/src/main/java/com/rnd/chain/events/ChainKafkaErrorHandlingConfig.java` | Register `NonRetryableEventException` as not-retryable; remove `@Autowired`; align `KafkaTemplate<Object,Object>` → `KafkaTemplate<String,String>`; simplify DLT resolver to default constructor | Findings 1, 4a, 4b, 4c | 1 |
| `teams/chain/src/main/java/com/rnd/chain/events/ProcessedEventStore.java` | Replace `isProcessed`/`markProcessed` with `boolean tryClaim(ProcessedEvent event)` | Finding 2 | 2 |
| `teams/chain/src/main/java/com/rnd/chain/events/InMemoryProcessedEventStore.java` | Implement `tryClaim` via `putIfAbsent`; add `@ConditionalOnProperty` guard | Findings 2, 6 | 2 |
| `teams/chain/src/main/java/com/rnd/chain/dto/ProcessedEvent.java` | Convert to `record` | Finding 9b | 6 |
| `teams/chain/src/main/java/com/rnd/chain/events/payloads/TaskSchedulingUpdatedPayload.java` | Convert to `record` | Finding 9b | 6 |
| `teams/chain/src/main/java/com/rnd/chain/events/KafkaEventPublisher.java` | Add documentation TODO on fire-and-forget/Outbox tradeoff | Finding 3 | 3 |
| `teams/chain/src/main/java/com/rnd/chain/events/ChainEventTopicsProperties.java` | Broaden to bind both publish and consumed topics symmetrically | Finding 7 | 3 |
| `teams/chain/src/main/resources/application.yml` | Add consumer-group-id property (D3); align with broadened topics properties shape | Finding 7 | 3 |
| `teams/chain/pom.xml` | Add `spring-kafka-test` (test scope) | Testing gap | 5 |
| `teams/chain/src/test/java/com/rnd/chain/events/ChainEventConsumerTest.java` | Replace raw exception assertions with `NonRetryableEventException`; add missing-field/bad-UUID/bad-timestamp cases | Findings 1, 8 (testing) | 5 |
| `teams/chain/src/test/java/com/rnd/chain/events/KafkaEventPublisherTest.java` | Add serialization-failure test | Finding 8 (testing) | 5 |
| `teams/chain/src/test/java/com/rnd/chain/events/InMemoryProcessedEventStoreTest.java` | **New file** — sequential and concurrent `tryClaim` tests | Finding 8 (testing), missing coverage | 5 |
| `teams/chain/src/test/java/com/rnd/chain/events/ChainEventConsumerIntegrationTest.java` (or similar) | **New file** — `@EmbeddedKafka` retry/DLT round-trip tests | Finding 8 (testing), missing coverage | 5 |
| `.idea/claudeCodeEditorTabs.xml`, `.idea/compiler.xml`, `.idea/encodings.xml`, `.idea/jarRepositories.xml`, `.idea/misc.xml` | Revert to `main`'s versions | Finding 9d | 6 |

---

## 12. Implementation Order

1. Verify baseline: `mvn -pl teams/chain test` on a real machine — confirms current pass/fail state
   before any change (resolves D-4b's open question empirically).
2. Phase 1 (exception taxonomy, `ChainKafkaErrorHandlingConfig` fixes) — highest severity, most
   self-contained, touches the fewest files.
3. Phase 2 (idempotency `tryClaim`, production guard) — depends conceptually on Phase 1's exception
   pattern being in place for consistent test style, not on its code.
4. Phase 4's `Collectors.toMap` and double-extraction fixes — same file as Phase 1/2, do while it's
   open.
5. Phase 3 (properties consolidation, group id, publisher TODO) — independent, can run in parallel
   with 2/4 if split across two people; sequenced after for a single implementer to avoid touching
   `ChainEventConsumer` three times.
6. Phase 6 (records, `.idea` revert) — purely mechanical, do last so it doesn't create merge noise
   against the functional changes above.
7. Phase 5 (tests) — write incrementally alongside 2–6, not saved entirely for the end; the
   `@EmbeddedKafka` retry/DLT integration tests specifically should be written *after* Phase 1 lands,
   since they assert on the new exception's not-retryable behavior.
8. Final: re-run full `mvn test`, update the PR description to explicitly state the `priorityScore`
   contract caveat (D5) and the Outbox-deferral rationale (Phase 3.1), and revert the `.idea` diffs
   as the very last commit.

---

## 13. Risks

- **Changing idempotency semantics changes failure behavior**, not just fixes a bug (see Phase 2.4)
  — a handler crash after a successful claim now means that event is never retried by this store,
  where today's (broken) implementation would have retried it. This is a genuine behavior change to
  call out to reviewers explicitly, not bury in a diff.
- **Changing retry/DLT semantics is currently unverifiable without a real Kafka broker** — this
  audit could not run `mvn test` (no network access to Maven Central in this sandbox). Phase 1 and
  the Phase 5 `@EmbeddedKafka` tests are the actual verification; don't skip them because the logic
  "looks right."
- **`KafkaTemplate<Object,Object>` vs `<String,String>` may or may not be a live bug today** — until
  verified (Phase 12.1), treat `ChainKafkaErrorHandlingConfig` as a class that might currently be
  failing to wire at all, which would mean `ChainApplicationTests.contextLoads()` is already red on
  this branch.
- **Cross-team contract mismatch**: `task.scheduling.updated`'s `priorityScore` is not settled
  cross-team (D5). Publishing this event in a demo/staging environment where Inbox is listening
  (once Inbox builds its consumer) risks sending a shape Inbox doesn't expect. Infrastructure
  readiness ≠ contract readiness — keep these described separately in the PR.
- **No CI exists** to catch a regression in any of this. Recommend, as a side note outside this
  plan's scope, that Chain's `mvn test` be added to some CI once CI exists repo-wide — not a blocker
  for this PR, just worth naming as the underlying reason manual verification matters more here than
  it would in a repo with a test gate.
- **Framework compatibility**: Spring Boot 3.3.13/Java 21 is unlikely to be a real risk for Chain
  specifically (no `javax.*` usage found), but has not been build-verified in this audit due to the
  sandbox's lack of Maven/network access. Verify for real before merging.

---

## 14. Definition of Done

- [x] Permanent malformed events (`NonRetryableEventException`) do not undergo retries — verified by
      `permanentFailure_missingEventType_skipsRetryAndReachesDltImmediately`
- [x] Transient failures (anything else, e.g. a future handler's DB timeout) still retry per the
      existing `FixedBackOff` policy (3 attempts, 2s apart) — unchanged, verified by
      `transientFailure_retriesThenSucceeds_neverReachesDlt`
- [x] Failed messages reach the `.DLT` topic, verified by an `@EmbeddedKafka` integration test, not
      just by reading the config
- [x] `ChainEventConsumer` no longer exposes raw `NullPointerException` / `IllegalArgumentException`
      / `DateTimeParseException` / Jackson exceptions from `onMessage` or `dispatch`
- [x] `onMessage`/`dispatch` no longer declare `throws Exception`
- [x] Idempotency claim (`tryClaim`) is atomic under concurrency ≥ 1, verified by a dedicated
      concurrency test (16 threads, exactly one winner)
- [x] The crash-after-claim limitation is documented in code, not just in this plan
- [x] `InMemoryProcessedEventStore` cannot be silently active in an unintended environment (property
      guard in place)
- [x] Publisher's fire-and-forget behavior is documented with the same TODO rigor as the rest of the
      PR; Outbox is explicitly deferred, not implemented, not silently ignored
- [x] `chain.kafka.topics` / `chain.kafka.consumed-topics` bind through one consistent properties
      shape
- [x] Consumer group id is a configurable property, not a hardcoded literal
- [x] `EventEnvelope`'s camelCase field naming is preserved (confirmed correct, not changed)
- [ ] `task.scheduling.updated`'s `priorityScore` shape is called out as cross-team-unconfirmed in
      the PR description, not presented as settled — pending: PR description not yet written
- [x] Event handlers route correctly by `eventType`; duplicate registration fails fast with a named,
      descriptive exception
- [x] Unknown event types are logged and skipped, no retry, no DLT (already correct — confirmed
      unchanged)
- [x] Publisher serialization-failure path is unit-tested (currently missing)
- [x] Consumer failure paths (missing field, bad UUID, bad timestamp, unknown type, handler failure)
      are each unit-tested against the correct exception type
- [x] Retry-then-DLT and no-retry-then-DLT are each verified by an `@EmbeddedKafka` integration test
      — all 32 tests pass on a real machine
- [x] Idempotency concurrency (two threads, same `eventId`) is tested and passes
- [ ] The 5 already-tracked `.idea/*.xml` files are reverted to `main`'s content in the final commit
      — pending: only `misc.xml` was actually pre-existing and has been reverted; the other 4
      (`claudeCodeEditorTabs.xml`, `compiler.xml`, `encodings.xml`, `jarRepositories.xml`) were newly
      added by this PR's own commit and need `git rm`, not a revert (see corrected finding 9d)
- [ ] No new IDE files are newly added to tracking — pending: the `git rm` above
- [ ] The Spring Boot 3.3.13 / Java 21 change ships as its own commit, with a PR description noting
      it aligns Chain with Bridge's existing version, not a novel upgrade — pending: nothing has been
      committed yet
- [x] `mvn -pl teams/chain test` passes in full, verified on a real machine (not assumed) — **32/32
      tests pass**, confirmed on the user's machine

---

## Open Questions Before Implementation

These require a human/team decision — none can be resolved from the repository alone:

1. **`priorityScore`'s type, range, and update semantics for `task.scheduling.updated`.** The one
   document addressing this (`Inbox_Kafka_Ingestion_Contract.md` §4) states it is explicitly
   unresolved between Chain and Inbox. Needs a cross-team conversation, not a Chain-side guess.
2. **Should Chain's Kafka error-handling architecture converge with Inbox's `RetryTemplate`/
   `EventProcessingTemplate` pattern, or stay on Spring Kafka's native `DefaultErrorHandler`/DLT
   mechanism (D1)?** This plan recommends staying native for now since Chain's consumer isn't live
   yet, but if/when multiple teams' Kafka consumers need to be operated and monitored consistently,
   this becomes a real cross-team architecture question, not a Chain-internal one.
3. **When (and how) does Chain get a persistence layer**, and does `ProcessedEventStore`'s eventual
   real implementation piggyback on whatever Chain picks for `Task`/`TaskDependency`, or does it need
   its own store? This blocks closing the "crash after claim" gap in Phase 2 for good (D2).
4. **Should the repo-root `.idea/` untracking + root `.gitignore` (D4) be picked up as its own,
   separate hygiene task**, and by whom — it's cross-team, not Chain-specific.
5. **Is Chain expected to eventually adopt an Outbox pattern** once it has a database, and if so,
   should that be Chain's own implementation (D6 option (a)) or the trigger to finally extract a
   shared module from Inbox's existing one (D6 option (b))? §2A found Inbox's current Outbox is not
   reusable as built — including that Yehudit's own follow-up work for Bridge-sourced tasks stayed
   inside Inbox rather than extracting anything — so this is a real open call, not a formality. Or
   is Chain's publish volume/criticality low enough that fire-and-forget-with-logging is an
   acceptable permanent tradeoff (D6 option (c))? This plan defers the decision, not just the
   implementation.
