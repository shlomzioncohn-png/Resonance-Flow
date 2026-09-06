# Team Bridge — Project Notes

Scope: everything under `teams/bridge/`. Loaded automatically when work happens in this module.
Written from the CRM lane's build-out (Ruth's sub-role); the GitHub/unstructured-intake lanes are
described here only as far as they affect shared code (`core`, `task`, `publish`).

`README.md` in this directory is the original Foundation-stage skeleton doc and is **stale** on
package structure (it describes a flat `controller/service/repository/dto/config` that no longer
exists) — trust this file and the actual source tree over it. `plan.md` still has useful
reasoning for the unstructured-intake lane's design choices.

## 1. What Bridge owns

The adapter/ETL layer that turns per-source records (GitHub issues/PRs, CRM deals, unstructured
chat/email/meeting content) into Inbox's canonical `Task` shape and gets them to Inbox — today by
logging or an HTTP-triggered sync, eventually by publishing to Kafka. Four independent lanes,
one per sub-role (GitHub / CRM / unstructured intake / meeting-doc-audio), sharing the `core` and
`task` packages. A fifth, cross-lane package, `enrichment`, is new as of 2026-09-06 — see §9.

## 2. Stack

Java 21, Spring Boot 3.3.13 (Maven), `spring-kafka`, MinIO client, springdoc-openapi
(`/swagger-ui/index.html`).

## 3. Core abstraction: the intake lane

`core.AbstractIntakeLane<S, O extends BridgePayload>` is a Template Method: `read → map →
validate → publish`, one record at a time, via four collaborator interfaces a lane supplies —
`SourceReader<S>`, `PayloadMapper<S, O>`, `PayloadValidator<O>`, `PayloadPublisher<O>`. A failed
record throws a `LaneException`; `run()` catches it, records it in `IntakeResult`, and continues
to the next record unless `ErrorPolicy.shouldAbort` says otherwise (default: never abort —
`RECORD_AND_CONTINUE`). This means **any exception a mapper/validator/publisher wants to fail a
single record with must extend `LaneException`** (see `ValidationException`, `PublishException`)
— a plain `RuntimeException` aborts the whole run instead of being recorded.

`CrmLane` (Deal → Task) is the only lane currently wired through this end to end. The GitHub lane
predates this abstraction and does not use it (see §6).

## 4. Task vs. BridgeTaskEventDto — two shapes, on purpose

- `task.model.Task` is the **database-shaped** record: `@JsonNaming(SnakeCaseStrategy)`, enums
  serialize lowercase via `@JsonValue`. This is what Inbox's `tasks` table looks like.
- `task.BridgeTaskEventDto` is the **Kafka wire shape** (Inbox's ingestion contract, topic
  `bridge.structured.tasks`): camelCase keys, enums as raw `UPPER_SNAKE_CASE` `Enum.name()`, plus
  a fresh `eventId` per publish, and it deliberately omits fields `Task` carries that Inbox
  forbids on the wire (`extractionProvenance`, `completedAt`, `snoozedUntil`).

**Do not conflate these or serialize `Task` directly onto Kafka** — that was `GitHubKafkaProducer`'s
approach and it does not conform to the contract (§6). Build `BridgeTaskEventDto.from(task)` for
anything Kafka-bound.

`organizationId` on `Task` is `null` by default from `AbstractTaskMapper` (no lane has a general
tenant-id source) — a subclass overrides `AbstractTaskMapper.organizationId()` only once it has
an actual value, never a made-up one. `DealToTaskMapper` is the only override today, backed by
`${bridge.crm.organization-id}` / `CRM_ORGANIZATION_ID` — one static value standing in for one
account until Inbox supports resolving it per deal from `Deal.crmAccountSlug()`.

## 5. Profile-switched beans

Convention used throughout: a default, always-registered bean, overridden by a `@Profile("x")
@Primary` bean when that profile is active. No profile active → the default always wins (single
candidate); profile active → both beans exist, `@Primary` breaks the tie.

| Concern | Default | Profile override |
|---|---|---|
| CRM deal source | `JsonDealRepository` (synthetic fixture) | `hubspot` → `HubSpotDealRepository` (live HubSpot API) |
| CRM task publisher | `LoggingPayloadPublisher<Task>` (logs, publishes nowhere) | `kafka` → `KafkaPayloadPublisher` (real Kafka via `BridgeTaskEventDto`) |

Don't add a new switch without this pattern — a bespoke `if/else` in a `@Bean` method or a second
mechanism will fight `@Primary` resolution.

## 6. Known gaps — don't silently "fix" these without asking

- **`GitHubKafkaProducer` predates the Inbox Kafka contract and doesn't match it.** It publishes
  the raw `Task` (snake_case, lowercase enums, no `eventId`), to whatever `spring.kafka.topic`
  resolves to (currently `bridge.test` in `application.yml`, not `bridge.structured.tasks`). Do
  not use it as a reference for new Kafka work — use the contract doc and `KafkaPayloadPublisher`
  instead.
- **`SourceType.MANUAL` — investigated, not a gap.** Inbox's Kafka contract lists `MANUAL` in the
  shared `sourceType` enum, which raised the question of whether Bridge's `SourceType` was missing
  a value. It isn't: per Inbox's own data schema spec, `MANUAL` denotes a task typed directly into
  the product (Lens/Inbox) with no external source record and no adapter involved — a path that
  bypasses Bridge entirely, not one Bridge is expected to produce. Inbox already models this
  independently in its own `SourceType` enum (`com.rnd.inbox...`), separate from Bridge's
  (`com.rnd.bridge.task.model.SourceType`). Every current `SourceType` call site in Bridge (CRM,
  GitHub Issue, GitHub PR) is hardcoded with no exhaustiveness check, and `BridgeTaskEventDto`
  serializes via plain `Enum.name()`, so no code change was needed or made. No lane, now or in the
  future, will need to emit `MANUAL`.
- **The Inbox Kafka consumer's existence is unverified.** The contract lives on the unmerged
  branch `esti/inbox-kafka-ingestion-contract`
  (`teams/inbox/docs/Inbox_Kafka_Ingestion_Contract.md`) and claims "Consumer implemented &
  tested." As of the last check on that branch, no `BridgeTaskConsumer`/`@KafkaListener`/
  `BridgeTaskMapper` exists anywhere in the repo — the doc's own file references
  (`bridge/task/BridgeTaskEventDto.java`, `bridge/task/BridgeTaskMapper.java`,
  `messaging/kafka/EventProcessingTemplate.java`) don't exist either. Re-check before trusting
  that claim; don't assume it's been built since.
- **`organizationId` is one static config value**, not per-tenant (§4).

## 7. Testing conventions

- Wire-shape/schema tests pin literal JSON key strings and assert the full key set with
  `assertEquals(expectedKeys, actualKeys)`, not just presence — see `TaskSerializationTest` (DB
  shape) and `BridgeTaskEventDtoTest` (Kafka shape) as the two canonical examples.
- Kafka-touching code is tested with a **mocked `KafkaTemplate`** — no test talks to a real
  broker. `@SpringBootTest`-based tests that boot a real context already pay ~40s in
  `KafkaAdmin` retrying against an absent broker; don't add to that.
- `AbstractIntakeLane`'s per-record error handling is exercised generically once
  (`AbstractIntakeLaneTest`) — a new publisher/mapper/validator only needs to prove it throws the
  right `LaneException` subtype, not re-test the lane's catch/continue behavior.
- `application.yml` has **no default** for secrets/tenant config that must never silently take a
  placeholder value in production (`HUBSPOT_TOKEN`, `CRM_ORGANIZATION_ID`) — missing → startup
  fails loudly. A full-context test (`@SpringBootTest`) touching a profile that needs one of
  these must supply a test-only value via `@TestPropertySource`/`properties = {...}`, not add a
  production default.
- Full suite as of the last run (2026-09-06): **126 tests, 0 failures** (was 121 before the
  `enrichment` package landed - 5 tests added in `NoOpEnrichmentServiceTest`; was 115 before that,
  before `HubSpotClient`'s retry logic was extended to 502/503/504 and transport failures - 3
  tests added to `HubSpotDealRepositoryTest`, plus a new `HubSpotClientTest` for the
  transport-failure and connect/read-timeout paths, which don't go through `MockWebServer` socket
  tricks - see that class's Javadoc for why).

## 8. Local environment quirks (this machine)

- **No `mvn` on `PATH`.** Use IntelliJ's bundled Maven:
  `` `C:\Program Files\JetBrains\IntelliJ IDEA <version>\plugins\maven\lib\maven3\bin\mvn.cmd` ``
  (find the exact `<version>` dir under `C:\Program Files\JetBrains\` — it changes with IDE
  updates).
- **Default JDK breaks Mockito.** `.jdks\openjdk-26.0.1` (found via `$env:USERPROFILE\.jdks`) is
  too new for this Spring Boot/Mockito version — `@Spy` fields fail to initialize. Use JDK 23 at
  `C:\Program Files\Java\jdk-23` instead (`$env:JAVA_HOME` + prepend `bin` to `$env:PATH`); it
  compiles the pom's `java.version=21` target fine and Mockito works normally under it.

## 9. Project status & roadmap (as of 2026-09-06)

This section is the detailed, point-in-time answer to "what's done and what's left" for Ruth's
CRM lane + the new cross-team enrichment scope. Update it as state changes rather than trusting it
blindly once it's gone stale — cross-check against `git log`/the PRs below before relying on it.

### 9.1 Branch / PR topology

Three branches stacked on each other, each with its own PR:

1. **`ruth/bridge-core`** (PR **#159**, `ruth/bridge-core → main`, **OPEN**) — the shared,
   lane-agnostic foundation: `core` (the `AbstractIntakeLane` template + its four collaborator
   interfaces), `task` (`Task`, `AbstractTaskMapper`, `TaskValidator`, the enums), and
   `LoggingPayloadPublisher`. Title: "Bridge: shared pipeline infrastructure (blocks the other
   three lanes)". **Status: reviewed by 1 of 8 requested reviewers (MalkaSafra, approved
   2026-09-06 10:07); the other 7 (danielkap-tr, KamaTechOrg, tamarmar, leaRevivo, ayalaw,
   estherora-tem, Bezbran, rachelzahler) have not reviewed yet — `reviewDecision` is
   `REVIEW_REQUIRED`.** `mergeable: MERGEABLE`, CI not failing. **This PR merging is what actually
   lands all of Ruth's CRM work on `main` — until then, everything below exists only on these
   branches, not on `main`.** Not something Ruth can resolve alone; needs the other reviewers.
2. **`ruth/crm-deal-mapper`** (PR **#161**, `ruth/crm-deal-mapper → ruth/bridge-core`, **MERGED**
   2026-09-06 09:35, merge commit `fd0a3e2`) — the CRM lane itself, built on top of (1). Had 6
   `add/add` merge conflicts against `ruth/bridge-core` at merge time (both branches had
   independently evolved `AbstractTaskMapper`/`Task`/`TaskValidator`/3 tests after diverging from
   a common ancestor); resolved 2026-09-06 by keeping `ruth/crm-deal-mapper`'s side entirely
   (commit `5152031`, "fix: resolve merge conflicts with ruth/bridge-core by keeping HEAD") —
   verified line-by-line that `bridge-core`'s side had nothing unique, and confirmed empirically
   (isolated `git worktree` + `BridgeApplicationTests` full context load) that the resulting
   `TaskValidator` (`@Component` + `CrmLaneConfig`'s manual `@Bean` for the same type) does not
   collide.
3. **`ruth/llm-enrichment`** (current branch, **not yet pushed / no PR opened**) — branched from
   `ruth/bridge-core` after (2) merged into it, so it starts from a clean, conflict-free base.
   Carries the new `enrichment` package (§9.3) as uncommitted, untracked files as of this writing
   — nothing on this branch has been committed yet, on Ruth's explicit instruction not to change
   anything further until she reviews.

### 9.2 What's done — CRM lane (Ruth's core deliverable)

All of the following is merged into `ruth/bridge-core` (via PR #161) and passes the full test
suite (126 tests, 0 failures — see §7):

- **`Deal` model + `DealToTaskMapper`** (`crm.model`, `crm.mapper`) — maps a CRM `Deal` to the
  canonical `Task`: stage→status, close-date→deadline (end-of-day UTC convention, provisional),
  urgency, `organizationId()` override backed by `${bridge.crm.organization-id}` /
  `CRM_ORGANIZATION_ID` (no default — fails startup loudly if unset), `toCompletedAt()` override
  (only set when status is DONE and the deal actually recorded a close date).
- **`JsonDealRepository`** (default bean) — reads the synthetic fixture
  `classpath:crm/synthetic-deals.json` via `SyntheticDealsLoader`/`SyntheticDealsFeed`. This is
  the *existing* CRM fixture data, not the new Stage-4 "dirty" synthetic corpus (§9.4.4) — don't
  conflate the two.
- **`HubSpotDealRepository`** (`hubspot` profile) — the live data source. Talks to the real
  HubSpot API via `HubSpotClient`, page size capped at 50 (HubSpot's own limit when
  `propertiesWithHistory` is requested), backed by `HUBSPOT_TOKEN` (no default, fails loudly).
  **Retry logic**: classifies 429, 502/503/504, and transport-level failures
  (`ResourceAccessException`, plus `IOException` walked up the cause chain for the
  read-timeout-after-2xx case) as retryable, up to `max-retries` with exponential backoff;
  anything else (400/401/404/...) fails immediately as non-retryable. **Explicit connect/read
  timeouts** (default 5s/15s, configurable) so a server that accepts the connection and never
  responds doesn't hang the sync forever. See `application-hubspot.yml` for all tunables.
- **`CrmLane`** (`crm.CrmLane` + `CrmLaneConfig`) — the only lane currently wired through
  `AbstractIntakeLane` end to end (read HubSpot/fixture → map to `Task` → validate → publish).
  Bean wiring in `CrmLaneConfig` follows the default/profile-override convention (§5): fixture vs.
  `hubspot`, logging vs. `kafka` publisher.
- **`CrmBridgeController`** — `POST /bridge/crm/sync`, the (currently only) trigger for a CRM
  sync run. No auth (documented gap, not solved here). No single-flight guard against concurrent
  POSTs (harmless today with the logging publisher; not once Kafka is the default).
- **Kafka publish path** — `KafkaPayloadPublisher` (kafka profile) builds `BridgeTaskEventDto`
  from `Task` (via `BridgeTaskEventDto.from(task)`) and publishes to
  `${bridge.structured-tasks-topic:bridge.structured.tasks}` — the real Inbox contract shape
  (camelCase, `UPPER_SNAKE_CASE` enums, fresh `eventId`), **not** the same path
  `GitHubKafkaProducer` uses (§6 — that one is a known, separate gap, untouched).
- **`.env.example`** documents `CRM_ORGANIZATION_ID` as a per-environment real UUID Inbox
  (Ayala) still needs to hand back for dev/staging/prod — placeholder value is not usable as-is.

### 9.3 What's done — enrichment scaffolding (Stage 3, first slice only)

New package `com.rnd.bridge.enrichment`, built 2026-09-06, **uncommitted** on `ruth/llm-enrichment`:

- `DueDateSuggestion`, `SeveritySuggestion`, `DependencySuggestion` (+ `DependencyRelation` enum:
  `BLOCKS`/`BLOCKED_BY`/`FIXES`/`RELATES_TO`) — each carries its own `confidence` (`double`,
  0.0–1.0, never null) and a `rationale` string. `SeveritySuggestion.label` is a free-text token
  (e.g. "P0", "critical"), deliberately **not** `task.model.Tier` — Tier is an org-scope axis
  (operational/tactical/strategic), not a severity scale; mapping one onto the other would be an
  invented convention nobody asked for.
- `TaskEnrichment` — the aggregate result for one piece of free text, keyed to
  `Task.sourceRef`. A field that found nothing is `null`/empty-list, never a thrown exception —
  `TaskEnrichment.empty(sourceRef)` is the canonical "nothing found" value.
- `EnrichmentService` — the interface (`enrich(sourceRef, freeText) -> TaskEnrichment`), with an
  explicit contract in its Javadoc: null/blank input, or an implementation that finds nothing,
  **must never throw** — callers should never need a try/catch around this call.
- `NoOpEnrichmentService` — the only implementation today. **No LLM vendor is chosen yet**
  (Anthropic vs. OpenAI is still an open decision — see §9.4.3); this always returns an empty
  result, wired as the default bean via `EnrichmentConfig`, following the same
  default/`@Profile`-override convention as everything else in §5. A real provider becomes a
  `@Profile`-gated bean later without touching this class.
- `NoOpEnrichmentServiceTest` — proves the robustness contract: null/blank/whitespace-only input,
  and realistic messy input (`"this PR might block the release of #789 but I'm not sure"`), all
  return an empty `TaskEnrichment` without throwing.
- **Verified**: compiles clean, all 5 new tests pass, and `BridgeApplicationTests` (full Spring
  context load) still passes with `EnrichmentConfig`'s bean wired in — no collision with existing
  beans.
- **Explicitly not done in this slice**: no lane calls `EnrichmentService` yet (no wiring into
  `CrmLane`/`DealToTaskMapper`/GitHub mappers), no publisher for `TaskEnrichment`/suggestions
  (Chain is the eventual consumer for dependency hints per Kayvan's brief — "suggestions only, we
  don't write the graph ourselves" — but no publish mechanism exists yet), no real LLM call
  anywhere in this codebase.

### 9.4 Kayvan's 4-stage roadmap — status per stage

Source: verbal brief relayed 2026-09-06 (no `team-specs/` doc exists yet for this — checked, the
repo has no `team-specs` directory; `docs/` only has `employee-ui-design.md` and
`lens-architecture-design.md`, unrelated). Treat the stage descriptions below as a paraphrase of
that brief, not a linked spec.

1. **Close out the current batch (immediate).** ✅ **Done**, with one caveat:
   - PR #161 merged (§9.1).
   - Verified (not changed, per instruction) that "the daily scheduled job pulling HubSpot +
     GitHub" **does not actually exist as described**:
     - **CRM**: no scheduler at all. `POST /bridge/crm/sync` is the only trigger — something
       external has to call it; nothing in Bridge does so on a cadence.
     - **GitHub**: `GitHubSyncScheduler` (`@Scheduled`, default cron `0 0 2 * * ?`, gated by
       `@EnableScheduling` in `GitHubSchedulerConfig`) exists, but is **disabled by default**
       (`github.sync.cron.enabled: ${GITHUB_SYNC_CRON_ENABLED:false}`, not set true anywhere in
       `.env.example` or any profile yml). Even if enabled, it publishes through
       `GitHubKafkaProducer`, which is the known-stale gap from §6 (wrong topic, wrong wire
       shape) — so enabling it today would not correctly reach Inbox anyway.
   - This was reported, not fixed — left exactly as-is per instruction not to touch this logic.
2. **Real-time ingestion infra prep (optional / time-permitting).** ❌ **Not started.** No
   `CrmWebhookReceiver`, `GitHubEventEndpoint`, or any webhook-shaped controller exists anywhere
   in `teams/bridge/src`. Explicitly scoped by the brief as skeleton-only (accept payload, basic
   validation, log — no business logic) until a decision to go real-time is made.
3. **LLM-based enrichment (the core new scope).** 🔶 **Started, schema/interface/stub only** —
   see §9.3 for exactly what exists. Remaining work, in dependency order:
   - Decide the LLM vendor (Anthropic vs. OpenAI vs. stay provider-agnostic longer) — **open
     decision, not made yet**; blocks writing a real (non-stub) `EnrichmentService`
     implementation, and blocks knowing what credential/env-var convention to add (would follow
     the `HUBSPOT_TOKEN`/`GITHUB_TOKEN` pattern: env-var-backed, no default, fail loud if unset).
   - Decide **when/how `EnrichmentService` actually gets called** — nothing calls it yet. Open
     questions: per-record during `CrmLane`/GitHub mapping (inline, adds LLM latency to every
     sync), or a separate async/batch step reading already-published Tasks? Not decided.
   - Decide **how a `TaskEnrichment` gets published/exposed** once produced — Kayvan's brief says
     dependency hints go to Chain "as suggestions only"; no publisher, topic, or endpoint for this
     exists yet. Likely mirrors `PayloadPublisher<TaskEnrichment>` but this isn't designed.
   - Prompt design for the three extraction targets (due dates, severity from labels+text,
     dependency refs like "Fixes #45") — not started; depends on vendor choice above.
4. **Synthetic dirty-data corpus (breadth task, whole team).** ❌ **Not started.** No JSON fixtures
   with deliberately missing/malformed data, weird date formats, or ambiguous dependency phrasing
   exist yet (the "synthetic" files that do exist —
   `crm/repository/{SyntheticDealsFeed,SyntheticDealsLoader}.java`,
   `resources/crm/synthetic-deals.json` — are the pre-existing clean CRM fixture data, unrelated
   to this task). Low practical value until Stage 3 has a real (non-stub) provider to run the
   dirty data through — against `NoOpEnrichmentService`, every input produces the same empty
   result regardless of how messy it is, so nothing would be proven yet.

### 9.5 Cross-team context worth knowing (not Bridge's job, but affects it)

- **Mind's own `extraction` module is itself just a stub** (`teams/mind/src/mind/extraction/` —
  a README + empty `__init__.py`, no implementation). The "same pattern Mind already uses" Kayvan
  referenced is a *convention* (every LLM-inferred item carries `confidence` +
  `extraction_provenance`), not existing code to copy — there is nothing there to port from
  Python to Java.
- **The Inbox Kafka consumer's existence is still unverified** (§6, unchanged) — the contract PR
  is now **#195** ("Kafka ingestion contract for BRIDGE, MIND & CHAIN", `esti/inbox-kafka-ingestion-contract → inbox-team`, still **OPEN** as of 2026-09-06). Don't assume Inbox can
  actually consume `bridge.structured.tasks` yet just because `KafkaPayloadPublisher` can produce
  to it correctly.
- Other open Bridge-adjacent PRs as of 2026-09-06: **#193** (Esther, "rebuild meeting/doc/audio
  media lane on shared architecture", `esther/bridge-minio-intake → main`), **#194** (Yehudit,
  "Bridge outbox integration", `yehudit/bridge-outbox-integration → inbox/bridge-assignment-outbox`).
  Neither blocks the CRM lane directly, but both touch shared `core`/`task` territory the same way
  `ruth/bridge-core` does — worth a glance before assuming `core`/`task` are stable.
