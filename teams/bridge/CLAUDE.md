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
`task` packages.

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
- Full suite as of the last run: **121 tests, 0 failures** (was 115 before `HubSpotClient`'s retry
  logic was extended to 502/503/504 and transport failures - 3 tests added to
  `HubSpotDealRepositoryTest`, plus a new `HubSpotClientTest` for the transport-failure and
  connect/read-timeout paths, which don't go through `MockWebServer` socket tricks - see that
  class's Javadoc for why).

## 8. Local environment quirks (this machine)

- **No `mvn` on `PATH`.** Use IntelliJ's bundled Maven:
  `` `C:\Program Files\JetBrains\IntelliJ IDEA <version>\plugins\maven\lib\maven3\bin\mvn.cmd` ``
  (find the exact `<version>` dir under `C:\Program Files\JetBrains\` — it changes with IDE
  updates).
- **Default JDK breaks Mockito.** `.jdks\openjdk-26.0.1` (found via `$env:USERPROFILE\.jdks`) is
  too new for this Spring Boot/Mockito version — `@Spy` fields fail to initialize. Use JDK 23 at
  `C:\Program Files\Java\jdk-23` instead (`$env:JAVA_HOME` + prepend `bin` to `$env:PATH`); it
  compiles the pom's `java.version=21` target fine and Mockito works normally under it.
