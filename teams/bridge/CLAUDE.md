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
  serialize lowercase via `@JsonValue`. This is what Inbox's `tasks` table looks like — except
  `organization_id`/`organizationId`, which Inbox renamed to `tenant_id` on 2026-09-06 and `Task`
  deliberately does not mirror (see §6).
- `task.BridgeTaskEventDto` is the **Kafka wire shape** (Inbox's ingestion contract, topic
  `bridge.structured.tasks`): camelCase keys, enums as raw `UPPER_SNAKE_CASE` `Enum.name()`, plus
  a fresh `eventId` per publish, and it deliberately omits fields `Task` carries that Inbox
  forbids on the wire (`extractionProvenance`, `completedAt`, `snoozedUntil`). Inbox confirmed
  camelCase directly on 2026-09-15 — they have no `@JsonNaming` and no global Jackson naming
  strategy, so a snake_case key from us simply doesn't match their field names. The cross-team
  snake_case convention still stands as the eventual target, not rejected — Inbox just hasn't
  adopted it on their side yet, and both producer and consumer have to switch in the same moment
  for it to work. Don't change this DTO's casing unilaterally again until they have. Separately,
  this DTO's tenant field is named `tenantId`, not `organizationId` like `Task` — Inbox renamed
  `organization_id` to `tenant_id` across their schema on 2026-09-06 (#255, `9684b8e8`), including
  the `bridge.structured.tasks` idempotency index, so the dedup key is `(tenant_id, source_type,
  source_ref)`, not `(organization_id, source_type, source_ref)`. Every message Bridge published
  between that rename and this fix was silently rejected by Inbox's `@NotBlank` validation on
  `tenantId` (see §6) — `Task.organizationId` is deliberately NOT renamed to match, see above.

**Do not conflate these or serialize `Task` directly onto Kafka** — that was `GitHubKafkaProducer`'s
approach and it does not conform to the contract (§6). Build `BridgeTaskEventDto.from(task)` for
anything Kafka-bound.

`organizationId` on `Task` is `null` by default from `AbstractTaskMapper` (no lane has a general
tenant-id source) — a subclass overrides `AbstractTaskMapper.organizationId()` only once it has
an actual value, never a made-up one. `DealToTaskMapper` is the only override today, backed by
`${bridge.crm.organization-id}` / `CRM_ORGANIZATION_ID` — one static value standing in for one
account until Inbox supports resolving it per deal from `Deal.crmAccountSlug()`.

`DealToTaskMapper.toUrgency` also adds +1 (before the 1..5 clamp) for a deal in an open stage
(`PROSPECT`/`QUALIFIED`/`PROPOSAL`/`NEGOTIATION`) that's sat in that stage at least
`${bridge.crm.stalled-after-days}` / `CRM_STALLED_AFTER_DAYS` days (default 14, `<= 0` disables
it) as of the run's `asOf` — `ON_HOLD`/`CLOSED_*` are never bumped. Computed only from `asOf` and
`Deal.stageChangedAt`, so a deal that becomes stalled after its Task already exists in Inbox won't
get the bump there until Inbox's F2 (insert-only ingestion) is fixed to also update existing tasks.

## 5. Profile-switched beans

Convention used throughout: a default, always-registered bean, overridden by a `@Profile("x")
@Primary` bean when that profile is active. No profile active → the default always wins (single
candidate); profile active → both beans exist, `@Primary` breaks the tie.

| Concern | Default | Profile override |
|---|---|---|
| CRM deal source | `JsonDealRepository` (synthetic fixture) | `hubspot` → `HubSpotDealRepository` (live HubSpot API); `salesforce` → `SalesforceDealRepository` (live Salesforce REST API) |
| CRM task publisher | `LoggingPayloadPublisher<Task>` (logs, publishes nowhere) | `kafka` → `KafkaPayloadPublisher` (real Kafka via `BridgeTaskEventDto`) |

Don't add a new switch without this pattern — a bespoke `if/else` in a `@Bean` method or a second
mechanism will fight `@Primary` resolution. `hubspot` and `salesforce` are mutually exclusive, not
stackable the way `kafka` is: both repository beans are independently `@Primary` within their own
profile, so Spring has no tiebreaker if both are active at once. `CrmLaneConfig.dealToTaskMapper`
fails startup with a clear `IllegalStateException` naming both profiles if that happens (same
shape as the kafka/tenant-id check, §7) — see §10 for the Salesforce source specifically.

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
- **`bridge.structured.tasks` was silently rejected by Inbox for over a week (2026-09-06 to
  2026-09-15).** Inbox renamed `organization_id` to `tenant_id` across their schema on 2026-09-06
  (#255, `9684b8e8`) — `StructuredTaskEventDto.tenantId` is `@NotBlank`, has no `@JsonNaming`, and
  Inbox has no global Jackson naming strategy, so it matches nothing but a literal `tenantId` key.
  `BridgeTaskEventDto` kept sending `organizationId` until this was found and fixed, so every CRM
  and JIRA message in that window failed that validation and was routed to
  `bridge.structured.tasks.errors` — a real Kafka topic, logged at WARN, but nothing in this repo
  consumes `.errors` topics, so in practice it was invisible. Fixed by renaming the DTO's field to
  `tenantId` (§4); `Task.organizationId` was deliberately left alone (§4) since `Task` never goes
  on the wire directly. Two internal Bridge naming inconsistencies are a deliberate result of that
  scoping decision, not oversights, and are tracked here together: (1) `Task.organizationId` /
  `AbstractTaskMapper.organizationId()` still say "organization", not "tenant" (§4); (2)
  `CRM_ORGANIZATION_ID` / `bridge.crm.organization-id` (`application.yml`, `.env.example`) also
  still say "organization" - renaming an env var/config key is an operational risk across Helm and
  every deployed environment for zero correctness gain, since it's a label Bridge picks for its
  own config, never a wire field. Neither is broken today; both are candidates for a future
  cleanup pass, not this fix. **`TaskSerializationTest` pins a stale claim about Inbox's schema** — its
  `INBOX_COLUMN_NAMES` set still asserts `organization_id` as Inbox's real column name, which has
  been false since the same 2026-09-06 rename. The test still passes because it only checks that
  `Task.java`'s own (deliberately unrenamed) `organizationId` field serializes to
  `organization_id` — that's internally consistent and always will be, regardless of what Inbox's
  actual column is called. A passing `TaskSerializationTest` is not evidence this was ever checked
  against Inbox's real schema; don't read it as verification of that. No test anywhere in either
  `teams/bridge` or `teams/inbox` exercises the real seam between Bridge's producer and Inbox's
  consumer — `StructuredTaskConsumerIntegrationTest` (Inbox) hand-builds its own JSON payload using
  Inbox's own expected field names, so it's true by construction and would never have caught this.

## 7. Testing conventions

- Wire-shape/schema tests pin literal JSON key strings and assert the full key set with
  `assertEquals(expectedKeys, actualKeys)`, not just presence — see `TaskSerializationTest` (DB
  shape) and `BridgeTaskEventDtoTest` (Kafka shape) as the two canonical examples.
- `HubSpotDealRecordObjectMapperNamingStrategyTest` (`@JsonTest`) pins that Bridge's shared,
  Spring-auto-configured `ObjectMapper` stays on Jackson's default camelCase naming strategy —
  that bean also deserializes inbound HubSpot API responses (camelCase), so any future per-DTO
  `@JsonNaming` decision for a Kafka contract must never become a global strategy on this bean.
- Kafka-touching code is tested with a **mocked `KafkaTemplate`** — no test talks to a real
  broker. `@SpringBootTest`-based tests that boot a real context already pay ~40s in
  `KafkaAdmin` retrying against an absent broker; don't add to that.
- `AbstractIntakeLane`'s per-record error handling is exercised generically once
  (`AbstractIntakeLaneTest`) — a new publisher/mapper/validator only needs to prove it throws the
  right `LaneException` subtype, not re-test the lane's catch/continue behavior.
- `application.yml` has **no default** for `HUBSPOT_TOKEN` — missing → startup fails loudly
  (`HubSpotClient`'s constructor). **`CRM_ORGANIZATION_ID` is different and this bullet used to
  claim otherwise:** it defaults to the placeholder `local-crm-org`
  (`bridge.crm.organization-id:local-crm-org` in `application.yml`), usable for a local run
  without the `kafka` profile. `CrmLaneConfig.dealToTaskMapper` fails startup on a missing or
  non-UUID value only once the `kafka` profile is active (added 2026-10-06, CRM-lane audit
  finding F5) — a `@SpringBootTest` combining `kafka` with that placeholder needs a real UUID via
  `@TestPropertySource`/`properties = {...}`, not a production default change. `HUBSPOT_TOKEN`
  itself still has no profile-conditional default; it fails loudly regardless of profile.
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
- **`protobuf-maven-plugin`'s `protoc.exe` fails on a non-ASCII checkout path.** If the repo sits
  under a path containing non-ASCII characters (this machine's checkout is under a Hebrew-named
  folder), `mvn compile`/`test` fails with `PROTOC FAILED: ...: warning: directory does not
  exist` even though the directory is right there — the native `protoc.exe` binary can't resolve
  the path under this machine's active codepage. Reproduces identically for `teams/inbox`'s own
  pre-existing `.proto` build, so it's an environment issue, not anything specific to Bridge's
  schema. `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8` via `MAVEN_OPTS` does not fix it.
  Workaround: `subst` an ASCII drive letter to the parent folder and build from there, e.g.
  `subst R: "C:\Users\1\Desktop\<non-ascii-folder>"`, then build from
  `R:\Resonance-Flow\teams\bridge`. Not an issue in CI — `.github/actions/test-java` runs `mvn`
  inside a Linux Maven Docker container, where this never comes up.

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
   three lanes)". **Status as of 2026-09-06 13:4x: 0 of 8 requested reviewers currently approved.**
   MalkaSafra approved once (2026-09-06 10:07, against commit `fd0a3e2`), but that approval was
   **auto-dismissed by GitHub** when (4) below pushed new commits onto this same branch —
   `ruth/bridge-core` is PR #159's head, so any push here invalidates prior reviews of it. All 8
   reviewers (danielkap-tr, KamaTechOrg, tamarmar, leaRevivo, ayalaw, estherora-tem, Bezbran,
   rachelzahler) were re-requested and a status comment was posted 2026-09-06 explaining the
   blocking. `mergeable` briefly went `UNKNOWN` right after the push (GitHub recomputing) — check
   fresh rather than trusting a cached value. **This PR merging is what actually lands all of
   Ruth's CRM + enrichment work on `main`** — until then, everything below exists only on these
   branches. Not something Ruth can resolve alone; needs the other reviewers.
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
3. **`ruth/llm-enrichment`** (branched from `ruth/bridge-core` after (2) merged into it) — carried
   the new `enrichment` package (§9.3). Committed (`bb84a69`), pushed, opened as PR **#286**
   (`ruth/llm-enrichment → ruth/bridge-core`), and **merged** 2026-09-06 13:42:55 (merge commit
   `e9d78c2`). This branch's work is done; the branch itself can be deleted (GitHub already offers
   this after merge) — nothing further happens on it.
4. Net effect: **`ruth/bridge-core` now contains both (2) and (3)**, at commit `e9d78c2`. Local
   checkout was fast-forwarded to match. PR #159 (item 1) is the only thing standing between all of
   this and `main`.

### 9.2 What's done — CRM lane (Ruth's core deliverable)

All of the following is merged into `ruth/bridge-core` (via PR #161) and passes the full test
suite (126 tests, 0 failures — see §7):

- **`Deal` model + `DealToTaskMapper`** (`crm.model`, `crm.mapper`) — maps a CRM `Deal` to the
  canonical `Task`: stage→status, close-date→deadline (end-of-day UTC convention, provisional),
  urgency, `organizationId()` override backed by `${bridge.crm.organization-id}` /
  `CRM_ORGANIZATION_ID`, `toCompletedAt()` override (only set when status is DONE and the deal
  actually recorded a close date). **Corrected 2026-10-06 (this bullet previously said "no
  default — fails startup loudly if unset"; false — see §7):** the config key defaults to
  `local-crm-org`, and only fails startup when the `kafka` profile is active
  (`CrmLaneConfig.dealToTaskMapper`, CRM-lane audit finding F5).
- **Snapshot-per-sync, latest-state-per-deal (added 2026-10-06, audit findings F1/F2/F14/F9).**
  `DealRepository.snapshot()` performs one fresh fetch and returns an immutable `DealSnapshot`
  (`asOf`, full history, `latestPerDeal()`, `skippedAtRead`). `CrmBridgeController` and
  `CrmSyncJob` each take exactly one snapshot per run and call
  `crmLane.run(snapshot.latestPerDeal().stream(), snapshot.asOf())` — never the old
  `ReadCursor`/`ReadCursor.full()` overload, which would let the deals and `asOf` come from two
  separate fetches. `CrmSourceReader.read()` also returns `latestPerDeal()`, so the generic
  `AbstractIntakeLane.run(ReadCursor, Instant)` path (used by `CrmLaneIntegrationTest`) agrees.
  **`DealRepository` has only `snapshot()` now** — the five old per-instance-cached finder methods
  (`asOf()`, `findAll()`, …) were deprecated, then removed outright (2026-10-06, same day): nothing
  called them except their own test suite, and leaving a cache field on `HubSpotDealRepository`
  that nothing production-facing used was a trap for the next person who reached for the
  "obvious" method. `HubSpotDealRepositoryTest`'s 14 pre-existing tests were migrated to
  `snapshot()` rather than deleted. `HubSpotDealRepository.snapshot()` also re-fetches and
  re-resolves the pipeline/stage mapping on every call (not just at construction), so a stage
  added to the portal after startup is picked up. **Tradeoff worth knowing:** the constructor
  still eagerly resolves the pipeline once too (result discarded), purely to fail fast at startup
  on a pipeline/stage-level problem (see §11) — so the very first `snapshot()` call after startup
  does one redundant pipeline fetch.
  **Corrected 2026-10-14 (F6 is fixed, not "still open" as the paragraph here originally said) —
  see §11 for the full mapping-rule rewrite:** this used to describe a label added under a name
  `HubSpotStageMapping` doesn't recognize failing the **entire** sync from the moment it appears.
  Stage resolution is metadata-based now (`isClosed`/`probability`, never the label), the same
  approach §10's Salesforce fix already used, so that failure mode is gone — a renamed/custom
  label with valid metadata just resolves correctly.
  `SyncJobScheduler`'s shared `catch (RuntimeException e) { log.error(...) }` (every `SyncJob`'s
  failure handling, not just CRM's — see §9.4) and
  `CrmBridgeController.handleSnapshotFailure` (a 503 with the exception's message, not the
  framework's default message-less 500) still apply to what genuinely can't be isolated to one
  stage (§11's FAIL STARTUP / FAIL RUN rows).
  Only a CRM-only `CrmTaskValidator` (wraps the shared `TaskValidator`, adds tenant-UUID/
  `sourceRef` checks) is new for F5 — the shared `TaskValidator` itself is unchanged, since
  GitHub/JIRA/`TaskEnrichmentConsumer` share it directly and never set a tenant id.
  **`CRM_ORGANIZATION_ID`'s default changed from `local-crm-org` to
  `CrmTaskValidator.LOCAL_PLACEHOLDER_TENANT_ID` (`00000000-0000-0000-0000-000000000000`),
  2026-10-06, fixing a real regression caught before merge:** `CrmTaskValidator` validates
  tenant-id shape unconditionally, not just under `kafka`, so the old non-UUID placeholder would
  have made every task fail validation on a plain no-profile local run. The new placeholder is
  UUID-shaped (passes `CrmTaskValidator`) but is rejected **by name**, not just by shape, in
  `CrmLaneConfig`'s `kafka`-profile startup check — explicitly setting it to this exact value under
  `kafka` still fails startup, same as leaving it unset.
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

New package `com.rnd.bridge.enrichment`, built 2026-09-06 on `ruth/llm-enrichment`, committed
(`bb84a69`), merged into `ruth/bridge-core` via PR #286 (§9.1 item 3):

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
     - **CRM**: **stale as of 2026-10-12 — a scheduler exists, now via a shared mechanism.**
       `CrmSyncJob` (`bridge.crm.sync.cron.enabled`/`...expression`, same keys and `0 0 3 * * ?`
       default as before, off by default) runs the same `crmLane` as `POST /bridge/crm/sync`.
       It's one of several `SyncJob` beans `SyncJobScheduler` (`core/sync`) now registers — a
       single generic scheduler added 2026-10-12 to replace CRM/GitHub/Jira each having their own
       copy-pasted `@Scheduled` method; `IntakeSyncJobs` added three more (EMAIL/CALENDAR/DRIVE,
       `bridge.intake.<source>.sync.cron.*`, off by default, each a direct bean call to
       `UnstructuredIntakeController.run(RawSourceType)`). This bullet originally said "no
       scheduler at all" — true on 2026-09-06, false since. See §9.2 for what the CRM job itself
       does today.
     - **GitHub**: `GitHubSyncScheduler` (`@Scheduled`, default cron `0 0 2 * * ?`, gated by
       `@EnableScheduling` in `GitHubSchedulerConfig`) exists, but is **disabled by default**
       (`github.sync.cron.enabled: ${GITHUB_SYNC_CRON_ENABLED:false}`, not set true anywhere in
       `.env.example` or any profile yml). Even if enabled, it publishes through
       `GitHubKafkaProducer`, which is the known-stale gap from §6 (wrong topic, wrong wire
       shape) — so enabling it today would not correctly reach Inbox anyway. **Not migrated to
       `SyncJob`** (2026-10-12) — GitHub/Jira are Hadas's own schedulers, and `GitHubSyncScheduler`
       is reactive (`Mono`-based) rather than returning `IntakeResult`, unlike every `SyncJob`;
       left exactly as-is, the owner's call.
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

## 10. Salesforce CRM source

`SalesforceDealRepository` (`@Repository @Profile("salesforce") @Primary`) is the third
`DealRepository` implementation, alongside `JsonDealRepository` (default) and
`HubSpotDealRepository` (`hubspot`). Full design rationale lives in
`teams/bridge/docs/crm-salesforce-design.md` — this section is the short version plus the things
most likely to trip someone up.

- **Auth: OAuth 2.0 Client Credentials Flow, not a static token.** `SalesforceClient` acquires and
  caches an access token (together with the `instance_url` it came with — every subsequent call
  targets that host, not the login host). Salesforce's Client Credentials Flow issues no refresh
  token and no `expires_in`, so there is nothing to proactively refresh against; the token is
  refreshed *reactively* on a `401` (refreshed once, triggering call retried once — a second `401`
  after a genuinely fresh token is fatal, not looped).
- **`dealId` is always the bare `Opportunity.Id` — never prefixed.** Per-source `sourceRef` needs
  go through `Deal.sourceRefPrefix` instead (defaults to `"crm:"` in the builder and for
  HubSpot/JSON, both of which never set it; `SalesforceDealRepository` sets it to
  `"crm:salesforce:"`). `DealToTaskMapper.toSourceRef` is `deal.sourceRefPrefix() + deal.dealId()`
  — no per-source branch in the mapper, and `dealId()` is the same bare value everywhere, so
  `fetchDeal(deal.dealId())` always round-trips. Don't reintroduce a prefixed `dealId` to solve a
  future per-source `sourceRef` need — extend `sourceRefPrefix` (or add a sibling field) instead.
- **`fetchDeal(dealId)` takes the bare `Opportunity.Id`** — the same value `Deal#dealId()` returns.
  One query for the Opportunity; the stage-metadata query only fires if that lookup found a record
  (a miss costs one request, not two, mirroring `HubSpotDealRepository`'s 404 handling adapted to
  Salesforce's "not found is zero records"). No cache field anywhere on this class — `snapshot()`
  and `fetchDeal()` are both a fresh set of SOQL queries every call (CRM-lane audit finding F1).
- **Stage resolution is metadata-based, never label matching** (`SalesforceStageMapping`) — the
  same fix as HubSpot's own (§11) for CRM audit finding F6 (a renamed/custom stage label breaking
  the whole sync), landed here first. `IsClosed`/`IsWon` on the `OpportunityStage` row map directly to `CLOSED_WON`/
  `CLOSED_LOST`; an open stage is bucketed by `DefaultProbability` into one of four configurable
  bands read from `bridge.crm.salesforce.stage-bands.*` in `application-salesforce.yml` (defaults
  0–20/21–50/51–80/81–99 → `PROSPECT`/`QUALIFIED`/`PROPOSAL`/`NEGOTIATION`), validated at actual
  Spring startup (the mapping is a plain field of the eagerly-created `SalesforceDealRepository`
  singleton) to be strictly increasing and partition `0..99` exactly — a misconfigured band fails
  loudly with a clear `IllegalStateException` instead of silently misclassifying every open deal. A
  `null` `DefaultProbability` on an open stage falls back to `PROSPECT`, the most conservative
  bucket. **An unresolvable `StageName` is a per-record skip in `snapshot()`** (counted in
  `DealSnapshot.skippedAtRead()`, never aborts the sync) **but throws `IllegalStateException` in
  `fetchDeal()`** — there's no "sync" to protect for a single-record fetch, so failing loudly there
  is correct, not a missed case of the same rule.
- **REST API version is config, not hardcoded** (`bridge.crm.salesforce.api-version` /
  `SALESFORCE_API_VERSION`, default `68.0`) — Salesforce ships three releases a year and retires
  old API versions, so this is expected to need bumping periodically; it was hardcoded in
  `SalesforceClient` until this was fixed.
- **`load-history: true` is not implemented.** Unlike HubSpot (per-property history),
  Salesforce's default read is current-state-only — one `Deal` per Opportunity, one history entry
  long. Enabling `load-history` fails fast at `SalesforceDealRepository`'s construction with a
  clear message rather than silently behaving like current-state-only mode.
- **`REQUEST_LIMIT_EXCEEDED` (`403`) is fatal, never retried** — it's a rolling 24-hour budget, not
  a short per-second bucket like HubSpot's `429`, so backing off cannot make it succeed sooner.
  `500`/`502`/`503`/`504` and transport failures (connect/read timeout, DNS/IO errors) **are**
  retried with exponential backoff, same as `HubSpotClient`.
- **`hubspot` and `salesforce` are mutually exclusive profiles** — see §5's table note. Both active
  at once fails startup with an `IllegalStateException` naming both, from
  `CrmLaneConfig.dealToTaskMapper` (`CrmLaneConfigTest.failsToStartWhenBothHubspotAndSalesforceProfilesAreActive`).
- **No Salesforce org exists for this work yet** (design doc §9) — everything above is verified
  against `SalesforceClient`/`SalesforceDealRepository`'s own test suites
  (`MockWebServer`-backed, no live calls), not against a real org. `stage-changed-at-field`
  defaulting to `LastStageChangeDate` is specifically flagged in the design doc as
  moderate-confidence/unverified — if it turns out not to exist on the target org, switch
  `SALESFORCE_STAGE_CHANGED_AT_FIELD` to `LastModifiedDate` (config change only, no code change),
  noting the real imprecision that comes with it (bumps on any field edit, not just a stage
  change).

## 11. HubSpot stage mapping by metadata — F6 fix

`HubSpotStageMapping` resolves `isClosed`/`probability` metadata on each pipeline stage now,
never the stage `label` — the same approach §10's `SalesforceStageMapping` already used. A
renamed or custom stage label with valid metadata just resolves correctly; `label` is purely
informational (log/error messages only). `StageProbabilityBands` (`crm/repository`, shared with
Salesforce) holds the four band maxima + their validation + `forOpenStage(percent)`; each CRM
constructs its own instance from its own `bridge.crm.<crm>.stage-bands.*` config. HubSpot's
defaults are **20/50/70/99**, not Salesforce's 20/50/80/99 — HubSpot's live default pipeline has
"Decision Maker Bought-In" at probability 0.8, which must land in `NEGOTIATION` (matching the old
label mapping), not `PROPOSAL`; at Salesforce's 80-max boundary it wouldn't.

Metadata arrives as JSON strings on the live API (`"false"`, `"0.2"`), but `HubSpotStageMetadata`'s
fields are plain `String` so a portal sending real JSON booleans/numbers binds identically (Jackson's
default `String` deserializer accepts either). `probability` is parsed via `BigDecimal` (never
`Double` — `Double.parseDouble("NaN")` succeeds; `BigDecimal`'s string constructor has no NaN
representation at all) and compared to `0`/`1` with `compareTo`, never `equals` (`"1.0".equals(ONE)`
is `false`; `compareTo` is `0`).

**Policy table** (every row implemented in `HubSpotStageMapping`/`HubSpotDealRepository`, tested in
`HubSpotStageMappingTest`/`HubSpotDealRepositoryTest`):

| Case | Where | Outcome |
|---|---|---|
| Pipeline fetch fails; empty pipeline list; a stage with no id; every fetched stage invalid (any reason); bad `stage-bands` config; bad `max-skipped-ratio` | Before the per-deal loop, or at Spring startup | **FAIL STARTUP** (constructor) / **FAIL THE WHOLE SYNC RUN** (`snapshot()`/`fetchDeal()`) |
| Metadata missing on one stage; `isClosed` missing/not boolean-like; `probability` non-numeric/out-of-range/NaN; `probability` missing on a **closed** stage; closed stage with `0 < p < 1` (can't tell won from lost — HubSpot has no `IsWon`-equivalent flag); a deal's `dealstage` id not in the pipeline at all | Stage classified invalid during resolution (before the loop); effect realized per-deal inside `snapshot()`'s existing per-record `try/catch` | **SKIP** the affected deal(s), **+WARN** (once per invalid stage per run, not per deal), counted in `skippedAtRead` |
| Open stage, `probability` missing | Same resolution step | **MAP WITH DEFAULT** → `PROSPECT`, +WARN (once per stage per run) |
| `skippedAtRead / totalRecordsRead` **strictly greater than** `bridge.crm.hubspot.max-skipped-ratio` (default `0.5`; `0` records read never fails) | After the per-deal loop, before returning the `DealSnapshot` | **FAIL THE WHOLE SYNC RUN** (`HubSpotSkipThresholdExceededException`) instead of publishing a mostly-skipped snapshot |

**A skipped deal means a stale Task in Inbox, not a deleted one.** Neither Bridge (`CrmLane`,
`LoggingPayloadPublisher`/`KafkaPayloadPublisher`) nor Inbox (`StructuredTaskConsumer` →
`TaskIngestionService`) has any delete-by-absence path — a deal missing from one sync's published
set (skipped, or just not re-sent) leaves its Inbox `Task` exactly as it was after the last
successful publish. `CrmSyncJob` logs a WARN ("partial sync") when `skippedAtRead > 0` so a
scheduled run with skips doesn't look identical to a clean one in the logs; the HTTP path already
surfaces `skippedAtRead` in `CrmSyncResponse`'s JSON body.

`bridge.crm.hubspot.max-skipped-ratio` is **HubSpot-only for now** — Salesforce's per-record skip
(§10) has no equivalent aggregate threshold yet. Out of scope for this task; flagged as a
candidate for the same guard later.

## 12. Shared AI infrastructure (`com.rnd.bridge.ai`, added 2026-10-20)

Every model (LLM) call in Bridge goes through `LlmClients.forUsage("<usage>")` - see
`src/main/java/com/rnd/bridge/ai/README.md` for the five-line usage example, the settings and the
design (Registry + Decorator chain + Strategy per provider). Don't write another HTTP client for a
model; add a usage instead.

- Settings: `bridge.ai.*` - `providers` (each key once), `models` (the one shared list), optional
  `usages` overrides. "usage", not "profile": Spring's `@Profile` already means something else here
  (§5).
- **Free only, by design** (`bridge.ai.free-only: true`): startup fails if any listed model isn't
  `free: true`. The real guarantee is a Gemini key from a project with no billing.
- PII redaction (email/phone/Israeli ID/card/IBAN) applies to the text sent to the model only -
  never to what Bridge publishes (Mind reads `RawObjectEvent` unredacted, agreed with Mind's
  team, who chose on 2026-10-19 to keep separate LLM implementations rather than share one).
- **Free-tier budget:** Gemini's free tier allows ~20 requests/day per model per project, shared by
  every usage on it - keep the usages' `daily-call-limit`s summing to at most that. Thinking is
  off (`reasoning-effort: none`), replies capped (`max-output-tokens`). A 429 asking to wait
  longer than 30s is a spent quota: not retried, and that model fails fast until the reset.
- Media classification is the first user (usages `media-meeting`, `media-document`).
  `BRIDGE_MEDIA_CLASSIFIER_API_KEY` still works as a fallback for `BRIDGE_AI_GEMINI_API_KEY`.
- Not done yet: the Helm side (mounting the key into the cluster) - deliberately last, with relay,
  once everything works locally. `EnrichmentService` (§9.3) is a natural next user.
