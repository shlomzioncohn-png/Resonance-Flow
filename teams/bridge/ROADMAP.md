# Unstructured-intake lane — committed plan to the 2026-11-04 demo

This replaces the previous draft. Every item below is anchored to a specific, verified file/class/
line — not to `plan.md`'s prose, which has drifted from the code in places already caught and
corrected during this planning pass (see "Corrections made along the way" at the bottom — keep
that section; it's the reason this doc can be trusted over `plan.md`'s rollout-status claims).

**Demo: 2026-11-04.** Everything below is sequenced against that date. Breadth across all 5
sources is explicitly *not* the goal — a polished, reliable, demoable system is.

## 0. Verified current state (the anchor for everything below)

| Source | Real reader | Cursor | MinIO content landed | Notes |
|---|---|---|---|---|
| WhatsApp | ✅ `RealWhatsAppSourceReader` | n/a (file-drop) | ✅ actual export text | Done, no further work planned |
| Slack | ✅ `RealSlackSourceReader` + `SlackEventsController` (Events API, real-time) | ✅ `since` → `conversations.history.oldest` | ✅ | Most complete source; gaps are listed in §3 |
| Calendar | ✅ `RealCalendarSourceReader` | ✅ `syncToken`, guarded by `calendarCursorLock` in `UnstructuredIntakeController.runCalendarLocked` | ✅ | Already emits `ChangeType.DELETE_REVIEW` for cancelled events — see §2.3 |
| Gmail | ✅ `RealEmailSourceReader` | ❌ none — `EmailSourceReader.read()` takes no cursor param at all; `SourceRecordLoader` line `case EMAIL -> emailReader.read();` drops whatever cursor it's given | ✅ (body + per-attachment bytes) | **§1 — top priority** |
| Drive | ❌ `RealDriveSourceReader.read()` throws `UnsupportedOperationException` unconditionally | ❌ none | ❌ — even `FixtureDriveSourceReader` only lands the file's JSON *metadata*, never real bytes | **§2 — must land real content, not just metadata** |

Schema fact just confirmed, materially changes scope: **`change_significance_score` already exists
on the wire.** It's `raw-object-event.proto` field 11 (`int32 change_significance_score`), already
flowing through `RawObjectEventProtoMapper.setChangeSignificanceScore(...)`, already a component
of `RawObjectEvent`. Every producer today hardcodes it to `RawObjectEvent.
CURRENT_CHANGE_SIGNIFICANCE_SCORE = 100` (see that class's javadoc, which says so explicitly). So
**§4 (the LLM scoring feature) is "replace a hardcoded constant with a real LLM call," not
"design + add a new field."**

Also just confirmed: `ChangeType.DELETE_REVIEW` **already exists and is already emitted — by
Calendar**, not Slack (`RealCalendarSourceReader`/`FixtureCalendarSourceReader`, for cancelled
events). Slack's `message_deleted` gap (§3) is therefore "wire Slack's reconciler to the
`DELETE_REVIEW` path Calendar already proves out," not "invent delete handling from zero."

## 1. Gmail incremental sync (`historyId`) — **highest priority, do first**

**Why it's first, not just important:** without a cursor, every call to
`POST /api/bridge/intake/EMAIL/run` re-lists and re-fetches the entire mailbox
(`RealEmailSourceReader.read()` → `gmail.users().messages().list("me")`, no query filter, paged to
exhaustion). Repeated triggers during demo rehearsal or the demo itself risk visibly slow
responses and real Gmail API rate-limit exposure — this is an operational risk to the demo, not
just technical debt.

**Why it's cheap relative to its priority:** Calendar already solved the identical shape of
problem. `UnstructuredIntakeController.runCalendarLocked` (lines 111–126) is the exact template:
load cursor → read with it → publish → save the new cursor only if `rejected() == 0`, under a
dedicated lock to stop two concurrent runs from racing on the token. Gmail's `historyId` is
Google's equivalent of Calendar's `syncToken`.

**Concrete steps:**
1. `EmailSourceReader.read()` currently takes zero parameters — change its contract to accept a
   cursor (mirror `CalendarSourceReader.read(ReadCursor)` / `CalendarReadResult`'s shape: return
   both the records and the next cursor value together, not through shared reader state — same
   reason Calendar's javadoc gives for avoiding a race between concurrent calls).
2. `RealEmailSourceReader`: replace the unconditional `messages.list` with
   `users.history.list(startHistoryId)` when a prior `historyId` exists; fall back to the existing
   full `messages.list` only on first run (`ReadCursor.full()`) or if Gmail reports the historyId
   as expired (`404`/too-old — Gmail does not retain history forever, so a reader must be able to
   fall back to a full resync rather than fail).
3. `FixtureEmailSourceReader` needs the same signature (accept + ignore cursor), matching how
   `FixtureSlackSourceReader` already handles this — no behavior change, interface parity only.
4. Wire a new controller path analogous to `runCalendar`/`runCalendarLocked` — or confirm whether
   Gmail's history API has the same "can't advance past a token before everything up to it is
   published" constraint Calendar's `syncToken` has (it does — same reasoning applies: save the
   new `historyId` only after `rejected() == 0`).
5. Add a lock equivalent to `calendarCursorLock` for Gmail, same justification as Calendar's.

**Acceptance check:** calling the endpoint twice in a row with no new mail in between should do
effectively no work on the second call (verify via a test asserting `RealEmailSourceReader` is
invoked with a non-null `since`/`historyId` on the second call, same style as whatever test
already exists for Calendar's cursor wiring).

## 2. Drive — basic real reader, with actual content landed in MinIO

**Why this is in scope for the demo, not deferred like originally proposed:** confirmed directly —
parity with every other source requires the actual file content to land in MinIO via
`IntakeLandingService.land()`, which puts `SourceRecord.rawContent()` as-is. Metadata-only (what
even the fixture does today) is not acceptable parity.

**Why it's still smaller than it first looks:**
- `google-api-services-drive` is already a `pom.xml` dependency (line 145–147) — no new library.
- `GoogleCredentialsProvider` is already written to be shared across Calendar/Gmail/Drive (its own
  javadoc says so) — adding Drive only means requesting `DriveScopes.DRIVE_READONLY` in the scope
  list passed to `credentials()`, not a new auth mechanism.
- The branching logic needed (native Google file vs. plain binary) has a working precedent already
  in this exact codebase: `RealEmailSourceReader.collectBodyAndAttachments`/`attachmentBytes`
  already branches on "is the content inline" vs. "needs a separate fetch call" for Gmail
  attachments. Same shape of problem, same file to use as a model.

**Concrete steps:**
1. Add `DriveImportProperties` (`@ConfigurationProperties(prefix = "bridge.intake.drive")`) —
   3-line copy of `CalendarImportProperties`'s pattern (just needs a `tenantId`, possibly a root
   folder id to scope the sync).
2. `RealDriveSourceReader.read()`: replace the stub with `files.list()`, paged via `pageToken`
   (no cursor/incremental sync in this pass — matches where Gmail sits *before* §1 lands, and is
   an explicitly acceptable MVP scope, consistent with how every other source in this lane went
   real one capability at a time).
3. For each file, branch on `mimeType`:
   - **Native Google type** (`application/vnd.google-apps.*`): fetch via `files.export(fileId,
     mimeType)`. **Default export target: `text/plain`** — cheapest and most directly usable by
     downstream text processing (Mind); flag if you want PDF instead (trades text-minability for
     preserved formatting — your call, not a default I'd override silently).
   - **Everything else:** `files.get(fileId).executeMediaAsInputStream()` (or
     `alt=media`-equivalent in the Java client) — plain bytes, no branching needed, same as Gmail's
     non-inline attachment path.
4. Map to `SourceRecord` with the **real file bytes** as `rawContent()` (not the metadata-node
   serialization `FixtureDriveSourceReader` currently uses) — this is the one behavior genuinely
   new relative to every existing reader in this package, flagged explicitly since it changes the
   fixture/real parity contract going forward (fixture should arguably be updated to match, or the
   mismatch documented — your call on whether that's in scope now or a known fixture gap).

**Explicitly out of scope for the demo (confirmed earlier in this planning pass, unchanged):**
Drive push notifications / `files.watch` + domain-verified webhook — see §5.

## 3. Slack gaps — pick up only after §1 and §2 land

### 3.1 `message_deleted` handling
Now scoped smaller than originally described, because `ChangeType.DELETE_REVIEW` is a proven,
already-emitted pattern (Calendar) rather than a from-scratch concept:
1. `SlackEventMessageParser` currently returns `Optional.empty()` for `message_deleted` — needs a
   parse path that extracts the deleted message's stable id (the nested `previous_message.ts` or
   equivalent, per Slack's event shape — confirm exact field name against Slack's
   `message_deleted` event docs, not assumed here).
2. `ChatGroupStateReconciler` needs a new reconciliation path (there is genuinely no existing
   method for this — `group`/`reconcileNonThreadedMessage` both assume an upsert) that removes a
   `MessageSnapshot` from a `GroupState` and marks the resulting change as needing a
   `DELETE_REVIEW`-flavored republish, mirroring how Calendar's reader already constructs
   `ChangeType.DELETE_REVIEW` events today.

### 3.2 Stale-claim recovery after restart
Still genuinely unscoped — no existing partial implementation to extend. Needs a design decision
(on restart, does recovery replay every `GroupState` with `claimedRevision != null` and
re-schedule its timer, or something else?) before coding. Lowest priority in this doc; pick up
only if §1/§2/§4 land with time to spare.

## 4. LLM-backed features

Both share one prerequisite: a **generic `LlmClient` interface** (e.g. `complete(String prompt) ->
String`), with a Groq-backed implementation as the only bean today, following the exact
default-bean/`@Profile`-override convention already used throughout this codebase (§5 of
`teams/bridge/CLAUDE.md` — CRM's deal-source and publisher beans are the existing examples to
copy). Built this way, swapping Groq for another vendor later is "add one new `@Profile` bean,"
not a rewrite — build this first, not inline inside either feature below.

### 4.1 `change_significance_score` — smaller than previously estimated
**Correction from earlier in this planning pass: the field already exists on the wire (see §0).**
Remaining work is only:
1. Call `LlmClient` with a diff (old message text vs. new message text) when
   `ChatGroupStateReconciler` processes an edit, get back a 0–100 score.
2. Replace `RawObjectEvent.CURRENT_CHANGE_SIGNIFICANCE_SCORE`'s hardcoded `100` with the real
   score for edited chat messages specifically (non-chat sources have no "edit" concept yet, so
   they keep the hardcoded value — don't invent edit-significance for sources that never emit a
   revision > 0 in the first place).
3. MIND is confirmed already coordinated to consume this field — no cross-team schema negotiation
   needed, unlike the `occurred_at` precedent in `docs/bridge-structured-tasks-proto-spec.md`.

### 4.2 LLM session-boundary decision (Slack grouping)
Larger and less scoped than 4.1 — still needs real design work, not just "call the LLM":
1. **Where in the pipeline:** the natural seam is inside `SlackSessionScheduler`'s fire path
   (`onDeadline`), right before a time-gap group is actually claimed/published — call `LlmClient`
   with the current group's messages + the previous published group for the same channel.
2. **What a "split" decision requires that doesn't exist yet:** `GroupStateStore` has no operation
   today for splitting one `GroupState` into two — every existing mutation
   (`claimForPublication`/`confirmPublished`/`recordLandedAttachment`/`markSuperseded`) assumes one
   group stays one group. A split needs a new store operation, and a decision on how the *new*
   group's `groupId`/`correlationId` get minted (presumably the same minting logic
   `ChatGroupStateReconciler.newGroupState` already uses for any new group).
3. **Failure/timeout behavior — not yet decided:** falling back to the existing time-gap decision
   on an LLM failure is the obvious default (keeps the feature additive, never a regression if the
   LLM call fails), but confirm before building, since it affects whether this feature can ever
   make the pipeline *less* reliable than it is today.

## 5. Explicitly deferred past the demo (confirmed decisions, not oversights)

- **Google push notifications** (Gmail Pub/Sub watch, Calendar/Drive watch channels + webhook).
  Confirmed nothing exists in code (`pom.xml` has no Pub/Sub dependency; no `watch`/`webhook`
  reference anywhere in `src/main/java/com/rnd/bridge`). Deferred because: (a) Calendar/Drive
  channel registration requires Google domain-ownership verification — a turnaround outside this
  team's control; (b) Slack's existing real-time Events API already carries the "reacts to new
  data automatically" demo story; (c) it would directly compete for time against §1/§2/§4, which
  have higher confirmed value. **This is a recommendation already given, not yet explicitly
  re-confirmed by you after the full explanation — flag if you want to revisit.**
- **Allowlist (`AllowlistScopingFilter`)** — intent is to fold this into an LLM-based scoping
  decision rather than wire static `ScopingProperties`, per your direction. Not scheduled in this
  plan; would reuse the `LlmClient` interface from §4 if picked up later.
- **Demo fixture/test scenario** (a prepared messy conversation + mix of trivial/significant
  edits) — explicitly deferred to closer to the demo date, per your call.

## 6. Sequencing against 2026-11-04

Rough week-by-week, assuming work starts the week of 2026-10-07:

1. **Week 1 (by ~10-13):** §1 Gmail incremental sync, end to end, tested.
2. **Week 2 (by ~10-20):** §2 Drive real reader (list + export/download branching + MinIO
   landing), tested against at least one native Google file and one binary file.
3. **Week 3 (by ~10-27):** §4.0 `LlmClient` interface + Groq implementation; §4.1
   `change_significance_score` real scoring (smallest of the two LLM features — do it first to
   prove the `LlmClient` seam works end to end before the harder one).
4. **Week 4 (by ~11-03):** §4.2 LLM session-boundary decision — the largest remaining item;
   starting it last leaves the least slack, so land §1/§2/§4.1 solidly first. If §4.2's full split
   logic (point 2 above) doesn't fit, a reduced version (LLM decides continuation-vs-not, split
   deferred) is an acceptable fallback — flag before cutting scope this way, don't decide silently.
5. **Remaining slack time, if any:** §3.1 Slack `message_deleted`. §3.2 stale-claim recovery and
   §5's deferred items stay out of scope regardless of time remaining, per the decisions above.

## 7. Open decisions still needed from you before coding starts

1. **Drive export format for native Google files** — defaulting to `text/plain` unless you say
   otherwise (reasoning in §2, step 3).
2. **Google push notifications (§5)** — you were about to read the full complexity breakdown when
   the conversation moved on; confirm defer-past-demo or reopen.
3. **§4.2 fallback scope** — acceptable to ship "continuation decision only, no split" as a
   fallback if full split logic doesn't fit in Week 4? (§6, point 4)
4. **Gmail `historyId` expiry fallback** — confirm full-resync-on-expiry (step 2 in §1) is the
   right behavior, not an error that should surface instead.

## Corrections made along the way (why this doc, not `plan.md`, is the trustworthy one)

- Originally stated Gmail/Calendar were "fixture only" — wrong; both have real, OAuth-backed
  readers. Found by actually reading `RealEmailSourceReader`/`RealCalendarSourceReader`, not by
  trusting `plan.md`'s "rollout order" section, which reads as aspirational in places.
  Drive's stub status, by contrast, *was* accurately "not implemented" — verified directly.
- Originally sized Drive as metadata-only/small — wrong once MinIO content-parity was raised;
  revised to "medium," with the export/download branching called out explicitly.
- Originally described `change_significance_score` as an open schema design question — wrong; it's
  already on the wire, hardcoded to a constant. Found by reading `raw-object-event.proto` and
  `RawObjectEvent.java` directly after being asked to prove real familiarity with the schema,
  rather than re-stating the plan's framing of it as undesigned.
