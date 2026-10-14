# Unstructured-intake lane — plan

Design notes for Malki's lane (Gmail / Drive / Calendar / Slack / WhatsApp intake). Referenced
throughout the code (`FixtureSourceLoader`, `ChatSessionGrouper`, `AllowlistScopingFilter`,
`MinioObjectStorageClient`, `RawObjectEvent`) — this file is the source of truth those comments
point to.

## Current state

```
fixtures/unstructured/*.json|*.txt (or real sources, per bridge.intake.<source>.mode)
        │  (ClassPathResource / ResourcePatternResolver, or Real...SourceReader)
        ▼
SourceRecordLoader.load(RawSourceType)      ← per-source dispatch → List<SourceRecord>
        │  (Slack/WhatsApp go through ChatMessage → ChatSessionGrouper first)
        ▼
UnstructuredIntakeLane (one instance per RawSourceType, intake/lane/) extends
core.AbstractIntakeLane — template method run(cursor, asOf), per record:
        ├─ SourceRecordReaderAdapter.read()      → wraps SourceRecordLoader for one fixed source
        ├─ UnstructuredPayloadMapper.map()          ├─ AllowlistScopingFilter.decide()          → ScopeDecision (stub: always INCLUDED)
        │  (pure - no I/O)                          ├─ ObjectKeyBuilder.build()                 → object key
        │                                            └─ IntakeLandingService.resolveContentType() → pure, no I/O
        ├─ UnstructuredIntakeLane.afterMap()      → IntakeLandingService.land()  → MinIO (real; the one side effect)
        ├─ RawObjectValidator.validate()         → fails fast on a malformed RawObjectEvent
        └─ KafkaRawObjectPublisher.publish()      → RawObjectEvent → Kafka/Redpanda (real)
```

Triggered only by `POST /api/bridge/intake/{sourceType}/run` (`UnstructuredIntakeController`) —
synchronous, on demand, no scheduling. Returns `core.IntakeResult` (`{read, published, rejected,
errors}`). Everything from `UnstructuredIntakeLane` downward is already source-agnostic and talks
to real infra (MinIO, Redpanda) — the fixture coupling lives entirely inside the `Fixture...`
readers under `intake/parser/`.

## Target design: real sources

**Decision (revised): adopt `core.AbstractIntakeLane`, one instance per source.** The original
version of this plan kept a hand-rolled `UnstructuredIntakeService.run(RawSourceType)`
orchestrator, reasoning that splitting into one `AbstractIntakeLane` *subclass* per
`RawSourceType` (the way the CRM lane does) would mean 5 near-duplicate subclasses for no real
Template Method benefit. That objection applies to subclassing per source, not to *instantiating*
per source: `UnstructuredIntakeLane` (`intake/lane/`) is a single concrete class, constructed once
per `RawSourceType` (excluding MEETING) with a different `SourceReader` but the *same*
mapper/validator/publisher instances (`UnstructuredIntakeLaneConfig`). Nothing is duplicated —
scoping/landing/publishing logic still lives in exactly one place (`UnstructuredPayloadMapper`,
`KafkaRawObjectPublisher`) — and the lane now goes through the same
`read -> map -> validate -> publish` template as every other lane in Bridge, instead of a bespoke
loop. `SourceRecordLoader` still does the per-source dispatch (`EmailSourceReader`/
`DriveSourceReader`/etc.); it's now called from `SourceRecordReaderAdapter` (bound to one fixed
`RawSourceType`) instead of directly from the orchestrator.

`UnstructuredIntakeService` and `PointerEventPublisher` are gone. Their logic moved into
`UnstructuredPayloadMapper` (mapping — a pure transform, see below) and `KafkaRawObjectPublisher`
(publishing); `RawObjectValidator` slots in unchanged as the lane's `PayloadValidator`. The
controller's response shape changed from `List<RawObjectEvent>` to `core.IntakeResult`, since
that's what `AbstractIntakeLane.run()` produces — a deliberate breaking change, made with no known
consumers of the old shape at the time.

**The `afterMap` hook**: landing bytes in MinIO is a side effect specific to this lane — the CRM
lane (and presumably a future one) map straight to their published payload with no separate
durable copy to write. Forcing every lane to have a "land" step (or folding the I/O into `map()`,
which was tried first and reverted) would either force unrelated lanes to implement a no-op they
don't need, or blur `PayloadMapper.map()`'s contract into "transform, but also maybe do I/O".
Instead, `core.AbstractIntakeLane` gained one optional hook method — `afterMap(source, payload,
asOf)`, no-op by default, called between `map()` and `validate()` — that only lanes which need a
persistence step override. `UnstructuredIntakeLane` overrides it to call `IntakeLandingService
.land(...)`; every other lane is unaffected (they simply don't override it). This keeps
`UnstructuredPayloadMapper.map()` a genuinely pure function: `contentType`/`sizeBytes` are derived
from the `SourceRecord` itself (`IntakeLandingService.resolveContentType` is pure; `sizeBytes` is
just `rawContent().length`), so building the event never needs to wait on the actual MinIO put.

**The seam**: `FixtureSourceLoader` splits into a thin dispatcher (keeps the
`load(RawSourceType) -> List<SourceRecord>` contract) plus one reader interface per source
(`EmailSourceReader`, `DriveSourceReader`, `CalendarSourceReader`, `SlackSourceReader`,
`WhatsAppSourceReader`), each with:
- a `Fixture...` implementation — today's parsing logic, moved as-is from `FixtureSourceLoader`'s
  private methods (`loadEmail`, `loadDrive`, `loadCalendar`, `loadSlackMessages`,
  `loadWhatsAppMessages`), no behavior change.
- a `Real...` implementation, added later, one source at a time — calls the actual API and
  returns the same `SourceRecord`/`ChatMessage` shapes. No downstream code changes: `SourceRecord`
  and `ChatMessage` are already generic enough to carry real API data unchanged.

Which implementation is active is a per-source config flag
(`bridge.intake.<source>.mode: fixture|real` in `application.yml`), not a single global
fixture/real switch — the README already documents that fixture realism was chosen per source,
not uniformly, so sources go real independently as each is ready.

### Real reader specifics, per source

| Source | API | Pagination / incremental sync | Notes |
|---|---|---|---|
| Gmail | Gmail API, `users.messages.list`/`get` | `pageToken`; `historyId` for incremental | OAuth2 (user consent or service account + domain-wide delegation) |
| Calendar | Google Calendar API, `events.list` | `syncToken` | same OAuth as Gmail |
| Drive | Google Drive API, `files.list`/`changes.list` | `pageToken` / `startPageToken` | same OAuth as Gmail |
| Slack | Slack Web API, `conversations.history` | cursor-based | OAuth bot token |
| WhatsApp | **no official export API** for personal WhatsApp | n/a | only path today is a manual `.txt` export per chat — this source likely stays fixture/file-drop-based even after the others go real |

**Gmail attachment shape mismatch:** `FixtureEmailSourceReader` assumes attachment content arrives
embedded in the message JSON (base64 inline, like the calendar/drive fixtures). The real Gmail API
doesn't work that way — `users.messages.get` returns only attachment metadata/IDs, and the actual
bytes require a separate `users.messages.attachments.get` call per attachment. `RealEmailSourceReader`
will need to fetch attachments by reference (closer to how `RealWhatsAppSourceReader` reads attachment
files separately) rather than reading them out of the same payload as the body.

### WhatsApp: how the file actually gets to the server

There is no automated way to trigger a WhatsApp export — a person has to open the chat, use
WhatsApp's own **Export Chat** feature (Settings/⋮ → More → Export Chat, with or without
media), and the phone shares the resulting `.txt` (and media files) out through its own share
sheet. "Going real" for WhatsApp therefore doesn't mean polling an API — `RealWhatsAppSourceReader`
(implemented) just reads whatever `.txt` files are sitting in a configured directory
(`bridge.intake.whatsapp.import-dir`), the same export line format the fixtures already use,
via the shared `WhatsAppExportLineParser`. How the file gets *into* that directory is a
separate, still-open question — options, cheapest first:
1. Someone copies the file onto the server manually (what's implemented now — no automation).
2. A cloud-synced folder (Drive/Dropbox client running on the server) the phone shares into.
3. A dedicated upload endpoint on Bridge itself (`POST .../whatsapp/upload`, multipart) — more
   code, but keeps delivery inside the system instead of depending on an external sync client.

### Trigger: scheduled polling, not webhooks

Real sources need something to actually call `run(sourceType)` periodically — the manual HTTP
trigger stays for ad hoc/debug use, but the default becomes a `@Scheduled` poll (interval
configurable, e.g. every 5–15 minutes) that calls the same `run(sourceType)` path per source.

Considered and rejected for now (revisited since for Slack — see "Slack real-time ingestion"
below): push/webhooks (Gmail Push via Google Pub/Sub, Slack Events API) — near-real-time, but
needs a publicly reachable endpoint plus subscription/renewal management, which was meaningfully
more infrastructure than this stage needed at the time. Gmail/Calendar/Drive still poll only;
revisit those only if polling latency becomes an actual problem.

### Slack real-time ingestion (Events API)

Implemented, alongside (not instead of) Slack's own polling path above — `POST
/api/bridge/slack/events` (`SlackEventsController`), signature-verified via
`SlackSignatureVerifier`, parsed by `SlackEventMessageParser` into the same `ChatMessage` shape
`RealSlackSourceReader` produces. Two message events are supported: a plain new "message" (no
`subtype`), and `message_changed` (an edit) — the parser reads the edited content from the
event's nested `message` object, using `message.ts` (the ORIGINAL message's stable id, not the
edit notification's own top-level `ts`) as `ChatMessage.id`, so it resolves to the same group as
the message being edited. `message_deleted` and every other `subtype` are still acknowledged
with 200 but not reconciled — not implemented yet (see below).

- **Non-thread messages** go through `ChatGroupStateReconciler.reconcileNonThreadedMessage`:
  merged into a persisted `GroupState` (time-gap session, same `session-gap-minutes` grouping as
  the polling path) but the transcript itself is withheld — only this message's own new
  attachment records publish immediately. `SlackSessionScheduler` (re)schedules an inactivity
  timer off `GroupState`'s own message timestamps every time a message lands, superseding any
  earlier timer for that group; when the timer fires it reloads the group fresh, claims it via
  `GroupStateStore.claimForPublication` (revision-guarded, so two competing publish attempts for
  the same revision can't both win), publishes the transcript, and only marks it published via
  `GroupStateStore.confirmPublished` once Kafka has actually acknowledged it
  (`UnstructuredIntakeLane.afterPublish`) — never on the strength of having built the record.
- **Threaded messages** (`threadId() != null`) keep the batch/poll path's existing immediate
  merge-and-publish behavior (`ChatGroupStateReconciler.group`) unchanged — no delayed-transcript
  session, no scheduler involvement.
- **Publication failures are surfaced to Slack**: `SlackEventsController` inspects the
  `IntakeResult` from the lane run and returns a non-2xx status if anything was rejected
  (validation/MinIO/Kafka failure), instead of always acking 200 — Slack then retries the
  delivery. This is safe because `GroupState` is already durably persisted (MinIO control-plane)
  before this response is returned, and a redelivery re-resolves to the same group by messageId
  rather than creating a duplicate one.
- **Attachments are retry-safe**: each attachment carries Slack's own stable `File.getId()`
  through as `ChatMessage.Attachment.fileId`/`SourceRecord.attachmentFileId` (not
  `attachmentIndex`, which is just a position and can shift if an edit reorders/removes files).
  Once an attachment's publish actually succeeds, `UnstructuredIntakeLane.afterPublish` records
  it via `GroupStateStore.confirmAttachmentPublished`, keyed on `(messageId, fileId)` and carried
  forward across edits/re-reads (`MessageSnapshot.confirmedAttachmentFileIds`) — a Slack retry or
  an unrelated edit of the same message never republishes an attachment that already landed.

Not yet implemented: `message_deleted` handling (no reconciliation logic exists for it at any
layer, not just a blocked parser filter — deleting a `MessageSnapshot` from a `GroupState` isn't
built), startup/stale-claim recovery (an in-flight `claimedRevision`/scheduler timer is lost on
process restart — the next edit or a future recovery pass would need to pick it back up), a
fast/async ACK (the whole webhook request, including any attachment download, still runs
synchronously before responding to Slack), and LLM-based semantic session grouping (grouping is
still purely time-gap/thread based).

### No intermediate datastore for payloads

Fetched records go straight from the API response into the existing
scoping → land (MinIO) → publish (Kafka) sequence, exactly like fixtures do today — MinIO is
already the durable landing zone for raw content and Kafka is already the pointer-event stream,
so there is no reason to add a staging database in between. This applies per record/batch within
one poll run, same as it does for one fixture-driven HTTP call today.

### Cursor persistence

Incremental sync needs one small piece of state per `(tenantId, sourceType)`: the last
`historyId`/`syncToken`/`pageToken`. This is not the payload — just a token — so it doesn't need
a new datastore: store it as a small JSON object in MinIO (e.g.
`_cursors/{sourceType}/{tenantId}.json`), read at the start of a poll run, written back at the
end. `core.ReadCursor` already has a `since: Instant` field reserved for exactly this
(`core/ReadCursor.java`).

**Stage 1 (storage only):** `ObjectStorageClient.getObject()` (and its MinIO implementation) plus
`intake.lane.CursorStore`, which reads/writes a `ReadCursor` as JSON at the key above, defaulting
to `ReadCursor.full()` when nothing has been saved yet.

**Stage 2 (wired for Slack, first source to go real):** `UnstructuredIntakeController` now loads
the cursor via `CursorStore.load(sourceType, tenantId)` before calling `lane.run(cursor, asOf)`,
and saves `new ReadCursor(asOf)` via `CursorStore.save(...)` right after — only reached if `run()`
returns normally, matching the "update only after success" rule below.
`SourceRecordReaderAdapter` forwards the cursor into `SourceRecordLoader.load(sourceType,
cursor)` instead of dropping it; `SourceRecordLoader` passes `cursor.since()` into
`SlackSourceReader.read(since)`, and `RealSlackSourceReader` sets Slack's `conversations.history`
`oldest` parameter from it (omitted entirely when `since` is null, i.e. `ReadCursor.full()`,
which still reads all history — this is what every fixture-mode source and Slack's own first-ever
run does). `FixtureSlackSourceReader` accepts the same `since` parameter for interface parity but
ignores it.

Email/Drive/Calendar/WhatsApp still read everything every run — `SourceRecordLoader` only threads
`since` through the `SLACK` case today. Wiring the rest is per-source work (Gmail: `historyId`,
Calendar: `syncToken`, Drive: `pageToken`/`startPageToken`) that lands alongside each source's
`Real...` reader going live, the same way Slack's did.

Known limitation: a chat session that straddles a poll boundary (still active, gap < the
session-gap threshold, but a poll runs mid-conversation) gets split into two `SourceRecord`s
instead of one contiguous transcript — the tail after the cursor has no way to know about the
head already landed in the previous run. No data is lost or duplicated, just an occasional
artificial split; acceptable given polling is infrequent (a handful of runs/day), so most sessions
never straddle a boundary at all.

Update the cursor **only after** every record in the run has successfully landed and published —
never before. If a run crashes partway through, the next run re-reads from the old cursor and
may reprocess a few records; this is fine (at-least-once, not exactly-once) because
`RawObjectEvent.dedupKey()` is already a deterministic content hash — downstream (Mind)
de-duplicates on it, so occasional reprocessing is harmless. This is what keeps cursor handling
simple: it doesn't need to be perfectly exactly-once, just monotonic and crash-safe on the
"update last" side.

Revisit if/when multiple app instances could run the same poll concurrently (would need a lock
around cursor read+update) — out of scope while this runs as a single instance.

## What does NOT change

`ChatSessionGrouper`, `AllowlistScopingFilter`, `ObjectKeyBuilder`, `IntakeLandingService`,
`RawObjectValidator`, the `SourceRecord`/`ChatMessage`/`RawObjectEvent` DTOs, the MinIO/Kafka
infra wiring, and the controller's request path (`POST /api/bridge/intake/{sourceType}/run`) all
stay as they are. The blast radius of going real is contained to the loader/reader layer plus the
new scheduler and cursor-storage pieces described above.

## Adjacent, separately tracked gaps

Not part of this change, but worth knowing about before/while going real, since real traffic
will make them matter more:
- `AllowlistScopingFilter` is currently a stub that always returns `INCLUDED`; real filtering
  against `ScopingProperties.allowedDomains`/`allowedKeywords` is unbuilt.

## Rollout order

Go real one source at a time via the per-source config flag, easiest first:
1. **WhatsApp** — done. No OAuth needed, but also no live API; stays a manual file-drop even as
   "real" (real export file instead of a fixture file).
2. **Slack** — done, including cursor since-filtering (see "Cursor persistence" above) and,
   separately, real-time ingestion via the Events API (see "Slack real-time ingestion" above,
   runs alongside the polling path). One OAuth bot token (`channels:history`/`groups:history`/
   `files:read`/`users:read`), no per-user consent flow. Sender names are resolved via
   `users.info` (cached per run) since `conversations.history` only ever returns a raw user id —
   the real-time path uses the raw Slack user id as sender instead (see
   `SlackEventMessageParser`'s javadoc).
3. **Gmail / Calendar / Drive** — need a Google OAuth setup before the first real call can be
   made. Personal Gmail accounts (no Workspace admin) can't use a service account with
   domain-wide delegation — that only works for a Workspace-managed domain. For a personal
   account, the reader instead needs the standard OAuth2 **user-consent** flow: an OAuth client
   (Desktop app type), a one-time interactive authorization in a browser to obtain a refresh
   token, then silent token refresh from that point on. Not yet implemented.
