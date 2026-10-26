# Unstructured-intake lane — committed plan to the 2026-11-04 demo

This replaces the previous draft. Every item below is anchored to a specific, verified file/class/
line — not to `plan.md`'s prose, which has drifted from the code in places already caught and
corrected during this planning pass (see "Corrections made along the way" at the bottom — keep
that section; it's the reason this doc can be trusted over `plan.md`'s rollout-status claims).

**Demo: 2026-11-04.** Everything below is sequenced against that date. Breadth across all 5
sources is explicitly *not* the goal — a polished, reliable, demoable system is.

## 0. Verified current state (the anchor for everything below) — re-verified 2026-10-18

**This table was last accurate at the planning pass that opened this doc; §1 and §2 below have
since been fully implemented in the working tree (confirmed 2026-10-18 by reading the actual
classes, not by trusting the table's own prior framing) — ahead of this doc's own Week 2 target.**

| Source | Real reader | Cursor | MinIO content landed | Notes |
|---|---|---|---|---|
| WhatsApp | ✅ `RealWhatsAppSourceReader` | n/a (file-drop) | ✅ actual export text | Done, no further work planned |
| Slack | ✅ `RealSlackSourceReader` + `SlackEventsController` (Events API, real-time) | ✅ `since` → `conversations.history.oldest` | ✅ | Most complete source; gaps are listed in §3 |
| Calendar | ✅ `RealCalendarSourceReader` | ✅ `syncToken`, guarded by `calendarCursorLock` in `UnstructuredIntakeController.runCalendarLocked` | ✅ | Already emits `ChangeType.DELETE_REVIEW` for cancelled events — see §2.3 |
| Gmail | ✅ `RealEmailSourceReader` | ✅ **done, not pending** — `EmailSourceReader.read(ReadCursor)` takes a cursor, returns `EmailReadResult(records, nextHistoryId)`; `UnstructuredIntakeController.runEmail` wires it end to end, same pattern as Calendar | ✅ (body + per-attachment bytes) | **§1 complete — see status note below** |
| Drive | ✅ **done, not a stub** — `RealDriveSourceReader.read(ReadCursor)` baselines via `files.list()` once, then `changes.list`/`startPageToken` thereafter | ✅ **done 2026-10-18** — `startPageToken`, guarded by the same `tokenCursorLock` as Calendar/Email via `UnstructuredIntakeController.runDrive`; `DriveFileStateReconciler`/`DriveFileStateStore` give real correlationId/revision per `fileId` (no more `UUID.randomUUID()`/hardcoded `revision=0`) | ✅ real bytes landed, not metadata-only | **§2 and §3.3 both complete** — emits `ChangeType.DELETE_REVIEW` on `trashed`, mirroring Calendar's cancellation path |

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

## 1. Gmail incremental sync (`historyId`) — ✅ **DONE** (confirmed 2026-10-18)

The steps below were the original plan and are kept for the record/acceptance-check reference —
all of steps 1-5 are implemented in the current working tree (`EmailSourceReader.read(ReadCursor)`,
`EmailReadResult`, `RealEmailSourceReader`, `UnstructuredIntakeController.runEmail`). No further
work needed here; the "highest priority, do first" framing below is now historical context, not a
live instruction.

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

## 2. Drive — basic real reader, with actual content landed in MinIO — ✅ **DONE** (confirmed 2026-10-18)

Steps 1-4 below are all implemented in the current working tree (`DriveImportProperties`,
paginated `files.list()`, export/download branching by `mimeType`, real bytes into
`SourceRecord.rawContent()`) — ahead of this doc's own Week 2 (~10-20) target. Kept for the
record/acceptance-check reference. **§3.3 (Drive `headRevisionId`/`trashed` incremental cursor +
edit/delete) also now DONE, 2026-10-18** — see §3.3 below for what landed and what's still
genuinely open (real-account verification, not unit coverage).

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

## 3. Edit/delete coverage across all 5 sources — audited 2026-10-18

This was previously undocumented as a cross-source question — this doc only described Slack's gap
(§3.1 below) without ever checking whether Gmail/Drive/WhatsApp even *can* signal an edit/delete at
the platform level, or whether anything in this codebase reacts to it when they can. Audited
directly against the source of each reader/parser, not inferred from this doc's own prior framing.

| Source | Platform signals edit? | Platform signals delete? | Edit implemented here? | Delete implemented here? |
|---|---|---|---|---|
| WhatsApp | ❌ no edit concept in export format | ❌ no delete event — a deletion is just literal text `"This message was deleted"` in the export | — (nothing to implement against) | — (nothing to implement against) |
| Slack | ✅ `message_changed` event | ✅ `message_deleted` event | ✅ done — `SlackEventMessageParser.parse()` remaps `event.message.ts` as the stable id so `ChatGroupStateReconciler` republishes a bumped revision (`ChangeType.UPSERT`) | ✅ done (2026-10-20, §3.1) — `SlackEventMessageParser.parseDeletion()` handles `message_deleted` and nested-`tombstone` `message_changed`; `ChatGroupStateReconciler.removeMessage` produces `ChangeType.DELETE_REVIEW` once a group empties entirely |
| Calendar | ✅ updated event via `syncToken` | ✅ cancelled event (`status: "cancelled"`) via `syncToken` | ✅ done — same `events.list` + `syncToken` path, revision bump via `CalendarEventStateReconciler` | ✅ done — `RealCalendarSourceReader.toSourceRecord()` detects `isCancelled(event)` and builds a `ChangeType.DELETE_REVIEW` record via `toCancelledSourceRecord()`; `FixtureCalendarSourceReader` mirrors it |
| Gmail | ⚠️ not really — email bodies are immutable once sent, no edit concept | ✅ `users.history.list` reports `messagesDeleted` / `labelsRemoved` (e.g. moved to Trash) | — (nothing to implement against) | ❌ not done — `RealEmailSourceReader.readIncremental()` only reads `history.getMessagesAdded()`; `getMessagesDeleted()`/`getLabelsRemoved()` are never inspected. Every record also gets a fresh random `correlationId` and `revision=0` every read, so there isn't even a stable identity across reads to attach a delete to yet |
| Drive | ✅ `changes.list` reports `modifiedTime` | ✅ `changes.list` reports `trashed`/`removed` | ✅ **done 2026-10-18** — `RealDriveSourceReader.read(ReadCursor)` baselines via `files.list()` once (minting a `startPageToken` alongside it), then `changes.list(startPageToken)` on every later run; `headRevisionId` changes bump revision via `DriveFileStateReconciler`/`DriveFileStateStore` (real correlationId/revision per `fileId`, replacing the old `UUID.randomUUID()`/hardcoded `revision=0`) | ✅ **done 2026-10-18** — `file.trashed=true` → `ChangeType.DELETE_REVIEW` via `toTrashedSourceRecord()`, mirroring `RealCalendarSourceReader.toCancelledSourceRecord()`; content is never fetched for a trashed file. `removed=true` still deliberately unhandled (skipped outright), per the decision below |

**Decisions made 2026-10-18 (after auditing both the platform APIs and this codebase):**

- **Gmail — deliberately out of scope, both edit and delete.** No edit concept exists at the
  platform level (email is immutable once delivered). Delete was investigated and explicitly
  declined: deleting a sent email doesn't propagate to the recipient's copy at all, and deleting a
  received email is overwhelmingly a storage-cleanup action, not a signal correlated with the
  underlying task's state — unlike Calendar's cancellation or Slack's edit/retraction, which *are*
  semantically tied to the task. Confirmed via Google's docs that the two plausible signals are
  non-redundant and neither matches "user clicked delete" cleanly: `labelsRemoved(INBOX)` +
  `labelsAdded(TRASH)` fires on move-to-trash (the common case), while `messagesDeleted` fires only
  on permanent purge (rare, often 30 days later) — reinforces that there's no clean mechanism here
  even if the semantic case were stronger.
- **Drive — edit: support it, via `headRevisionId`, not checksum diffing.** `changes.list`
  embeds the updated `File` resource inline per change record, so requesting `headRevisionId` in
  the fields mask costs no extra API call. It is Google's own content-revision-tracking mechanism
  (the same one behind Google Docs' "Version history"), not an invented heuristic — a new revision
  is created specifically when content changes, not on rename/move/permission changes, though this
  particular behavior is conventional/widely-relied-upon rather than written verbatim in Google's
  reference docs for this field. Risk is a false positive (treating an unedited file as edited —
  e.g. a format-conversion-on-upload or "restore a previous revision" minting a new revision id
  without a human-perceived edit) rather than a false negative; judged low-frequency and low-severity
  (costs a redundant reprocessing pass, not a correctness error) and accepted as a reasonable
  tradeoff versus the cost of `md5Checksum` diffing. **`version` was explicitly ruled out** — Google
  documents it as bumping on every change including ones invisible to the user.
- **Drive — delete: support only `trashed`, not `removed`.** `file.trashed=true` (with
  `removed=false`, file still present in the change record) is a clean, documented, zero-extra-call
  boolean — directly analogous to Calendar's `status: "cancelled"`. `removed=true` is explicitly
  declined: Google's own doc text describes it as covering at least two different causes ("deletion
  or loss of access") with file absent from the record once hit, making true deletion
  indistinguishable from the account losing access (e.g. unshared) without an extra `files.get`
  call to interpret a 404 vs. 403 — too ambiguous to build on for this pass.
- Slack delete (§3.1) is unaffected by the above — still an open, not-yet-decided item (see
  questions below).

**Resolved 2026-10-18 (asked directly, answers below):**
1. **Gmail scope** — confirmed fully out of scope, both edit and delete. Edit was never actually a
   choice (no platform mechanism exists — email is immutable once delivered, so there was nothing
   to decide there, only to confirm). Delete is a deliberate decline, same reasoning as above
   (doesn't correlate with task state, and the two plausible API signals don't even cleanly match
   "user clicked delete"). Not reopened; no residual ambiguity between "declined" and "still open."
2. **Drive sequencing** — `headRevisionId` (edit) and `trashed` (delete) both go in the **same
   week as §2** (§6's "Week 2, by ~10-20"), not deferred to slack time — see updated §6 below.
3. **§3.2 (Gmail) section** — kept in this document, marked declined, as a documented
   considered-and-rejected record (so it doesn't get silently re-raised later without the
   reasoning being visible).
4. **Slack delete (§3.1)** — confirmed still in scope on its own merits. The Gmail criterion
   (does a delete event correlate with real task-state change?) does NOT disqualify Slack: a
   retracted/corrected message is itself meaningful content history, unlike an email leaving a
   mailbox. §3.1 stands unchanged.

### 3.1 Slack `message_deleted` handling — ✅ **DONE** (2026-10-20)
Implemented per `docs/plans/slack-message-deleted.md` (see `docs/slack-deletion-contract.md` for
the consumer-facing contract summary — replacement semantics, `DELETE_REVIEW`, timing); landed as
one change since the two
pre-existing bugs it surfaced (thread-root edits getting silently absorbed into a dead group, and
the timer racing a burst of corrections) shared the same root cause/fix surface as deletion itself.
1. **Parser** — `SlackEventMessageParser.parseDeletion()` (new, alongside the unchanged `parse()`)
   handles both real Slack deletion shapes: top-level `message_deleted` (identity from `deleted_ts`,
   cross-checked against `previous_message.ts` with a logged warning on mismatch — `deleted_ts`
   always wins), and `message_changed` with a nested `message.subtype == "tombstone"` (a
   root-with-replies deletion; identity from the nested message's own `ts`, unchanged by the
   tombstone). A nested `bot_message` subtype (link-unfurl) is now also detected and ignored
   outright in `parse()` — previously unguarded and a real text-corruption risk (the unfurl's own
   `text` would silently overwrite the real message's stored content).
2. **Routing fix, broader than just deletion** — `ChatGroupStateReconciler.resolveCurrentGroupId`
   tries a direct `GroupStateStore.load(messageId)` before falling back to the messageId index.
   This fixes a **pre-existing, independent bug**: a thread root's own event never carries
   `thread_ts`, so an edit/deletion of a root arriving after the thread was created used to resolve
   (via the index alone) to the root's original channel group forever (at the time, permanently
   marked `superseded` — see §3.1a, since removed) — silently absorbed, never reaching Kafka. The direct load bypasses that stale index whenever a
   thread group actually exists at that id. `SlackEventsController`/`reconcileNonThreadedMessage`
   now route by the **resolved group's own kind** (thread vs. non-thread), not by the event's own
   `threadId()` field.
3. **Removal** — `ChatGroupStateReconciler.removeMessage` is the new reconciliation path (no
   existing method removed from `messages()` before this): resolves the group, removes the matching
   `MessageSnapshot`, and bumps revision using the same rule as any other content change. Deleting
   ONE message out of a multi-message session is a content-shrinking edit, not a deletion of the
   session (`RawObjectEvent`/`correlationId` represent the whole session) — stays `UPSERT`.
   `ChangeType.DELETE_REVIEW` only once the group empties entirely, same bar Calendar/Drive use. An
   unknown/already-removed `messageId` is a no-op, no revision bump.
4. **Publish timing** — new `ChatSessionProperties.correctionDelaySeconds` (default 60). A group
   that `maybePublished` (`published() || revision() > 0 || claimedRevision() != null`) publishes
   `correctionDelay` after the triggering change instead of waiting out the full session gap,
   collapsing a burst of rapid edits/deletions into one publish via the existing timer
   cancel-and-replace logic — **this changes timing for ordinary edits too, not just deletions**:
   previously a correction to an already-published-ish group would ride the full `sessionGapMinutes`
   debounce like any other message. `SlackSessionScheduler.onDeadline` skips its staleness recheck
   entirely once `maybePublished` (otherwise a `now + correctionDelay` deadline always looks "early"
   and never fires); the ordinary still-open recheck is unchanged. `ChatSourceRecordMapper` gained
   the empty-`GroupState` branch (`toDeleteReviewRecord`) this needed regardless of design choice —
   `toSourceRecords(GroupState, List)` used to throw on an empty `messages()`.
   **Two more real, pre-existing bugs surfaced and fixed while verifying this end to end (neither
   caught by any prior unit test, since neither had ever been exercised with a real, non-mocked
   `DELETE_REVIEW` payload before):** `SlackSessionPublisher.publishSession` — the non-thread
   timer-driven publish path, which bypasses `UnstructuredIntakeLane`/`UnstructuredPayloadMapper`
   entirely — always landed and referenced the transcript regardless of `changeType`, so an empty
   group would still publish a non-empty `objects` list; and `RawObjectValidator.validate`
   unconditionally rejected an empty `objects` list, which would have blocked `DELETE_REVIEW`
   (Calendar's and Drive's existing ones too, not just Slack's new one) from ever reaching Kafka
   through the real validator. Both fixed to special-case `ChangeType.DELETE_REVIEW`, mirroring
   `UnstructuredPayloadMapper`'s existing rule. See `docs/slack-deletion-contract.md`.
5. **Thread groups** — reuse the same removal logic; publish immediately (no `correctionDelay`,
   nothing to debounce against — threads have no timer).
6. **Known limitations** (not fixed here, out of scope per the plan):
   - A deletion/tombstone arriving **before** the message it targets was ever indexed is a no-op;
     if that message later arrives via delayed delivery, it's ingested normally with no record it
     was already deleted (Slack doesn't guarantee ordered delivery).
   - §3.4 (stale-claim recovery after restart) remains separate and unaddressed.
   - ~~A channel group `confirmPublished` before a reply caused thread migration is permanently
     unreachable for correction~~ — **fixed, see §3.1a below.**

### 3.1a `superseded` removed — a channel group survives its root being threaded
(`docs/plans/slack-superseded-fix.md`, 2026-10-22) `GroupState.superseded` is gone entirely (field
and every read/write site — `GroupStateStore.markSuperseded`/`confirmPublished`/
`claimForPublication`, `ChatGroupStateReconciler.isOpenForNewMessages`,
`SlackSessionScheduler.onDeadline`). Previously, once a channel (time-gap) group's root got its
first reply and was migrated into a brand-new thread group, the channel group was marked
`superseded` and permanently blocked from publishing/accepting new messages again — which didn't
just retire a lone root, it **stranded any other real message already sitting in that same channel
group** (proven by the repro test, re-enabled below), since the migration only ever copies the
root's own snapshot forward, never anything else in the group.
Fix: the channel group now keeps living normally after its root is threaded — its own timer,
its own publish, still open to new messages within the session gap — with its own copy of the
root still in it. The root legitimately appears in two transcripts (channel + thread) by design;
see `docs/slack-deletion-contract.md`. A root edit/deletion now also propagates to the channel
group's own copy (reusing `mergeIntoGroupState`/`removeMessage`, the same primitives the ordinary
single-group path already uses — no new merge logic, so the existing redelivery-idempotency
guarantees carry over unchanged); `SlackEventsController` schedules the channel-group step first,
uncaught, before the thread's publish, so it can never be silently skipped on a retry.
`secondNonThreadMessageIsNotStrandedInTheOriginalChannelGroupWhenTheFirstIsLaterThreaded`
(`ChatGroupStateReconcilerTest`) is the re-enabled acceptance test for this (previously
`@Disabled` from `caceb819`).
Existing stranded rows already in Postgres (`superseded=true`, never published) are **not**
touched by this change — an audit query is documented in the plan but intentionally not run; any
backfill/republish decision is left to whoever owns the data, since a row with non-root content
represents real, deliberately-not-yet-republished lost data (see the plan's §3 for the tradeoffs).
7. **Verified via the full test suite** (full `mvn test` run has 8 pre-existing, unrelated
   environment failures — `BridgeApplicationTests`/`CrmBridgeControllerTest`/
   `HubSpotDealRecordObjectMapperNamingStrategyTest` all need a local Postgres/Kafka this machine
   doesn't have running; reproduces identically on `main` before this change). **Not yet verified
   against a real Slack workspace** — a real tombstone/`bot_message` payload, and the thread-root
   migration + correction-delay timing end to end, are still outstanding; fold into §6's pre-demo
   dry run, don't treat unit-green as equivalent to that check.

### 3.2 Gmail edit/delete handling — declined, see decision above
**Decision (2026-10-18): out of scope, not just unsequenced.** No further work planned here
unless you revisit it — see open question 1 above. Left as a documented record rather than
deleted outright; see open question 3 above for whether to remove this section entirely.

### 3.3 Drive edit/delete handling — ✅ **DONE** (2026-10-18)
Both pieces landed together, in the order designed in `distributed-napping-pebble.md`:
`ReadCursor` was collapsed to one generic `token` field (not a 4th named field — `syncToken`/
`historyId`/Drive's `startPageToken` all play the same opaque-incremental-marker role) before
`DriveSourceReader.read()` gained its `ReadCursor` parameter.
1. **Edit** — `headRevisionId` is in the `changes.list` fields mask (no extra call) and compared
   against the previously-stored value per `fileId` via the new `DriveFileState`/
   `DriveFileStateReconciler`/`DriveFileStateStore` trio — a direct, file-for-file port of
   `CalendarEventState`/`CalendarEventStateReconciler`/`CalendarEventStateStore`'s first-seen/
   retry/updated algorithm. A change triggers a revision bump exactly like Calendar's reconciler.
2. **Delete** — `file.trashed` on the embedded `File` resource (only present when `removed=false`)
   emits `ChangeType.DELETE_REVIEW` via `RealDriveSourceReader.toTrashedSourceRecord()`, mirroring
   `RealCalendarSourceReader.toCancelledSourceRecord()`; content is never fetched for a trashed
   file. `removed=true` changes are skipped outright, unchanged from the decision below (still
   too ambiguous — deletion vs. lost access — to build on).
3. Per-file state (previous `headRevisionId`) was the one genuinely new piece — no existing
   reader had local per-item state before Calendar's own `CalendarEventState` precedent, which
   this directly reused the shape of (see `DriveFileStateStore`, keyed `_drive_files/{tenantId}/
   {fileId}.json`, same Postgres-backed `ControlPlaneStateStore` as Calendar's).
4. **A real cross-team contract bug was found and fixed along the way, not scope creep:**
   `RawObjectEvent`'s own contract says `DELETE_REVIEW` must carry an empty `objects` list, and
   Mind's written response (`teams/bridge/docs/raw-object-event-source-ref-schema-response.md`)
   already states this "matches our calendar lane's existing behavior" — but Calendar's actual
   code didn't enforce it (it still landed a sparse metadata object). Fixed in
   `UnstructuredPayloadMapper`/`UnstructuredIntakeLane` for both sources at once.
5. **Verified via the full test suite only (618 tests, 0 failures)** — `DriveFileStateReconcilerTest`
   (ported from `CalendarEventStateReconcilerTest`), extended `RealDriveSourceReaderTest`
   (baseline/incremental/410-fallback/trashed/edit-revision cases), `UnstructuredPayloadMapperTest`
   (new), plus `UnstructuredIntakeLaneTest`/`UnstructuredIntakeControllerTest` additions. **Not yet
   verified against a real Drive account/credentials** — all Drive/Google API calls are mocked at
   the unit level; the plan's own "manual check against a real Drive folder" step (create → edit →
   trash, confirm cursor/revision/DELETE_REVIEW behavior end to end) is still outstanding — fold
   into §6's pre-demo dry run below, don't treat unit-green as equivalent to that check.
4. Not yet placed in §6's week-by-week sequencing — see open question 2 above.

### 3.4 Slack stale-claim recovery after restart
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
   **Exact call site:** the "content changed" branch inside `ChatGroupStateReconciler
   .mergeIntoGroupState` — not inside `groupStateStore.update`'s callback/lock itself, since that
   holds a per-groupId lock and an LLM call is too slow to run under it. And only when this is a
   genuine edit of existing content (`current.messages()` non-empty, revision bumping from an
   already-real N to N+1) — not the "first content" transition (revision -1 → 0 via the
   unpublished-collapse rule in `newGroupState`), where there is no previous session to diff
   against at all.
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

**Risk flag, added 2026-10-18 — known-tight, not yet a decided problem:** rough accounting puts
remaining work (dry run, code review, §3.1 Slack delete, §4.0/§4.1/§4.2, plus demo/presentation
prep — slides + rehearsal + 3 `/code-presentation-prep` sessions, not previously budgeted
anywhere in this doc) at ~11-14+ working days of code/testing *plus* presentation prep, against
~12 working days actually available between 2026-10-18 and the demo (17 calendar days minus ~2
weekends and the 2026-10-27 Israeli Knesset election day, a holiday). That's effectively over
budget once presentation prep is counted, not merely tight — **and it grew again on 2026-10-18**
when §3.1 (Slack delete) was reprioritized from "remaining slack time, if any" to immediate,
ahead of the dry run. **Decision (2026-10-18, reaffirmed): attempt the full scope anyway; don't
pre-cut anything now.** §4.2's fallback (below) is the pre-identified lever if a cut becomes
necessary later, but it is not being invoked proactively.

**Revised ordering, confirmed 2026-10-18 (supersedes the old Week 1-4 framing below, which is
kept only for the historical record of what already shipped):**

| Date | Day | What |
|---|---|---|
| ~10-07 to 10-13 | — | ✅ §1 Gmail incremental sync — done. |
| ~10-14 to 10-18 | — | ✅ §2 Drive real reader + ✅ §3.3 Drive edit/delete (`headRevisionId`/`trashed` via `changes.list`/`startPageToken`) — both done 2026-10-18, unit-tested only (real-account check folded into the dry run row below). |
| **10-19 (Mon)** | ב' | **§3.1 Slack `message_deleted` — starts now, ahead of the dry run.** Reprioritized 2026-10-18 off "remaining slack time, if any": `SlackEventMessageParser` extracts the deleted message's `ts` (from `previous_message.ts`/`deleted_ts` — confirm exact field against Slack's docs); `ChatGroupStateReconciler` gets a new reconciliation path that removes the matching `MessageSnapshot` and branches — messages remain → `UPSERT` at a bumped revision; `GroupState` empties out → `ChangeType.DELETE_REVIEW` (see §3.1's corrected text for why a single deleted message is *not* automatically a `DELETE_REVIEW`). |
| 10-20 (Tue) | ג' | Likely finish §3.1. **Once this "base layer" is ready, run the pre-demo dry run immediately — not on a fixed date** (per your call: "ASAP once the base layer is ready," contingent on you having real credentials ready for all 5 sources). |
| — | — | **Pre-demo dry run** (whenever §3.1 lands + you're ready): all 5 sources on `mode=real` together. Checklist — trigger each `/run` once, confirm `rejected()==0` for all five; confirm real content (not metadata) lands in MinIO with correct `mimeType`; run Gmail/Calendar/Drive back-to-back to confirm independent first-time OAuth consent + cached-token reuse on a second run; re-run every source a second time and confirm cursor-bearing sources (Slack/Calendar/Gmail/Drive) do no redundant work. |
| — | — | **Thorough code review pass** (§6, item added 2026-10-18) — runs right after the dry run, before §4, so §4 builds on reviewed code. Scope (how much of the lane, which sources) still to be decided when you get there. |
| 10-21 to 10-22 | ד'-ה' | §4.0 `LlmClient` interface + Groq implementation; §4.1 `change_significance_score` real scoring (smaller of the two LLM features — first, to prove the `LlmClient` seam end to end). Parallel: first `/code-presentation-prep` session (started early, not left for the last week). |
| 10-23 to 10-24 | ו'-ש' | — weekend |
| 10-25 to 10-26 | א'-ב' | §4.2 LLM session-boundary decision starts — the largest remaining item. Second `/code-presentation-prep` session. |
| **10-27 (Tue)** | ג' | 🔴 **holiday — Knesset election day.** Not a working day. |
| 10-28 to 10-29 | ד'-ה' | §4.2 continues. If the full split logic doesn't fit, the pre-approved fallback (continuation-only decision, split deferred) applies — flag before cutting, don't decide silently. |
| 10-30 to 10-31 | ו'-ש' | — weekend |
| 11-01 to 11-02 | א'-ב' | Buffer/overflow for whatever from §4 ran long. Final `/code-presentation-prep` session (closing understanding gaps + hard-question practice). |
| 11-03 (Tue) | ג' | Dress rehearsal + final check that everything still stands. |
| **11-04 (Wed)** | ד' | 🎯 **Demo.** |

§3.2 (Slack stale-claim recovery) and §5's deferred items stay out of scope regardless of time
remaining, unchanged from the original decision.

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
