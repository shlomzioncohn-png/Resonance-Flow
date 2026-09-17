# Field specification: `bridge.structured.tasks` → Protobuf

Handoff document for whoever authors the `.proto` for this topic, per
`teams/relay/docs/kafka-producer-consumer-guide.md` §2/§6 ("bring field names, types, and the
topic name — you don't write the `.proto` yourself"). This is a from-scratch schema: `main` has no
`schema-subjects.tsv` row and no `.proto` for `bridge.structured.tasks` today (verified
2026-09-15), so nothing here is constrained by an already-registered version — field numbers below
are proposed, not fixed.

Source of truth: `teams/bridge/src/main/java/com/rnd/bridge/task/BridgeTaskEventDto.java` as it
exists on branch `ruth/crm-per-record-parse-fix` today. The wire format is plain camelCase JSON —
Inbox confirmed this directly on 2026-09-15 (they have no `@JsonNaming` and no global Jackson
naming strategy on their side either). Whatever the current or future JSON casing convention turns
out to be, it's orthogonal to this document: Protobuf field naming is independent of JSON casing
debates entirely, per Relay's own point that wire-level field naming doesn't apply to a binary,
number-keyed format.

**Topic:** `bridge.structured.tasks`
**Producers:** Bridge CRM lane (`CrmLaneConfig` → `KafkaPayloadPublisher`) and Bridge JIRA lane
(`JiraKafkaProducer`) — both build `BridgeTaskEventDto` from a `Task` and publish it to this same
topic. Any schema decision here affects both lanes identically; they share one DTO and one topic.
**Consumer:** Inbox's `StructuredTaskConsumer`/`StructuredTaskEventDto`
(`teams/inbox/src/main/java/com/rnd/inbox/structuredtask/`) — currently a raw `StringDeserializer`
+ hand-written Jackson JSON DTO, not Protobuf (verified against `origin/main` 2026-09-15).

## Field table

| # | Field (current DTO name) | Java type today | Proposed proto field | Proposed proto type | Optional? |
|---|---|---|---|---|---|
| 1 | `eventId` | `String` (UUID text) | `event_id` | `string` | No — always a fresh `UUID.randomUUID()` per publish (`from()`), never null |
| 2 | `tenantId` | `String` (UUID text) | `tenant_id` | `string` | No |
| 3 | `projectId` | `String` (UUID text) | `project_id` | `string` | **Yes** — `optional string` |
| 4 | `title` | `String` | `title` | `string` | No |
| 5 | `description` | `String` | `description` | `string` | **Yes** — `optional string` |
| 6 | `sourceType` | `String` (enum name) | `source_type` | `SourceType` (enum) | No |
| 7 | `sourceRef` | `String` | `source_ref` | `string` | No |
| 8 | `tier` | `String` (enum name, nullable) | `tier` | `Tier` (enum) | **Yes** — `optional Tier` |
| 9 | `roleContext` | `String` (enum name, nullable) | `role_context` | `RoleContext` (enum) | **Yes** — `optional RoleContext` |
| 10 | `urgency` | `Integer` (1–5, nullable) | `urgency` | `int32` | **Yes** — `optional int32` |
| 11 | `effortEstimateMinutes` | `Integer` (nullable, >0) | `effort_estimate_minutes` | `int32` | **Yes** — `optional int32` |
| 12 | `deadline` | `Instant` (nullable) | `deadline` | `google.protobuf.Timestamp` | Not needed — message-typed fields have automatic presence (see below) |
| 13 | `status` | `String` (enum name) | `status` | `TaskStatus` (enum) | No |
| 14 | `confidence` | `Double` (nullable, 0.0–1.0) | `confidence` | `double` | **Yes** — `optional double` |
| 15 | *(new — see below)* `occurredAt` | doesn't exist yet | `occurred_at` | `google.protobuf.Timestamp` | No — always known at publish time |

## Nullability — where proto3's no-absent-vs-zero distinction actually bites

proto3 scalar fields default to `""` / `0` / `false` and, without the `optional` keyword, cannot
distinguish "not set" from "set to the zero value." Going field by field against the current DTO:

- **`projectId` (#3):** always `null` today — `AbstractTaskMapper.map()` hard-codes
  `null // projectId - no lane currently sources this` for every lane. An empty string would be a
  real (if theoretical) project id, so absent-vs-empty matters. → `optional string project_id`.
- **`description` (#5):** legitimately null (e.g. a HubSpot deal with no description field set).
  Empty string ("no text") and absent ("field not populated") aren't the same claim. →
  `optional string description`.
- **`tier` (#8) / `roleContext` (#9):** nullable by design — Inbox's own spec says these stay
  unset "until Mind classifies" (`teams/inbox/CLAUDE.md` §2). CRM's `DealToTaskMapper` always sets
  `tier` today, but the DTO/contract must not assume every lane does. → `optional` enums.
- **`urgency` (#10) / `effortEstimateMinutes` (#11) / `confidence` (#14):** this is the case you
  flagged, and it's real. `0` is not a valid `urgency` (range is 1–5) but *is* the proto3 zero
  value for `int32`, so an unset `optional int32` and a wire bug sending literal `0` would be
  indistinguishable without `optional`. `confidence = 0.0` is explicitly a **meaningful** value
  (`AbstractTaskMapper.confidence()`'s Javadoc: a deterministic ETL lane "has nothing to be
  confident about, so the default is null" — i.e. null and 0.0 are two different claims about a
  CRM-sourced task) — this is exactly the field where conflating them would be a real bug, not a
  pedantic one. → all three get `optional`.
- **`snoozedUntil`, `completedAt`:** you named these as expected candidates, but neither exists
  on `BridgeTaskEventDto` — see the omitted-field section below; they're intentionally not in this
  spec at all.
- **`eventId`, `title`, `sourceType`, `sourceRef`, `status`:** never legitimately null/absent in
  today's code (`from()` always populates them, `toStatus()`/`toTitle()`/etc. are non-nullable
  abstract contracts on `AbstractTaskMapper`) → plain required fields, no `optional`.

## Timestamps and UUIDs

- **UUIDs** (`eventId`, `tenantId`, `projectId`): plain `string`, per the guide — "Protobuf
  has no native UUID type, so id fields are declared as `string` directly." No wrapper type needed.
- **Timestamps** (`deadline`, new `occurred_at`): `google.protobuf.Timestamp`, not an ISO-8601
  string. Two reasons: (1) it's the idiomatic proto3 choice and every existing schema in
  `teams/relay/src/main/proto/` already uses it for every `Instant`-typed field; (2) as a
  *message*-typed field, `google.protobuf.Timestamp` gets automatic wire presence for free —
  proto3 always distinguishes "message not set" from "message set to its zero value" for
  non-scalar fields, so `deadline` doesn't need (and by convention shouldn't use) the `optional`
  keyword the way the nullable scalars above do. `occurred_at` is always populated at publish
  time, so this distinction doesn't matter for it in practice, but the type choice is the same.

## Enums

All five enums need `*_UNSPECIFIED = 0` and a type-name prefix per the guide ("proto3 needs the
prefix anyway... every enum has an `*_UNSPECIFIED = 0`"). This differs from **both** existing wire
conventions in this codebase: today's JSON DTO sends the bare `Enum.name()` (`"CRM_DEAL"`, no
prefix), and `Task`'s own `@JsonValue` sends lowercase (`"crm_deal"`) for the *database* shape —
neither survives into the proto version unchanged.

**`SourceType`** (`teams/bridge/src/main/java/com/rnd/bridge/task/model/SourceType.java` — current
11 values, checked today):

| Bridge Java constant | Proposed proto value |
|---|---|
| — | `SOURCE_TYPE_UNSPECIFIED = 0` |
| `GITHUB_ISSUE` | `SOURCE_TYPE_GITHUB_ISSUE = 1` |
| `GITHUB_PR` | `SOURCE_TYPE_GITHUB_PR = 2` |
| `JIRA_ISSUE` | `SOURCE_TYPE_JIRA_ISSUE = 3` |
| `CRM_DEAL` | `SOURCE_TYPE_CRM_DEAL = 4` |
| `EMAIL` | `SOURCE_TYPE_EMAIL = 5` |
| `SLACK` | `SOURCE_TYPE_SLACK = 6` |
| `WHATSAPP` | `SOURCE_TYPE_WHATSAPP = 7` |
| `MEETING` | `SOURCE_TYPE_MEETING = 8` |
| `DOC` | `SOURCE_TYPE_DOC = 9` |
| `SCANNED_DOC` | `SOURCE_TYPE_SCANNED_DOC = 10` |
| `CALENDAR` | `SOURCE_TYPE_CALENDAR = 11` |

> **Blocking issue, not a style note:** Inbox's own `com.rnd.inbox.task.SourceType` (checked
> directly, `teams/inbox/src/main/java/com/rnd/inbox/task/SourceType.java`) has **no `JIRA_ISSUE`
> value at all** — its 12 values are GITHUB_ISSUE, GITHUB_PR, CRM_DEAL, EMAIL, SLACK, WHATSAPP,
> MEETING, DOC, SCANNED_DOC, CALENDAR, MANUAL, DRIVE. Worse, `StructuredTaskEventDto`'s
> `sourceType` field is validated with `@ValidEnum(enumClass = SourceType.class, allowed =
> {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL"})` — an explicit allow-list for *this topic* that
> doesn't even include `WHATSAPP`/`MEETING`/etc., let alone `JIRA_ISSUE`. **This means the JIRA
> lane's messages are rejected by Inbox today, JSON or Protobuf, regardless of this migration** —
> every `sourceType: "JIRA_ISSUE"` message fails `@ValidEnum` and routes to Inbox's error topic.
> This isn't a proto-migration problem to solve here; it's a pre-existing contract gap between
> Bridge and Inbox that needs its own fix (adding the value to Inbox's enum, and to this
> validator's allow-list) independent of whether the wire format is JSON or Protobuf.

**`TaskStatus`** (8 values):

| Bridge Java constant | Proposed proto value |
|---|---|
| — | `TASK_STATUS_UNSPECIFIED = 0` |
| `NEW` | `TASK_STATUS_NEW = 1` |
| `IN_REVIEW` | `TASK_STATUS_IN_REVIEW = 2` |
| `APPROVED` | `TASK_STATUS_APPROVED = 3` |
| `IN_PROGRESS` | `TASK_STATUS_IN_PROGRESS = 4` |
| `SNOOZED` | `TASK_STATUS_SNOOZED = 5` |
| `DONE` | `TASK_STATUS_DONE = 6` |
| `REJECTED` | `TASK_STATUS_REJECTED = 7` |
| `CANCELLED` | `TASK_STATUS_CANCELLED = 8` |

**`Tier`** (3 values):

| Bridge Java constant | Proposed proto value |
|---|---|
| — | `TIER_UNSPECIFIED = 0` |
| `OPERATIONAL` | `TIER_OPERATIONAL = 1` |
| `TACTICAL` | `TIER_TACTICAL = 2` |
| `STRATEGIC` | `TIER_STRATEGIC = 3` |

**`RoleContext`** (6 values):

| Bridge Java constant | Proposed proto value |
|---|---|
| — | `ROLE_CONTEXT_UNSPECIFIED = 0` |
| `DEVELOPER` | `ROLE_CONTEXT_DEVELOPER = 1` |
| `MANAGER` | `ROLE_CONTEXT_MANAGER = 2` |
| `HIGHER_MANAGER` | `ROLE_CONTEXT_HIGHER_MANAGER = 3` |
| `FIELD_ENGINEER` | `ROLE_CONTEXT_FIELD_ENGINEER = 4` |
| `SALESPERSON` | `ROLE_CONTEXT_SALESPERSON = 5` |
| `CEO` | `ROLE_CONTEXT_CEO = 6` |

**`ExtractionProvenance`** — **not sent on the wire today** (it's one of the 11 omitted fields,
listed for completeness only; see below). 3 values if it's ever added: `ETL`, `ML_INFERRED`,
`MANUAL`.

## The omitted-field contract

Verified directly against the current `BridgeTaskEventDto` record declaration (14 fields:
`eventId`, `tenantId`, `projectId`, `title`, `description`, `sourceType`, `sourceRef`,
`tier`, `roleContext`, `urgency`, `effortEstimateMinutes`, `deadline`, `status`, `confidence`) —
all 11 fields the contract says Inbox generates and forbids on the wire are confirmed absent
today:

`id`, `correlationId`, `isAvailable`, `priorityScore`, `ownerId`, `createdBy`,
`extractionProvenance`, `createdAt`, `updatedAt`, `completedAt`, `snoozedUntil`.

**The proto must preserve every one of these exclusions.** None of them should get a field number
in the new schema — adding one back in would silently reintroduce a field Inbox's own contract
says it owns.

## New field: `occurred_at`

Doesn't exist on `BridgeTaskEventDto` today — **flagged here as new, not a rename or
reinterpretation of an existing field.** Purpose: carry the HubSpot stage-change timestamp
(`Deal.stageChangedAt`, currently used internally by `HubSpotDealRepository`/`DealToTaskMapper`
but never put on the wire) so Inbox's consumer can do last-write-wins when the same `sourceRef`
arrives out of order or gets redelivered — directly relevant given `DealToTaskMapper` already
emits multiple events per deal (one per historical stage transition, all sharing one `sourceRef` —
see this lane's own idempotency-key design). Type: `google.protobuf.Timestamp`, required (always
known at publish time, sourced from the same place `stageChangedAt` already comes from). Source
lanes other than CRM (JIRA, and any future lane) would need their own equivalent "when did this
change happen" timestamp to populate it — worth confirming with whoever authors the schema whether
`occurred_at` should be `optional` for lanes that don't have an equivalent value yet, or whether
every lane must supply one before this field can be added.

## Open questions (see chat response for the full list with more cross-team context)

1. ~~Is `organization_id` the right name, or should it already be `tenant_id`?~~ **Resolved
   2026-09-15: `tenant_id`.** Confirmed directly with Inbox — they renamed `organization_id` to
   `tenant_id` across their schema on 2026-09-06 (#255, `9684b8e8`), including the
   `bridge.structured.tasks` idempotency index (now `(tenant_id, source_type, source_ref)`).
   `BridgeTaskEventDto` sent `organizationId` for over a week after that rename and every message
   was silently rejected by Inbox's `@NotBlank` validation on `tenantId` — now fixed on the DTO
   (field #2 above). `task.model.Task` keeps `organizationId` deliberately (see `CLAUDE.md` §4/§6)
   — that's an internal Bridge naming inconsistency, not a wire-contract question, and doesn't
   affect this proto spec.
2. `SourceType.JIRA_ISSUE` is unrecognized by Inbox's consumer today — pre-existing, orthogonal to Protobuf.
3. Cutover strategy for the topic (dual-format transition period vs. hard cutover).
4. `schema-subjects.tsv` has no row for this topic yet — needs a Relay-side addition once the `.proto` is authored.
5. The registry has not been proven against a real deployed Apicurio instance anywhere in this repo (only `MockSchemaRegistryClient`, on the unmerged `avigail/issue-227-schema-registry-verification` branch, for a different set of topics).
6. Whether `occurred_at` should be optional for lanes without a natural "changed at" timestamp.
